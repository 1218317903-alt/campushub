package ai.camphub.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import ai.camphub.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 统一错误契约的端到端测试。
 *
 * <h2>为什么错误契约值得单独做集成测试</h2>
 * 前端只会实现一套错误处理逻辑。只要有一个错误响应"长得不一样"，前端就会在某个
 * 罕见分支上崩掉 —— 而且往往是在线上才发现。最容易被漏掉的就是框架自己抛出的错误
 * （路径不存在、方法不支持、请求体解析失败），它们默认走 Spring 的
 * {@code DefaultErrorAttributes}，输出结构与业务错误完全不同。
 *
 * <p>本测试专门覆盖这些"框架抛出的错误"，确保它们同样被
 * {@link GlobalExceptionHandler} 接管并转换成统一结构。
 */
class ErrorContractIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("访问不存在的路径返回统一 404 结构，而不是 Spring 默认错误结构")
    void unknownPathShouldReturnUnifiedNotFound() throws Exception {
        HttpResponse<String> response = get("/api/v1/this-path-does-not-exist");

        assertThat(response.statusCode()).isEqualTo(404);
        assertCommonErrorShape(response.body(), ErrorCode.NOT_FOUND.code(), "/api/v1/this-path-does-not-exist");
    }

    @Test
    @DisplayName("错误的 HTTP 方法返回统一 405 结构")
    void wrongMethodShouldReturnUnifiedMethodNotAllowed() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/system/info", "{}", Map.of());

        assertThat(response.statusCode()).isEqualTo(405);
        assertCommonErrorShape(response.body(), ErrorCode.METHOD_NOT_ALLOWED.code(), "/api/v1/system/info");
    }

    @Test
    @DisplayName("错误响应带 X-Trace-Id 响应头，且与响应体中的 traceId 一致")
    void traceIdShouldBeConsistentBetweenHeaderAndBody() throws Exception {
        HttpResponse<String> response = get("/api/v1/this-path-does-not-exist");

        String headerTraceId = response.headers().firstValue("X-Trace-Id").orElse(null);
        String bodyTraceId = JsonPath.read(response.body(), "$.traceId");

        assertThat(headerTraceId).isNotNull();
        assertThat(bodyTraceId).isEqualTo(headerTraceId);
    }

    @Test
    @DisplayName("上游传入的合法 traceId 会被沿用，便于跨系统串联同一请求")
    void inboundTraceIdShouldBePropagated() throws Exception {
        String inbound = "gateway-trace-12345";

        HttpResponse<String> response = get("/api/v1/this-path-does-not-exist",
                Map.of("X-Trace-Id", inbound));

        assertThat(JsonPath.<String>read(response.body(), "$.traceId")).isEqualTo(inbound);
    }

    @Test
    @DisplayName("错误响应不得泄漏内部实现细节")
    void errorResponseMustNotLeakInternals() throws Exception {
        HttpResponse<String> response = get("/api/v1/this-path-does-not-exist");
        String body = response.body();

        // 这些字符串一旦出现在响应里，说明把框架/ORM/文件系统的内部信息暴露给了调用方
        assertThat(body)
                .doesNotContain("java.")
                .doesNotContain("Exception")
                .doesNotContain("at ai.camphub")
                .doesNotContain("jdbc")
                .doesNotContain("\t");
    }

    /**
     * 断言统一错误结构的关键字段。
     *
     * @param body     响应体
     * @param errorCode 期望的业务错误码
     * @param path     期望的请求路径
     */
    private void assertCommonErrorShape(String body, int errorCode, String path) {
        assertThat(JsonPath.<Integer>read(body, "$.code")).isEqualTo(errorCode);
        assertThat(JsonPath.<String>read(body, "$.message")).isNotBlank();
        assertThat(JsonPath.<String>read(body, "$.path")).isEqualTo(path);
        assertThat(JsonPath.<String>read(body, "$.traceId")).isNotBlank();
        assertThat(Instant.parse(JsonPath.<String>read(body, "$.timestamp"))).isNotNull();

        // details 恒为数组（无字段级错误时为空数组），前端可无条件遍历
        List<Object> details = JsonPath.read(body, "$.details");
        assertThat(details).isEmpty();
    }
}
