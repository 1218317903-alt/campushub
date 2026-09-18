package ai.camphub.workspace.infrastructure;

import ai.camphub.workspace.domain.MembershipRole;
import ai.camphub.workspace.domain.ScopedTable;
import ai.camphub.workspace.domain.Unscoped;
import ai.camphub.workspace.domain.WorkspaceMember;
import ai.camphub.workspace.domain.WorkspaceMemberRole;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code workspace_member} 表访问接口。SQL 定义在
 * {@code resources/mapper/workspace/WorkspaceMemberMapper.xml}。
 *
 * <h2>这张表上有两条"不受自动过滤"的查询，理由是同一个</h2>
 * {@link #findRole} 与 {@link #findMemberWorkspaceIds} 都是<b>鉴权的输入</b>：
 * 前者回答"这个人在这个空间里是什么身份"，后者回答"这个人被授权哪些空间"。
 * 若用后者算出来的集合去限制前者，鉴权判定就会依赖它自己的结论 ——
 * 一个刚被移出空间的用户会因为"集合里没有这个空间"而查不到自己的成员行，
 * 于是被判成 NONE、返回 404。结果<b>恰好是对的</b>，但这是巧合而不是设计：
 * 它让"为什么返回 404"无法通过日志区分是"确实不是成员"还是"查不到成员行"。
 *
 * <p>把这两条显式标成 {@link Unscoped} 并写出理由，比让它们隐式地"按运气正确"要好。
 */
public interface WorkspaceMemberMapper {

    /**
     * 插入成员。
     *
     * @param workspaceId 空间自增主键
     * @param userId      用户自增主键
     * @param role        空间内角色
     * @return 影响行数；重复插入会被唯一键 (workspace_id, user_id) 挡下
     */
    int insert(@Param("workspaceId") long workspaceId,
               @Param("userId") long userId,
               @Param("role") WorkspaceMemberRole role);

    /**
     * 查询某个用户在某个空间内的角色。
     *
     * @param workspaceId 空间自增主键
     * @param userId      用户自增主键
     * @return 是成员时返回其角色；拥有者不在这张表里，因此拥有者会得到空
     */
    @Unscoped(reason = "鉴权输入：回答'这个人是不是这个空间的成员'。答案不能依赖于鉴权自身的结论。")
    Optional<WorkspaceMemberRole> findRole(@Param("workspaceId") long workspaceId,
                                           @Param("userId") long userId);

    /**
     * 查询某个用户通过成员关系被授权的空间主键。
     *
     * <p>第三层防线授权集合的一半输入（另一半是"他拥有的空间"）。
     * 刻意 JOIN 回 {@code workspace} 排除已软删除的空间：否则一个被删除的空间
     * 会永远留在授权集合里，让 {@code IN (...)} 列表无谓地变长。
     *
     * @param userId 用户自增主键
     * @return 空间主键列表
     */
    @Unscoped(reason = "授权集合自身的输入：若用授权集合限制它，就会形成自指（空集永远算不出非空集）。")
    List<Long> findMemberWorkspaceIds(@Param("userId") long userId);

    /**
     * 查询某个用户通过成员关系获得的角色，一次返回全部空间。
     *
     * <h2>为什么它必须是一次查询</h2>
     * "我的空间"列表要给每一行标出"我是这个空间的管理员还是普通成员"。
     * 逐行调用 {@link #findRole} 会让一个参与了二十个空间的用户打开列表页时
     * 产生二十次往返 —— 而 N+1 在功能测试里完全看不出来（数据量小、延迟低），
     * 只有在真实数据下才表现为"这个页面怎么这么慢"。
     *
     * <p>拥有者<b>不在</b>结果里：它在 {@code workspace.owner_id} 上，
     * 由调用方按 {@code ownerId == userId} 判定。因此调用方必须把"拥有"这一支
     * 与"成员"这一支合起来看，只看本方法的返回值会把拥有者显示成"没有角色"。
     *
     * <p>标注 {@link ScopedTable} 而不是 {@link Unscoped}：这条查询回答的是
     * "我参与的每个空间里我是什么角色"，它天然就落在我被授权的范围之内。
     * 追加的空间条件与 {@code user_id} 条件在语义上一致 ——
     * 保留它，是为了让"这一层的行为不依赖作者这次有没有写对筛选条件"。
     *
     * @param userId 用户自增主键
     * @return 空间主键与角色的对应关系；用户不是任何空间的成员时返回空列表
     */
    @ScopedTable
    List<MembershipRole> findRolesOf(@Param("userId") long userId);

    /**
     * 统计某个空间的成员数（不含拥有者）。
     *
     * <h2>为什么它必须是"不受自动过滤"的一条</h2>
     * 它唯一的用途是容量判断，而容量判断有一条调用路径来自<b>还没加入的人</b>：
     * 兑换邀请码时要在写成员行之前确认空间没满。若这条查询被自动追加
     * {@code workspace_id IN (调用者已授权的空间)}，那个还没加入的人算出来的集合里
     * 没有这个空间，于是条件退化成 {@code 1 = 0}、计数恒为 0 ——
     * <b>上限永远不生效，而且不报错</b>。
     *
     * <p>这不只是"少了一道防护"：它让"空间最多 50 人"这条约束在兑换路径上完全消失，
     * 而邀请路径（管理员发起，他在集合里）仍然生效。同一条规则在两条路径上行为不同，
     * 排查时几乎不可能想到是防线本身造成的。
     *
     * <p>安全性由调用方保证，而且是更强的条件：
     * 空间主键不是来自请求参数，而是从<b>邀请行本身</b>读出来的，
     * 而邀请行的定位条件是 {@code code = ? AND invitee_id = 当前用户}。
     * 换句话说，调用方只能统计"自己确实收到并正在兑换的那份邀请所指的空间"，
     * 不存在用别人的空间主键来探测成员数的路径。
     *
     * @param workspaceId 空间自增主键
     * @return 成员行数（不含拥有者，它不在本表中）
     */
    @Unscoped(reason = "容量判断的前置条件。兑换邀请码者此刻还不是成员，"
            + "用授权集合限定会让计数恒为 0、上限静默失效。"
            + "空间主键来自邀请行本身（定位条件含 invitee_id = 当前用户），不是请求参数。")
    long countMembers(@Param("workspaceId") long workspaceId);

    /**
     * 列出某个空间的成员。
     *
     * @param workspaceId 空间自增主键
     * @return 成员行，按加入时间正序
     */
    @ScopedTable
    List<WorkspaceMember> findMembers(@Param("workspaceId") long workspaceId);

    /**
     * 移除成员。
     *
     * @param workspaceId 空间自增主键
     * @param userId      用户自增主键
     * @return 影响行数
     */
    @ScopedTable
    int delete(@Param("workspaceId") long workspaceId, @Param("userId") long userId);

    /**
     * 修改成员在空间内的角色。
     *
     * @param workspaceId 空间自增主键
     * @param userId      用户自增主键
     * @param role        新角色
     * @return 影响行数
     */
    @ScopedTable
    int updateRole(@Param("workspaceId") long workspaceId,
                   @Param("userId") long userId,
                   @Param("role") WorkspaceMemberRole role);
}
