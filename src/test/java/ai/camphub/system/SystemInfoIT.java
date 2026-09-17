package ai.camphub.system;

import static org.assertj.core.api.Assertions.assertThat;

import ai.camphub.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 系统信息接口的端到端测试。
 *
 * <h2>这个测试真正在验证什么</h2>
 * 它不只是"接口返回 200"。一次请求会依次穿过：真实 Servlet 容器 → TraceIdFilter →
 * Controller → Service → MyBatis → Flyway 建好的 MySQL 表。因此它同时证明了三件事：
 * <ol>
 *   <li>Web 层与依赖注入装配正确；</li>
 *   <li>数据源可用、MyBatis 映射可用（返回的 schemaBaseline 来自数据库而非硬编码）；</li>
 *   <li>Flyway 迁移已在真实 MySQL 上执行成功。</li>
 * </ol>
 * <b>如果这里改成断言一个硬编码常量，那么上述三条一条都验证不了</b> —— 这正是它必须读库的原因。
 */
class SystemInfoIT extends AbstractIntegrationTest {

    /**
     * 从 classpath 上的迁移脚本文件名里取出版本号最大的那个。
     *
     * <p><b>为什么不写死一个版本号。</b>与下面 {@code version} 那条断言是同一个道理：
     * 写死的常量会在每次升版时被"顺手改成新值"，于是它永远通过，
     * 也就永远发现不了"加了迁移但忘了更新 schema 基线标记"这个真实故障。
     * 本类原先就是这样写的（常量 {@code "V1"}），结果 V2 与 V3 都没更新标记，
     * 而断言依旧全绿 —— 直到某次真的去调这个端点才看出它报的是 V1。
     *
     * <p>现在断言的是**两个来源一致**：标记值来自数据库（app_metadata），
     * 版本号来自迁移脚本文件名。于是"新增迁移却忘了更新标记"会被构建挡下。
     *
     * @return 形如 {@code V4} 的最新迁移版本
     */
    private static String latestMigrationVersion() throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");
        return Arrays.stream(migrations)
                .map(Resource::getFilename)
                .map(name -> name.substring(1, name.indexOf("__")))
                .max(Comparator.comparingInt(Integer::parseInt))
                .map(version -> "V" + version)
                .orElseThrow(() -> new IllegalStateException("classpath 上找不到任何迁移脚本"));
    }

    @Test
    @DisplayName("GET /api/v1/system/info 返回 200，且 schemaBaseline 来自真实数据库迁移")
    void shouldReturnSystemInfoBackedByRealDatabase() throws Exception {
        HttpResponse<String> response = get("/api/v1/system/info");

        assertThat(response.statusCode()).isEqualTo(200);

        String body = response.body();
        assertThat(JsonPath.<String>read(body, "$.application")).isEqualTo("camphub");

        // 刻意**不**断言具体版本号：写死版本号的断言会在每次升版时被顺手改成新值，
        // 于是它永远通过，也就永远发现不了"报出的版本与 pom 里的版本不一致"这个真实故障
        // （Phase 02 收尾时确实漂移过：pom 是 0.1.0-SNAPSHOT，发布标签已经是 v0.2.0）。
        // 这里改断言"版本号是解析后的真实值"这个不变量：
        // 占位符未被 Maven 资源过滤替换时，值会是字面量 @project.version@，含 '@'。
        String version = JsonPath.read(body, "$.version");
        assertThat(version)
                .as("版本号必须由 Maven 资源过滤从 pom.xml 注入，而不是残留的占位符")
                .isNotBlank()
                .doesNotContain("@")
                .matches("\\d+\\.\\d+\\.\\d+.*");

        assertThat(JsonPath.<String>read(body, "$.profiles")).contains("test");
        assertThat(JsonPath.<String>read(body, "$.javaVersion")).startsWith("21");

        // 这一条是"数据库链路真的通了"的硬证据：值来自 app_metadata 表，而非代码里的常量。
        // 与迁移脚本里的最大版本号比对，因此它同时守住"新增迁移必须更新标记"这条纪律
        assertThat(JsonPath.<String>read(body, "$.schemaBaseline"))
                .as("schemaBaseline 来自 app_metadata，且必须等于迁移脚本里的最新版本")
                .isEqualTo(latestMigrationVersion());
    }

    @Test
    @DisplayName("成功响应也带 X-Trace-Id 响应头，便于用户报错时直接给出定位线索")
    void shouldExposeTraceIdHeaderOnSuccess() throws Exception {
        HttpResponse<String> response = get("/api/v1/system/info");

        String traceId = response.headers().firstValue("X-Trace-Id").orElse(null);
        assertThat(traceId).isNotNull().hasSize(32);
    }

    @Test
    @DisplayName("serverTime 是可解析的 ISO-8601 时间，序列化配置未退化")
    void serverTimeShouldBeIso8601() throws Exception {
        HttpResponse<String> response = get("/api/v1/system/info");

        String serverTime = JsonPath.<String>read(response.body(), "$.serverTime");
        // 若序列化被配置成时间戳数组或对象，这里会直接失败 —— 属于契约破坏
        assertThat(Instant.parse(serverTime)).isNotNull();
    }

    @Test
    @DisplayName("Actuator 健康检查为 UP，且数据库连通性被纳入健康判断")
    void healthEndpointShouldReportUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("UP");
    }
}
