package ai.camphub.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 来源 IP 解析测试。
 *
 * <h2>为什么这是一组安全测试，而不是工具类测试</h2>
 * 来源 IP 有两个用途：按 IP 限流、写入审计日志。两处都依赖它"是可信的"。
 * 而 {@code X-Forwarded-For} 由客户端完全控制 —— 在没有可信反向代理的部署里，
 * 攻击者可以对每个请求伪造一个新值，于是"按 IP 限流"被逐请求绕过，
 * 审计日志里留下的也全是假地址。
 *
 * <p>因此这里的核心断言不是"能解析出地址"，而是<b>"默认情况下伪造的头部不生效"</b>。
 * 集成测试跑在信任代理头的配置下（为了让每个用例拥有独立的限流桶），
 * 所以那条属性只能在这里、以单元测试的形式被钉住 —— 这正是它必须存在的原因。
 */
class ClientIpResolverTest {

    /** 攻击者伪造的来源。若它被采信，限流就形同虚设。 */
    private static final String SPOOFED = "203.0.113.99";

    @Test
    @DisplayName("默认配置下忽略 X-Forwarded-For 与 X-Real-IP，一律采用 remoteAddr")
    void shouldIgnoreForwardedHeadersWhenNotTrusted() {
        HttpServletRequest request = requestWithSpoofedHeaders("10.0.0.7");

        assertThat(ClientIpResolver.resolve(request, false)).isEqualTo("10.0.0.7");
    }

    @Test
    @DisplayName("信任代理头时取 X-Forwarded-For 最左一项（最初发起请求的客户端）")
    void shouldTakeLeftmostForwardedForWhenTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        // 代理链：客户端 → 中间代理1 → 中间代理2。最左一项才是原始客户端。
        request.addHeader("X-Forwarded-For", SPOOFED + ", 198.51.100.1, 198.51.100.2");

        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo(SPOOFED);
    }

    @Test
    @DisplayName("信任代理头但未提供 XFF 时回退到 X-Real-IP")
    void shouldFallBackToRealIpWhenTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Real-IP", "198.51.100.30");

        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("198.51.100.30");
    }

    @Test
    @DisplayName("信任代理头但两个头都为空时回退到 remoteAddr，而不是返回空串")
    void shouldFallBackToRemoteAddrWhenHeadersBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "   ");
        request.addHeader("X-Real-IP", "");

        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("10.0.0.7");
    }

    @Test
    @DisplayName("非 Web 上下文（request 为 null）返回 unknown，绝不返回 null")
    void shouldReturnUnknownWithoutRequest() {
        assertThat(ClientIpResolver.resolve(null, false)).isEqualTo(ClientIpResolver.UNKNOWN);
        assertThat(ClientIpResolver.resolve(null, true)).isEqualTo(ClientIpResolver.UNKNOWN);
    }

    @Test
    @DisplayName("超长头部被截断到列宽：伪造的长值不该变成一次 500")
    void shouldTruncateOversizedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("X-Forwarded-For", "a".repeat(500));

        String resolved = ClientIpResolver.resolve(request, true);

        assertThat(resolved).hasSize(64);
    }

    /**
     * 构造一个同时带上两个伪造头部的请求。
     *
     * @param remoteAddr 容器记录的真实对端地址
     * @return 请求
     */
    private static HttpServletRequest requestWithSpoofedHeaders(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader("X-Forwarded-For", SPOOFED);
        request.addHeader("X-Real-IP", SPOOFED);
        return request;
    }
}
