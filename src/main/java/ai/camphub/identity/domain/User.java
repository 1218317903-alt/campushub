package ai.camphub.identity.domain;

import java.time.Instant;

/**
 * 用户账号（领域模型）。
 *
 * <p>这是一个不可变记录，与数据库行一一对应。<b>刻意不在这里放 password_hash</b> ——
 * 凭据在 {@link UserCredential} 里单独建模，因为它们的读取频率与敏感度完全不同：
 * 每次鉴权都要读账号信息，但几乎永远不需要读密码哈希。
 *
 * <p>领域模型保持纯净（不依赖任何 Spring 类型，由 ArchUnit 在构建期断言），
 * 因此可以在不启动容器的情况下被直接构造与测试。
 *
 * @param id           自增主键，仅内部使用
 * @param publicId     对外暴露的随机 ID。所有接口与日志中使用它而不是 {@code id}，
 *                     使自增主键不泄漏"系统里一共有多少用户"这类信息，
 *                     也让"换个数字试试"的遍历攻击失去意义
 * @param username     登录名
 * @param email        邮箱
 * @param nickname     展示名
 * @param avatarUrl    头像地址，可为空
 * @param bio          个人简介，可为空
 * @param status       账号状态
 * @param tokenVersion 令牌世代号。改密、登出全部设备、踢下线时递增，
 *                     使此前签发的所有访问令牌立即失效
 * @param createdAt    创建时间
 * @param updatedAt    最后更新时间
 */
public record User(
        long id,
        String publicId,
        String username,
        String email,
        String nickname,
        String avatarUrl,
        String bio,
        UserStatus status,
        int tokenVersion,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 账号当前是否允许通过认证。
     *
     * @return 仅 {@link UserStatus#ACTIVE} 返回 true
     */
    public boolean isActive() {
        return status.isActive();
    }
}
