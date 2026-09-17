package ai.camphub.common.error;

import java.time.Duration;
import java.util.Objects;

/**
 * 限流异常：请求频率超过允许值。
 *
 * <p>它是 {@link BusinessException} 的特化，之所以单独成类，是因为除了状态码与文案之外
 * 还必须携带 {@code Retry-After}：限流如果只回一句"太频繁了"，调用方只能盲猜重试节奏，
 * 而盲猜的常见结果是客户端越试越密，反而加重拥塞。
 *
 * <p>把它放在 {@code common} 而不是某个业务模块，是因为限流是平台级能力
 * （后续社区发帖、搜索接口都会用到），而 {@code common} 不能被业务模块反向依赖 ——
 * 放在业务模块里，其它模块就没法复用它。
 */
public class RateLimitedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** 建议调用方等待的时长。 */
    private final transient Duration retryAfter;

    /**
     * 构造限流异常。
     *
     * @param retryAfter 建议等待时长。小于 1 秒时对外按 1 秒告知，
     *                   因为 {@code Retry-After} 的秒级粒度无法表达更短的值
     */
    public RateLimitedException(Duration retryAfter) {
        super(ErrorCode.RATE_LIMITED, buildMessage(retryAfter));
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    /**
     * @return 建议等待时长
     */
    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * 把等待时长格式化为对用户友好的文案。
     *
     * @param retryAfter 等待时长
     * @return 文案，如"操作过于频繁，请 30 秒后再试"
     */
    private static String buildMessage(Duration retryAfter) {
        long seconds = Math.max(1, retryAfter.toSeconds());
        return "操作过于频繁，请 " + seconds + " 秒后再试";
    }
}
