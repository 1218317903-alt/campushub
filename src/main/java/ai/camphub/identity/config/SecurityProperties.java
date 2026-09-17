package ai.camphub.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 身份与安全相关的配置（前缀 {@code app.security}）。
 *
 * <h2>为什么集中成一个类型化配置</h2>
 * 安全参数的默认值本身就是设计决策（令牌多久过期、密码最短几位、失败几次锁定）。
 * 把它们散在各个类里的 {@code @Value} 上，会导致"改了一个地方、另一个地方还是老值"，
 * 而这类不一致在安全域里是真实的漏洞来源。集中声明后，安全参数的全貌一眼可见，
 * 也便于评审时逐条追问"这个数字为什么是它"。
 *
 * <p>配置来源优先级（Spring Boot 标准）：环境变量 &gt; {@code application-{profile}.yml} &gt; {@code application.yml}。
 * 因此生产环境无需改代码，注入环境变量即可覆盖。
 *
 * @param jwt        令牌签名与有效期
 * @param password   密码策略
 * @param login      登录失败风控
 * @param rateLimit  认证类接口的限流
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        Jwt jwt,
        Password password,
        Login login,
        RateLimit rateLimit
) {

    /**
     * 令牌配置。
     *
     * @param secret           HMAC 签名密钥。**不设默认值**，必须由环境变量 {@code APP_JWT_SECRET} 注入；
     *                         为空时应用启动即失败，而不是悄悄用一个人人可猜的默认密钥跑起来。
     * @param issuer           {@code iss} 声明，用于区分本服务签发的令牌
     * @param accessTokenTtl   访问令牌有效期。刻意短（15 分钟）：它是"无状态"的，
     *                         短有效期是撤销延迟的上界，也是密钥轮换的暴露窗口上界
     * @param refreshTokenTtl  刷新令牌有效期。长（30 天）但**有状态**（入库、可撤销、可轮换）
     */
    public record Jwt(
            String secret,
            String issuer,
            Duration accessTokenTtl,
            Duration refreshTokenTtl
    ) {
    }

    /**
     * 密码策略。
     *
     * @param minLength 最短长度。取 10 而不是 8：长度是抵抗离线爆破最有效的单一变量，
     *                  而强制"大小写+符号"的复杂组合会把人推向 {@code Passw0rd!} 这类可预测模式
     *                  （NIST SP 800-63B 已明确不推荐强制组合）
     * @param maxLength 最长长度。必须在哈希前就限制：BCrypt 只取前 72 字节，
     *                  超长输入既无效又会成为 CPU 消耗攻击的载体
     */
    public record Password(int minLength, int maxLength) {
    }

    /**
     * 登录失败风控。
     *
     * @param maxFailures  同一账号连续失败多少次后锁定
     * @param lockDuration 锁定时长。刻意不用"永久锁定"：那会让攻击者仅凭反复输错
     *                     就能把任意用户永久踢出系统（一种低成本的拒绝服务）
     */
    public record Login(int maxFailures, Duration lockDuration) {
    }

    /**
     * 认证类接口限流。当前为**单实例内存实现**（Phase 02 不引入 Redis），
     * 因此多实例部署时限流是"每实例"而非全局 —— 这一点在 Phase 09 引入 Redis 后修正。
     *
     * @param login    登录接口（按来源 IP）
     * @param register 注册接口（按来源 IP）
     * @param refresh  刷新接口（按来源 IP）
     */
    public record RateLimit(Rule login, Rule register, Rule refresh) {

        /**
         * 单条限流规则：{@code window} 时间内最多 {@code capacity} 次。
         *
         * @param capacity 窗口内允许的请求数
         * @param window   窗口长度
         */
        public record Rule(int capacity, Duration window) {
        }
    }
}
