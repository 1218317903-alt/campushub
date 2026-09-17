package ai.camphub.identity.infrastructure;

import ai.camphub.identity.domain.UserCredential;
import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code user_credential} 表访问接口。
 */
public interface UserCredentialMapper {

    /**
     * 插入凭据。
     *
     * @param credential 凭据
     * @return 影响行数
     */
    int insert(@Param("credential") UserCredential credential);

    /**
     * 按用户 ID 查询凭据。
     *
     * @param userId 用户 ID
     * @return 存在时返回凭据
     */
    Optional<UserCredential> findByUserId(@Param("userId") long userId);

    /**
     * 登录成功的记账：清零失败计数、解除临时锁定、记录登录时间。
     *
     * @param userId      用户 ID
     * @param lastLoginAt 登录时间
     * @return 影响行数
     */
    int updateOnLoginSuccess(@Param("userId") long userId, @Param("lastLoginAt") Instant lastLoginAt);

    /**
     * 登录失败的记账：失败计数 +1，达到阈值时写入锁定到期时间。
     *
     * <p><b>阈值判定刻意放在 SQL 里</b>，整条语句一次完成：
     * 若改成"先查计数、再判断、再写回"，两个并发登录失败会各自读到同一个旧计数，
     * 各自算出"还没到 5 次"，于是攻击者可以靠并发把锁定机制绕过去 ——
     * 而锁定机制防的恰恰就是"高并发尝试"。
     *
     * @param userId        用户 ID
     * @param maxFailures   触发锁定的失败次数阈值
     * @param lockedUntil   达到阈值时写入的解锁时间
     * @return 影响行数
     */
    int updateOnLoginFailure(@Param("userId") long userId,
                             @Param("maxFailures") int maxFailures,
                             @Param("lockedUntil") Instant lockedUntil);

    /**
     * 更新密码哈希。
     *
     * @param userId            用户 ID
     * @param passwordHash      新哈希
     * @param passwordUpdatedAt 修改时间
     * @return 影响行数
     */
    int updatePassword(@Param("userId") long userId,
                       @Param("passwordHash") String passwordHash,
                       @Param("passwordUpdatedAt") Instant passwordUpdatedAt);
}
