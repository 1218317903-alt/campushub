package ai.camphub.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 解析请求的真实来源 IP。
 *
 * <h2>为什么这是一个需要显式决策的地方</h2>
 * 来源 IP 有两个用途：按 IP 限流、以及写入审计日志。两处都依赖它"是可信的"。
 * 而 {@code X-Forwarded-For} / {@code X-Real-IP} 这类请求头<b>由客户端完全控制</b>：
 * 在没有反向代理的部署里，攻击者可以对每个请求伪造一个新值，
 * 于是按 IP 限流形同虚设，审计日志里留下的也全是假地址。
 *
 * <p>因此本类的策略是<b>默认不信任这些头</b>，直接用 Servlet 容器的 {@code remoteAddr}。
 * 只有在确认部署于可信代理之后（由代理负责重写而非追加该头），
 * 才通过配置 {@code app.security.trust-forwarded-headers=true} 开启。
 *
 * <p>开启时取 {@code X-Forwarded-For} 的<b>最左</b>一项 —— 它代表最初发起请求的客户端。
 * 注意：这一项恰恰也是最容易被伪造的一段，所以可信代理必须**重写**而不是追加该头，
 * 否则开启这个开关反而是引入了漏洞。
 */
public final class ClientIpResolver {

    /** Servlet 规范中未获取到地址时的占位值，需要与"真实值"区分开。 */
    public static final String UNKNOWN = "unknown";

    /** 单个请求头值的长度上限：防止超长头部把审计列写爆。 */
    private static final int MAX_IP_LENGTH = 64;

    private ClientIpResolver() {
    }

    /**
     * 解析客户端 IP。
     *
     * @param request                当前请求，可为 null（非 Web 上下文）
     * @param trustForwardedHeaders  是否信任代理头
     * @return 客户端 IP；无法确定时返回 {@link #UNKNOWN}，绝不返回 null
     */
    public static String resolve(HttpServletRequest request, boolean trustForwardedHeaders) {
        if (request == null) {
            return UNKNOWN;
        }
        if (trustForwardedHeaders) {
            String fromXff = firstForwardedFor(request.getHeader("X-Forwarded-For"));
            if (fromXff != null) {
                return truncate(fromXff);
            }
            String realIp = trimToNull(request.getHeader("X-Real-IP"));
            if (realIp != null) {
                return truncate(realIp);
            }
        }
        String remoteAddr = trimToNull(request.getRemoteAddr());
        return remoteAddr == null ? UNKNOWN : truncate(remoteAddr);
    }

    /**
     * 取逗号分隔列表的第一项。
     *
     * @param headerValue 头部原始值
     * @return 第一项；为空时返回 null
     */
    private static String firstForwardedFor(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        int comma = headerValue.indexOf(',');
        String first = comma < 0 ? headerValue : headerValue.substring(0, comma);
        return trimToNull(first);
    }

    /**
     * 去除首尾空白，空串转 null。
     *
     * @param value 原始值
     * @return 规范化值或 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 截断到列宽以内。
     *
     * <p>宁可在入口处截断，也不让数据库抛"数据过长"——后者会把一个伪造的长头部
     * 变成一次 500，等于给攻击者提供了拒绝服务的手段。
     *
     * @param value 原始值
     * @return 截断后的值
     */
    private static String truncate(String value) {
        return value.length() <= MAX_IP_LENGTH ? value : value.substring(0, MAX_IP_LENGTH);
    }
}
