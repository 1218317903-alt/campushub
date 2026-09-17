package ai.camphub.identity.domain;

import java.time.Instant;

/**
 * 刷新令牌记录。
 *
 * <p><b>注意此处没有"令牌原文"字段，只有哈希。</b>这不是疏忽：刷新令牌是有效期 30 天、
 * 可直接换取访问令牌的长期凭据，等价于密码。数据库一旦被读走，存哈希与存原文的区别
 * 就是"攻击者能否直接冒用每个在线用户"的区别。
 *
 * <p>{@code rotatedFrom} 把令牌串成链：每一次刷新都签发新令牌、作废旧令牌，
 * 新令牌指向它的前驱。这条链让"旧令牌被重复使用"成为可判定事件 ——
 * 正常客户端永远只持有链尾，链中间节点再次出现即意味着令牌已泄露。
 *
 * @param id         主键
 * @param userId     所属用户
 * @param tokenHash  SHA-256 十六进制哈希
 * @param device     客户端上报的设备标识。仅用于展示（"查看并单独登出设备"），
 *                   <b>不作为安全判据</b> —— 它由客户端提供，可以随意伪造
 * @param expiresAt  过期时间
 * @param revokedAt  失效时间；非空即已失效（登出 / 轮换 / 重放处置）
 * @param rotatedFrom 由哪条令牌轮换而来；首次登录签发时为 null
 * @param lastUsedAt 最近一次被用于刷新的时间，可为空
 * @param createdAt  签发时间
 */
public record RefreshTokenRecord(
        long id,
        long userId,
        String tokenHash,
        String device,
        Instant expiresAt,
        Instant revokedAt,
        Long rotatedFrom,
        Instant lastUsedAt,
        Instant createdAt
) {

    /**
     * 判断在给定时刻该令牌是否可用于刷新。
     *
     * @param now 当前时刻
     * @return 未被撤销且未过期时返回 true
     */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
