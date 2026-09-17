package ai.camphub.identity.infrastructure.security;

import ai.camphub.common.config.RequestProperties;
import ai.camphub.common.error.RateLimitedException;
import ai.camphub.common.web.ClientIpResolver;
import ai.camphub.identity.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 认证类接口的限流器（固定窗口计数）。
 *
 * <h2>它防的是什么</h2>
 * 不是"性能保护"，而是<b>凭据攻击的成本抬升</b>：没有它，攻击者可以在一台机器上
 * 每分钟尝试数万次密码。加上"同一来源每分钟 10 次"之后，同样的尝试需要极长时间，
 * 攻击从"可行"变成"不划算"。
 *
 * <h2>三个必须说清楚的局限</h2>
 * <ol>
 *   <li><b>单实例内存实现</b>：多实例部署时每个实例各自计数，实际额度是"配置值 × 实例数"。
 *       这不是设计疏漏，而是 Phase 02 明确不引入 Redis 的代价。
 *       接入 Redis 是 Phase 09 的任务，那时会先压测确认它确实是瓶颈。</li>
 *   <li><b>重启即清零</b>：进程重启后计数归零。</li>
 *   <li><b>固定窗口存在边界效应</b>：跨窗口边界时，两个相邻窗口各自的额度会在
 *       瞬间连用，短时峰值可达配置值的两倍。对"抬高攻击成本"这个目标而言可以接受
 *       （它不影响长期速率），因此不引入更复杂的滑动窗口。</li>
 * </ol>
 *
 * <h2>计数键为什么必须区分桶</h2>
 * 登录、注册、刷新各自独立计数。若共用一个计数器，用户正常刷新几次令牌
 * 就会把登录额度耗掉，产生"越用越登不上"的荒谬体验。
 */
@Component
public class AuthRateLimiter {

    /**
     * 键数量上限。达到上限时清理已过期的窗口。
     *
     * <p>没有这条限制，攻击者用大量伪造来源 IP 就能把内存撑大 ——
     * 一个防滥用的组件本身变成内存耗尽的入口，是很典型的反噬。
     */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final boolean trustForwardedHeaders;
    private final Clock clock;
    private final SecurityProperties securityProperties;

    /**
     * 构造注入。
     *
     * @param requestProperties   HTTP 层配置（决定是否信任代理头）
     * @param clock               时钟，注入以便测试控制时间
     * @param securityProperties  安全配置（提供各桶的额度）
     */
    public AuthRateLimiter(RequestProperties requestProperties,
                           Clock clock,
                           SecurityProperties securityProperties) {
        this.trustForwardedHeaders = requestProperties.trustForwardedHeaders();
        this.clock = clock;
        this.securityProperties = securityProperties;
    }

    /**
     * 登录桶。
     */
    public void checkLogin() {
        acquire("login", securityProperties.rateLimit().login());
    }

    /**
     * 注册桶。
     */
    public void checkRegister() {
        acquire("register", securityProperties.rateLimit().register());
    }

    /**
     * 刷新令牌桶。
     */
    public void checkRefresh() {
        acquire("refresh", securityProperties.rateLimit().refresh());
    }

    /**
     * 消耗一次额度。
     *
     * @param bucket 桶名
     * @param rule   该桶的额度规则
     * @throws RateLimitedException 额度用尽时
     */
    private void acquire(String bucket, SecurityProperties.RateLimit.Rule rule) {
        if (rule == null || rule.capacity() <= 0) {
            // 额度配成 0 或未配置时不限流。这属于运营配置问题，
            // 让它以"忘记配置"的形式暴露，比悄悄按某个默认值限流更好排查。
            return;
        }
        String key = bucket + '|' + resolveClientIp();
        long windowMillis = rule.window().toMillis();
        long now = clock.millis();

        evictExpiredIfCrowded(now, windowMillis);

        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.startMillis >= windowMillis) {
                return new Window(now);
            }
            return existing;
        });

        long retryAfterMillis;
        synchronized (window) {
            if (window.count < rule.capacity()) {
                window.count++;
                return;
            }
            retryAfterMillis = windowMillis - (now - window.startMillis);
        }
        throw new RateLimitedException(Duration.ofMillis(Math.max(1, retryAfterMillis)));
    }

    /**
     * 键数量超限时清理已过期的窗口。
     *
     * <p>只在超过阈值时扫描，因此常态下没有额外开销；
     * 而被扫描的时机恰恰是"正在被大量不同来源访问"，此时清理正是需要的。
     *
     * @param now          当前时间
     * @param windowMillis 当前规则的窗口长度
     */
    private void evictExpiredIfCrowded(long now, long windowMillis) {
        if (windows.size() < MAX_TRACKED_KEYS) {
            return;
        }
        windows.entrySet().removeIf(entry -> now - entry.getValue().startMillis >= windowMillis);
    }

    /**
     * 解析来源 IP。
     *
     * @return 客户端 IP；无法确定时为 {@link ai.camphub.common.web.ClientIpResolver#UNKNOWN}
     */
    private String resolveClientIp() {
        HttpServletRequest request = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            request = attributes.getRequest();
        }
        return ClientIpResolver.resolve(request, trustForwardedHeaders);
    }

    /**
     * 一个固定窗口内的计数。
     */
    private static final class Window {

        /** 窗口起点。 */
        private final long startMillis;

        /** 窗口内已消耗的额度。 */
        private int count;

        /**
         * @param startMillis 窗口起点
         */
        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
