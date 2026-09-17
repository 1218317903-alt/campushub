package ai.camphub.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求建立链路追踪 ID（traceId），并保证其贯穿日志与错误响应。
 *
 * <h2>为什么必须有它</h2>
 * 出问题时要能回答"这一次失败到底发生了什么"。没有 traceId 时，只能靠时间戳在日志里猜；
 * 有了它，用户报错时给出的 traceId 就能直接定位到该请求的全部日志。
 *
 * <h2>行为</h2>
 * <ol>
 *   <li>若请求头带 {@code X-Trace-Id} 且格式合法 → 沿用（便于上游网关/前端串联）。</li>
 *   <li>否则生成新的 UUID（去掉连字符）。</li>
 *   <li>放入 MDC，供日志 pattern 的 {@code %X{traceId}} 输出。</li>
 *   <li>写入响应头 {@code X-Trace-Id}，便于前端在报错时直接反馈给用户。</li>
 *   <li><b>finally 中必须清理 MDC</b> —— 线程池会复用线程，不清理会把上一个请求的
 *       traceId 泄漏到下一个请求的日志里，造成误导性排查结论。</li>
 * </ol>
 *
 * <h2>为什么要校验入站 header</h2>
 * 入站 header 是<b>不可信输入</b>。若不校验就写进日志，攻击者可以注入换行符伪造日志行
 * （log injection）。因此这里只接受「8~64 位、字母数字与连字符」的值，其余一律丢弃并重新生成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 请求/响应头名称，也是前端与上游网关约定的字段名。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** MDC 中的键名，与 logging pattern 里的 {@code %X{traceId}} 对应。 */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /** 合法入站 traceId 的长度下限。低于此值视为无意义。 */
    private static final int MIN_LENGTH = 8;

    /** 合法入站 traceId 的长度上限，防止超长 header 污染日志。 */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    /**
     * 取出当前请求的 traceId，供非 Web 层（如异步任务的日志）复用。
     *
     * @return 当前 traceId；不在请求上下文中时返回 {@code null}
     */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID_MDC_KEY);
    }

    /**
     * 决定本次请求使用的 traceId：合法则沿用，否则新建。
     *
     * @param inbound 入站 header 值，可为 {@code null}
     * @return 最终使用的 traceId（非 null）
     */
    private static String resolveTraceId(String inbound) {
        return isAcceptable(inbound) ? inbound : newTraceId();
    }

    /**
     * 校验入站 traceId 是否可接受。
     *
     * @param value 待校验值
     * @return 合法返回 true
     */
    private static boolean isAcceptable(String value) {
        if (value == null) {
            return false;
        }
        int length = value.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * 生成新的 traceId。
     *
     * @return 32 位十六进制字符串
     */
    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
