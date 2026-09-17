package ai.camphub.identity.infrastructure;

import ai.camphub.identity.domain.RefreshTokenRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code refresh_token} 表访问接口。
 *
 * <p>注意所有方法都只按 {@code token_hash} 检索，从不接收令牌原文 ——
 * 库中本来也不存原文。这样即便某个查询被误加进日志，泄漏的也只是哈希。
 */
public interface RefreshTokenMapper {

    /**
     * 插入刷新令牌。
     *
     * @param record 令牌记录（{@code id} 被忽略）
     * @return 影响行数
     */
    int insert(@Param("record") RefreshTokenRecord record);

    /**
     * 按令牌哈希查询。
     *
     * @param tokenHash SHA-256 十六进制哈希
     * @return 存在时返回记录
     */
    Optional<RefreshTokenRecord> findByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * 查询某用户当前仍有效的会话（未撤销且未过期）。
     *
     * @param userId 用户 ID
     * @param now    当前时间
     * @return 有效令牌列表，按签发时间倒序
     */
    List<RefreshTokenRecord> findActiveByUserId(@Param("userId") long userId, @Param("now") Instant now);

    /**
     * 按主键查询。
     *
     * @param id 主键
     * @return 存在时返回记录
     */
    Optional<RefreshTokenRecord> findById(@Param("id") long id);

    /**
     * 查询由某个令牌轮换而来的后继令牌。
     *
     * <p>用途是区分两种"旧令牌被再次使用"：
     * <ul>
     *   <li><b>同一个客户端在极短时间内重复提交</b>（多标签页同时刷新）——
     *       后继令牌刚刚才签发，属于正常并发，不应判定为泄露；</li>
     *   <li><b>攻击者持有早已被替换掉的旧令牌</b> —— 后继令牌或是早已撤销，
     *       或是签发时间久远，属于确凿的泄露信号。</li>
     * </ul>
     * 这两种情形在数据上的区别就是"后继令牌是否存在、是否仍有效、多久之前签发"。
     *
     * @param rotatedFrom 前驱令牌 ID
     * @return 存在时返回最近一条后继令牌
     */
    Optional<RefreshTokenRecord> findLatestByRotatedFrom(@Param("rotatedFrom") long rotatedFrom);

    /**
     * 撤销单个令牌。
     *
     * <p>条件里带 {@code revoked_at IS NULL}：重复撤销不会覆盖首次撤销时间。
     * 首次撤销时间本身是有价值的线索（它记录了泄露被发现的时刻）。
     *
     * @param id        令牌 ID
     * @param revokedAt 撤销时间
     * @return 影响行数；已撤销时返回 0
     */
    int revoke(@Param("id") long id, @Param("revokedAt") Instant revokedAt);

    /**
     * 撤销某用户全部仍有效的令牌（登出全部设备 / 检测到令牌重放时使用）。
     *
     * @param userId    用户 ID
     * @param revokedAt 撤销时间
     * @return 影响行数
     */
    int revokeAllActiveByUserId(@Param("userId") long userId, @Param("revokedAt") Instant revokedAt);

    /**
     * 记录令牌最近一次被使用的时间。
     *
     * @param id         令牌 ID
     * @param lastUsedAt 使用时间
     * @return 影响行数
     */
    int updateLastUsed(@Param("id") long id, @Param("lastUsedAt") Instant lastUsedAt);
}
