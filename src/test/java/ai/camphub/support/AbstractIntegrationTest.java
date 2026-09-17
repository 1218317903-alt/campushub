package ai.camphub.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 集成测试基类：启动真实应用（随机端口）+ 真实 MySQL（Testcontainers）。
 *
 * <h2>为什么走真实 HTTP 而不是 MockMvc</h2>
 * 集成测试的价值在于验证"整条链路真的通"。MockMvc 不会经过真实 Servlet 容器、
 * 也不一定经过以 Bean 形式注册的 Servlet Filter —— 而本项目的 traceId 透传、
 * 统一错误响应、后续的认证过滤器都挂在 Filter/ControllerAdvice 上。
 * 用真实 HTTP 客户端发起请求，这些环节才会被真正执行。
 *
 * <h2>为什么用 JDK 自带 HttpClient + Jackson</h2>
 * 二者都不属于 Spring Boot 的测试 API，不受框架大版本改名影响（Boot 4 已经
 * 重组过一批测试相关 starter/注解位置）。测试基础设施本身越少依赖"正在变化的东西"越好。
 *
 * <p>JSON 解析刻意复用容器里那个 {@code ObjectMapper}（Boot 4 起是 Jackson 3 的
 * {@code tools.jackson.databind.ObjectMapper}），而不是在测试里自己 {@code new} 一个：
 * 这样"测试怎么解析响应"与"应用怎么序列化响应"用的是同一套配置，
 * 不会出现"应用改了命名策略、测试还在按旧字段名断言"的假失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    /** 单次请求超时。测试不应该无限等待：卡住时要快速失败而不是挂住 CI。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** 复用同一个客户端，避免每次请求都重建连接池。 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            // 固定 HTTP/1.1：避免 JDK 客户端对 h2c 升级的额外尝试，让测试输出更干净
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 容器内配置好的 JSON 序列化器（Jackson 3）。 */
    @Autowired
    protected ObjectMapper jsonMapper;

    /** 真实数据库连接。用于断言"库里到底存了什么"（这是接口断言覆盖不到的一层）。 */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** 由 Spring 在随机端口启动后注入。用 @Value 取值，不依赖特定注解所在包的路径。 */
    @Value("${local.server.port}")
    private int port;

    /**
     * 拼接被测服务的绝对地址。
     *
     * <p>固定使用 {@code 127.0.0.1} 而不是 {@code localhost}：后者在部分环境下解析为
     * IPv6 的 {@code ::1}，而嵌入式容器只监听 IPv4，会产生难以理解的连接失败。
     *
     * @param path 以 / 开头的路径
     * @return 完整 URL
     */
    protected String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /**
     * 发起 GET 请求。
     *
     * @param path 路径
     * @return 响应（状态码 + 原始 body），4xx/5xx 也正常返回而不抛异常
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return get(path, Map.of());
    }

    /**
     * 发起带自定义请求头的 GET 请求。
     *
     * @param path    路径
     * @param headers 附加请求头
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> get(String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        headers.forEach(builder::header);
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 发起指定方法的请求（用于验证 405 等场景）。
     *
     * @param method   HTTP 方法
     * @param path     路径
     * @param body     请求体，可为 null
     * @param headers  附加请求头
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(REQUEST_TIMEOUT);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        headers.forEach(builder::header);
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------------
    // 以下为 Phase 02 引入的便捷方法。
    //
    // 之所以放进基类而不是某个模块的测试工具类：认证是所有业务接口的前置条件，
    // 后续每个阶段的集成测试都要"带令牌发请求"。放在基类能让"怎么发一个已认证请求"
    // 只有一处实现 —— 否则各模块会各自拼 Authorization 头，
    // 早晚会出现拼错格式却看不出原因的测试。
    // ------------------------------------------------------------------------

    /**
     * 发送 JSON 请求体。
     *
     * @param method      HTTP 方法
     * @param path        路径
     * @param jsonBody    JSON 字符串
     * @param bearerToken 访问令牌；为 null 时不带 Authorization 头
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> sendJson(String method, String path, String jsonBody, String bearerToken)
            throws IOException, InterruptedException {
        return sendJson(method, path, jsonBody, bearerToken, Map.of());
    }

    /**
     * 发送带附加请求头的 JSON 请求。
     *
     * <p>附加头用于两类场景：来源 IP（限流分桶、审计记录）与将来的幂等键。
     * 有了这个重载，测试不必为了加一个头而绕开基类自己拼 {@link HttpRequest} ——
     * 一旦有人这么做，超时、Content-Type 这些共通设置就会开始漂移。
     *
     * @param method       HTTP 方法
     * @param path         路径
     * @param jsonBody     JSON 字符串
     * @param bearerToken  访问令牌；为 null 时不带 Authorization 头
     * @param extraHeaders 附加请求头
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> sendJson(String method, String path, String jsonBody, String bearerToken,
                                            Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        return send(method, path, jsonBody, jsonHeaders(bearerToken, extraHeaders));
    }

    /**
     * 发起带令牌的 GET 请求。
     *
     * @param path        路径
     * @param bearerToken 访问令牌，可为 null
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> getWithToken(String path, String bearerToken)
            throws IOException, InterruptedException {
        return get(path, jsonHeaders(bearerToken, Map.of()));
    }

    /**
     * 发起带令牌与附加请求头的 GET 请求。
     *
     * @param path         路径
     * @param bearerToken  访问令牌，可为 null
     * @param extraHeaders 附加请求头
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> getWithToken(String path, String bearerToken, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        return get(path, jsonHeaders(bearerToken, extraHeaders));
    }

    /**
     * 发起带令牌的 DELETE 请求。
     *
     * @param path        路径
     * @param bearerToken 访问令牌，可为 null
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> deleteWithToken(String path, String bearerToken)
            throws IOException, InterruptedException {
        return send("DELETE", path, null, jsonHeaders(bearerToken, Map.of()));
    }

    /**
     * 发起带令牌与附加请求头的 DELETE 请求。
     *
     * @param path         路径
     * @param bearerToken  访问令牌，可为 null
     * @param extraHeaders 附加请求头
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> deleteWithToken(String path, String bearerToken,
                                                   Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        return send("DELETE", path, null, jsonHeaders(bearerToken, extraHeaders));
    }

    /**
     * 把响应体解析为 JSON 树。
     *
     * @param response 响应
     * @return JSON 树
     */
    protected JsonNode json(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body());
    }

    /**
     * 把请求体对象序列化为 JSON 文本。
     *
     * @param value 请求体对象（通常是 Map 或 record）
     * @return JSON 文本
     */
    protected String toJson(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    /**
     * 构造一个允许 null 值的请求体 Map。
     *
     * <p>{@code Map.of} 不允许 null，而"某个可选字段显式传 null"本身就是常见的被测行为，
     * 因此这里必须绕开它。参数按 {@code key1, value1, key2, value2, ...} 成对传入。
     *
     * @param keyValues 键值对
     * @return 有序 Map
     */
    protected static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------------------
    // 认证夹具。
    //
    // 放在基类而不是某个模块的测试类里，理由很直接：Phase 02 之后**所有**受保护接口
    // 都需要令牌，后续每个阶段的集成测试都要先"弄到一个能用的账号"。
    // 若各模块各自实现一遍注册与登录取令牌，就会出现多处"怎么算注册成功"的口径，
    // 而其中任何一处写错，表现都是"这个模块的测试莫名其妙全部 401"。
    // ------------------------------------------------------------------------

    /** 满足密码策略的默认密码：够长，且不在常见弱密码表中。 */
    protected static final String DEFAULT_PASSWORD = "quiet-otter-canyon-71";

    /** 用户名唯一化序号。 */
    private static final AtomicInteger UNIQUE_SEQ = new AtomicInteger();

    /** 来源 IP 分配序号。 */
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    /**
     * 令牌对。
     *
     * @param access  访问令牌
     * @param refresh 刷新令牌
     */
    protected record Tokens(String access, String refresh) {
    }

    /**
     * 一个可用的测试账号及其令牌。
     *
     * @param username 登录名
     * @param email    邮箱
     * @param tokens   令牌对
     */
    protected record TestAccount(String username, String email, Tokens tokens) {
    }

    /**
     * 生成一个唯一的登录名（3~32 位字母数字下划线，符合接口的字符集约束）。
     *
     * @return 登录名
     */
    protected static String uniqueUsername() {
        return "u" + Long.toHexString(System.nanoTime()) + "x" + UNIQUE_SEQ.incrementAndGet();
    }

    /**
     * 由登录名派生唯一邮箱。
     *
     * @param username 登录名
     * @return 邮箱
     */
    protected static String emailOf(String username) {
        return username + "@example.test";
    }

    /**
     * 分配一个本用例独占的来源 IP。
     *
     * <p>限流按来源 IP 分桶，而注册桶只有 5 次/小时。若所有用例都从容器网关的
     * 127.0.0.1 发起，几十个用例会互相消耗额度，于是出现"单跑通过、全量跑 429"
     * 这类最难排查的失败。用 RFC 5737 保留给文档示例的 198.51.100.0/24 网段，
     * 既保证唯一，也让阅读测试的人一眼看出"这不是真实来源"。
     *
     * @return 形如 {@code 198.51.100.7} 的地址
     */
    protected static String nextIp() {
        int n = IP_SEQ.getAndIncrement();
        return "198.51." + (100 + n / 250) + "." + (n % 250 + 1);
    }

    /**
     * 构造"指定来源 IP"的请求头。
     *
     * <p>测试期 {@code app.request.trust-forwarded-headers=true}（见 application-test.yml），
     * 因此这里设置的地址会真正成为限流分桶与审计日志所用的来源。
     *
     * @param clientIp 期望的来源 IP
     * @return 请求头
     */
    protected static Map<String, String> fromIp(String clientIp) {
        return Map.of("X-Forwarded-For", clientIp);
    }

    /**
     * 注册一个全新账号（登录名随机生成）并通过 HTTP 取回令牌。
     *
     * @param ip 来源 IP
     * @return 账号与令牌
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected TestAccount registerAccount(String ip) throws IOException, InterruptedException {
        String username = uniqueUsername();
        return new TestAccount(username, emailOf(username), registerOk(username, "integration-test", ip));
    }

    /**
     * 调用注册接口（不校验结果，供"应当被拒绝"的用例使用）。
     *
     * @param username 登录名
     * @param email    邮箱
     * @param password 密码
     * @param device   设备标识，可为 null
     * @param ip       来源 IP
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> register(String username, String email, String password,
                                            String device, String ip)
            throws IOException, InterruptedException {
        return sendJson("POST", "/api/v1/auth/register",
                toJson(body("username", username, "email", email, "password", password, "device", device)),
                null, fromIp(ip));
    }

    /**
     * 用指定登录名注册并通过 HTTP 取回令牌。
     *
     * @param username 登录名
     * @param device   设备标识，可为 null
     * @param ip       来源 IP
     * @return 令牌对
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected Tokens registerOk(String username, String device, String ip)
            throws IOException, InterruptedException {
        HttpResponse<String> response = register(username, emailOf(username), DEFAULT_PASSWORD, device, ip);
        if (response.statusCode() != 201) {
            throw new AssertionError("注册应返回 201，实际 " + response.statusCode() + "：" + response.body());
        }
        return tokensOf(response);
    }

    /**
     * 从令牌对响应中提取令牌。
     *
     * @param response 响应
     * @return 令牌对
     */
    protected Tokens tokensOf(HttpResponse<String> response) {
        JsonNode body = json(response);
        String access = body.path("accessToken").asString("");
        String refresh = body.path("refreshToken").asString("");
        if (access.isBlank() || refresh.isBlank()) {
            throw new AssertionError("令牌响应缺少令牌字段：" + response.body());
        }
        return new Tokens(access, refresh);
    }

    /**
     * 拼装 JSON 请求头。
     *
     * @param bearerToken  访问令牌，可为 null
     * @param extraHeaders 附加请求头
     * @return 请求头
     */
    private static Map<String, String> jsonHeaders(String bearerToken, Map<String, String> extraHeaders) {
        Map<String, String> headers = new HashMap<>(extraHeaders);
        headers.put("Content-Type", "application/json; charset=UTF-8");
        if (bearerToken != null) {
            headers.put("Authorization", "Bearer " + bearerToken);
        }
        return Map.copyOf(headers);
    }
}
