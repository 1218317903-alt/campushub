package ai.camphub.identity.infrastructure;

import ai.camphub.identity.domain.User;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code user} 表访问接口。
 *
 * <h2>为什么插入方法不返回自增主键</h2>
 * MyBatis 的 {@code useGeneratedKeys} 需要把生成的主键写回<b>可变</b>的参数对象，
 * 而本项目的领域模型是不可变 record —— 为了拿一个 id 而专门造一个可变 DTO，
 * 换来的是"两套几乎一样的用户模型"这个长期维护负担。
 * 因此注册流程的做法是：插入后按 {@code public_id} 再查一次（{@code public_id} 由服务端生成，
 * 本来就已知）。代价是一次主键/唯一索引查询，而注册是低频操作。
 */
public interface UserMapper {

    /**
     * 插入用户。
     *
     * @param user 用户（{@code id} 字段被忽略，由数据库生成）
     * @return 影响行数
     */
    int insert(@Param("user") User user);

    /**
     * 按自增主键查询。
     *
     * @param id 主键
     * @return 存在时返回用户
     */
    Optional<User> findById(@Param("id") long id);

    /**
     * 按对外标识查询。
     *
     * @param publicId 对外标识
     * @return 存在时返回用户
     */
    Optional<User> findByPublicId(@Param("publicId") String publicId);

    /**
     * 按登录名查询。
     *
     * <p>比较不区分大小写（由列的排序规则 {@code utf8mb4_0900_ai_ci} 保证），
     * 与唯一键的行为保持一致 —— 否则会出现"唯一键认为重复、查询却查不到"的矛盾。
     *
     * @param username 登录名
     * @return 存在时返回用户
     */
    Optional<User> findByUsername(@Param("username") String username);

    /**
     * 按邮箱查询，同样不区分大小写。
     *
     * @param email 邮箱
     * @return 存在时返回用户
     */
    Optional<User> findByEmail(@Param("email") String email);

    /**
     * 统计同名用户数，用于注册前的友好提示。
     *
     * @param username 登录名
     * @return 记录数
     */
    int countByUsername(@Param("username") String username);

    /**
     * 统计同邮箱用户数。
     *
     * @param email 邮箱
     * @return 记录数
     */
    int countByEmail(@Param("email") String email);

    /**
     * 查询用户的角色码集合。
     *
     * @param userId 用户 ID
     * @return 角色码，如 {@code ["USER"]}
     */
    List<String> selectRoleCodesByUserId(@Param("userId") long userId);

    /**
     * 查询用户经角色继承得到的权限码集合。
     *
     * @param userId 用户 ID
     * @return 去重后的权限码
     */
    List<String> selectPermissionCodesByUserId(@Param("userId") long userId);

    /**
     * 递增令牌世代号，使该用户此前签发的全部访问令牌立即失效。
     *
     * <p>在 SQL 里做 {@code +1} 而不是"读出来加一再写回"：后者在并发下会丢更新 ——
     * 两个并发请求各自读到 5、各自写回 6，结果是只前进了 1 次。
     * 而本操作的语义恰恰是"必须生效"，丢了就是登出没登出干净。
     *
     * @param userId 用户 ID
     * @return 影响行数
     */
    int incrementTokenVersion(@Param("userId") long userId);

    /**
     * 更新个人资料。
     *
     * @param userId    用户 ID
     * @param nickname  昵称
     * @param avatarUrl 头像地址，可为 null（表示清空）
     * @param bio       简介，可为 null
     * @return 影响行数
     */
    int updateProfile(@Param("userId") long userId,
                      @Param("nickname") String nickname,
                      @Param("avatarUrl") String avatarUrl,
                      @Param("bio") String bio);

    /**
     * 为用户授予角色。角色按 {@code code} 解析，不依赖写死的自增 id。
     *
     * @param userId   用户 ID
     * @param roleCode 角色码
     * @return 影响行数；角色不存在时为 0
     */
    int assignRoleByCode(@Param("userId") long userId, @Param("roleCode") String roleCode);
}
