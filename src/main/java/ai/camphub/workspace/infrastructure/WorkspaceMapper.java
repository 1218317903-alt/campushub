package ai.camphub.workspace.infrastructure;

import ai.camphub.workspace.domain.ScopedTable;
import ai.camphub.workspace.domain.Unscoped;
import ai.camphub.workspace.domain.Workspace;
import ai.camphub.workspace.domain.WorkspaceDraft;
import ai.camphub.workspace.domain.WorkspaceVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code workspace} 表访问接口。SQL 定义在 {@code resources/mapper/workspace/WorkspaceMapper.xml}。
 *
 * <h2>这个接口是第三层防线的样板</h2>
 * 每个方法都要回答"这条 SQL 是否必须被限制在调用者被授权的空间内"：
 * 是则标注 {@link ScopedTable}，否则标注 {@link Unscoped} 并写明理由。
 * 不回答会让 {@code WorkspaceScopeCoverageTest} 失败 —— 这是刻意的，
 * 因为"忘了标注"和"不需要标注"在代码里长得一样，只有强制二选一才能区分。
 *
 * <h2>为什么按 public_id / id 解析空间是无限制查询</h2>
 * 这两个方法是<b>鉴权的输入</b>，而不是鉴权的输出：第二层必须先把 public_id
 * 解析成一行空间，才能判断"调用者是谁、能不能做这件事"。
 * 若在这里就按"已加入的空间"过滤，会得到一个看起来更安全、实际上更差的结果：
 * "空间不存在"与"没有权限"会合并成同一个空值，于是<b>无法区分 404 与 403</b>，
 * 而被邀请人在兑换邀请码的那一刻必然处于"还不是成员"的状态 —— 那条路径会直接失效。
 * 安全性由紧随其后的第二层判定保证，所有调用点都必须经过它。
 */
public interface WorkspaceMapper {

    /**
     * 插入空间。
     *
     * @param workspace 待插入的空间
     * @return 影响行数
     */
    int insert(@Param("workspace") WorkspaceDraft workspace);

    /**
     * 按对外标识查询空间。
     *
     * @param publicId 对外标识
     * @return 存在且未被删除时返回
     */
    @Unscoped(reason = "鉴权的输入：必须先把 public_id 解析成空间才能做第二层判定，"
            + "否则无法区分 404（空间不存在）与 403（无权限）。安全性由调用方随后的 "
            + "AuthorizationService.assertCan 保证，所有调用点均经过它。")
    Optional<Workspace> findByPublicId(@Param("publicId") String publicId);

    /**
     * 按自增主键查询空间。
     *
     * @param id 自增主键
     * @return 存在且未被删除时返回
     */
    @Unscoped(reason = "同上：供 AuthorizationService 解析空间的拥有者，属于鉴权输入。")
    Optional<Workspace> findById(@Param("id") long id);

    /**
     * 查询某个用户拥有的空间主键。
     *
     * <p>它是第三层防线授权集合的一半输入（另一半是成员表）。
     * 这个集合本身要靠它算出来，所以它不能再去读这个集合。
     *
     * @param userId 用户自增主键
     * @return 空间主键列表
     */
    @Unscoped(reason = "授权集合自身的输入。若用授权集合限制它，就会形成自指（空集永远算不出非空集）。")
    List<Long> findOwnedIds(@Param("userId") long userId);

    /**
     * 分页查询某个用户可见的空间（自己拥有的 + 被邀请加入的）。
     *
     * <p>这是"我的空间"列表。即使调用方已经按 userId 过滤过，
     * 这里仍然标注 {@link ScopedTable}：集合查询正是第三层防线要覆盖的形状 ——
     * 一旦将来有人把筛选条件改错（例如把 {@code user_id = ?} 写成 {@code user_id != ?}），
     * 少一道兜底就是整页别人的空间。多这一道条件的代价是一次主键 IN 判断。
     *
     * <p>必须分页。一个"我的空间"数量在正常使用下很小，但接口一旦不设上限，
     * 它的代价就由数据决定而不是由代码决定 —— 与 Phase 01-03 复查里修掉的
     * 深分页问题属于同一类：不设上限的读接口迟早会被一个意外构造的请求打满。
     *
     * @param userId 用户自增主键
     * @param limit  页大小
     * @param offset 偏移，用 {@code long} 避免极大页码溢出
     * @return 空间列表，按创建时间倒序
     */
    @ScopedTable(column = "w.id")
    List<Workspace> findVisibleTo(@Param("userId") long userId,
                                  @Param("limit") int limit,
                                  @Param("offset") long offset);

    /**
     * 统计某个用户可见的空间数。
     *
     * @param userId 用户自增主键
     * @return 条数
     */
    @ScopedTable(column = "w.id")
    long countVisibleTo(@Param("userId") long userId);

    /**
     * 更新空间设置。
     *
     * @param id         自增主键
     * @param name       新名称
     * @param description 新描述，可为 null
     * @param visibility 新可见性
     * @return 影响行数
     */
    @ScopedTable(column = "id")
    int updateSettings(@Param("id") long id,
                       @Param("name") String name,
                       @Param("description") String description,
                       @Param("visibility") WorkspaceVisibility visibility);

    /**
     * 软删除空间。
     *
     * @param id  自增主键
     * @param now 当前时间
     * @return 影响行数
     */
    @ScopedTable(column = "id")
    int softDelete(@Param("id") long id, @Param("now") Instant now);
}
