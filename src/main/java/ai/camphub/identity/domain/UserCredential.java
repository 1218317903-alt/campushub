package ai.camphub.identity.domain;

import java.time.Instant;

/**
 * 用户凭据与登录风控状态。
 *
 * <p>与 {@link User} 分开建模，映射到 {@code user_credential} 表。分开的理由是
 * <b>敏感度与访问模式不同</b>：账号信息（昵称、头像）几乎每个请求都要用，
 * 而密码哈希只在注册、登录、改密三个场景被读写。放在同一张表里，
 * 任何一次"查用户"的查询都会把哈希捞进内存，风险面被无谓放大。
 *
 * @param userId           所属用户
 * @param passwordHash     BCrypt 哈希（含算法标识与 salt）
 * @param failedLoginCount 连续登录失败次数，登录成功后清零
 * @param lockedUntil      锁定到期时间；为空表示未锁定。用"到期时间"而不是布尔位，
 *                         是为了不必再写一个定时任务去解锁
 * @param lastLoginAt      最近一次登录成功时间，可为空
 * @param passwordUpdatedAt 密码最后修改时间。用于将来做"密码多久没换"的提醒，
 *                         也是排查"改密后旧令牌是否真的失效"的依据
 */
public record UserCredential(
        long userId,
        String passwordHash,
        int failedLoginCount,
        Instant lockedUntil,
        Instant lastLoginAt,
        Instant passwordUpdatedAt
) {

    /**
     * 判断在给定时刻是否处于锁定期。
     *
     * @param now 当前时刻
     * @return 未到解锁时间时返回 true
     */
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
