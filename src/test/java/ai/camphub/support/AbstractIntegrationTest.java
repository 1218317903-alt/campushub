package ai.camphub.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 集成测试基类：启动真实应用（随机端口）+ 真实 MySQL（Testcontainers）。
 *
 * <h2>为什么走真实 HTTP 而不是 MockMvc</h2>
 * 集成测试的价值在于验证"整条链路真的通"。MockMvc 不会经过真实 Servlet 容器、
 * 也不一定经过以 Bean 形式注册的 Servlet Filter —— 而本项目的 traceId 透传、
 * 统一错误响应、后续的认证过滤器都挂在 Filter/ControllerAdvice 上。
 * 用真实 HTTP 客户端发起请求，这些环节才会被真正执行。
 *
 * <h2>为什么用 JDK 自带 HttpClient + JsonPath</h2>
 * 二者都不属于 Spring Boot 的测试 API，不受框架大版本改名影响（Boot 4 已经
 * 重组过一批测试相关 starter/注解位置）。测试基础设施本身越少依赖"正在变化的东西"越好。
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
}
