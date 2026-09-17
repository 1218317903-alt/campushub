package ai.camphub.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link TraceIdFilter} 的行为测试。
 *
 * <p>重点覆盖三件容易出错的细节：
 * <ol>
 *   <li>入站 header 是<b>不可信输入</b>，非法值必须被丢弃 —— 否则可以借它注入换行符伪造日志行。</li>
 *   <li>MDC 必须在请求结束后清理 —— 线程池复用线程时不清理会把 traceId 串到别的请求上。</li>
 *   <li>traceId 要同时出现在响应头和 MDC 中，否则"用户报错 → 查日志"的路走不通。</li>
 * </ol>
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("未带 X-Trace-Id 时自动生成 32 位十六进制 ID，并写入响应头")
    void shouldGenerateTraceIdWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/info");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(traceIdDuringChain));

        String generated = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertThat(generated).isNotNull().hasSize(32).matches("[0-9a-f]{32}");
        assertThat(traceIdDuringChain.get()).isEqualTo(generated);
    }

    @Test
    @DisplayName("合法的入站 X-Trace-Id 会被沿用，便于上游网关串联")
    void shouldReuseValidInboundTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "upstream-trace-0001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(traceIdDuringChain));

        assertThat(traceIdDuringChain.get()).isEqualTo("upstream-trace-0001");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo("upstream-trace-0001");
    }

    @Test
    @DisplayName("含换行符的入站 X-Trace-Id 必须被丢弃并重新生成（防日志注入）")
    void shouldRejectTraceIdWithNewline() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "bad\ntrace-injected-line");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(traceIdDuringChain));

        assertThat(traceIdDuringChain.get()).doesNotContain("\n").hasSize(32);
    }

    @Test
    @DisplayName("过短或含非法字符的入站 X-Trace-Id 会被丢弃")
    void shouldRejectMalformedTraceId() throws Exception {
        for (String malformed : new String[]{"abc", "trace id with spaces", "trace;id", "中文-trace-id"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
            request.addHeader(TraceIdFilter.TRACE_ID_HEADER, malformed);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<String> traceIdDuringChain = new AtomicReference<>();

            filter.doFilter(request, response, capturingChain(traceIdDuringChain));

            assertThat(traceIdDuringChain.get())
                    .as("非法值 [%s] 不应被沿用", malformed)
                    .hasSize(32);
        }
    }

    @Test
    @DisplayName("请求结束后必须清理 MDC，避免污染复用该线程的下一个请求")
    void shouldClearMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain(new AtomicReference<>()));

        assertThat(TraceIdFilter.currentTraceId()).isNull();
    }

    @Test
    @DisplayName("业务抛异常时也要清理 MDC（finally 分支）")
    void shouldClearMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain throwingChain = (req, res) -> {
            throw new IllegalStateException("模拟下游异常");
        };

        try {
            filter.doFilter(request, response, throwingChain);
        } catch (Exception ignored) {
            // 本测试只关心 MDC 是否泄漏，异常本身不在断言范围
        }

        assertThat(TraceIdFilter.currentTraceId()).isNull();
    }

    /**
     * 构造一个在链式调用期间捕获当前 traceId 的 {@link FilterChain}。
     *
     * <p>只有"链执行期间"能读到 MDC，因此必须在这个时点采样，否则测不出 MDC 是否真的写入过。
     *
     * @param holder 用于承接采样值的引用
     * @return 可传入 doFilter 的链
     */
    private static FilterChain capturingChain(AtomicReference<String> holder) {
        return (request, response) -> holder.set(TraceIdFilter.currentTraceId());
    }
}
