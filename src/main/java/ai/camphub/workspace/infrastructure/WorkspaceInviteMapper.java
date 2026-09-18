package ai.camphub.workspace.infrastructure;

import ai.camphub.workspace.domain.InviteDraft;
import ai.camphub.workspace.domain.ScopedTable;
import ai.camphub.workspace.domain.Unscoped;
import ai.camphub.workspace.domain.WorkspaceInvite;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code workspace_invite} 表访问接口。SQL 定义在
 * {@code resources/mapper/workspace/WorkspaceInviteMapper.xml}。
 *
 * <h2>兑换路径上有一条必须是"不受自动过滤"的查询</h2>
 * {@link #findRedeemable} 与 {@link #markAccepted} 都不接受自动空间过滤，
 * 理由不是"懒得加"，而是一个硬事实：<b>兑换邀请码的人在被兑换成功之前还不是空间成员</b>。
 * 一旦按"调用者已加入的空间"去限制这两条语句，
 * 邀请码将永远无法兑换 —— 而这个 bug 只在"邀请一个尚未加入的人"时出现，
 * 用已有成员试邀请的日常测试发现不了。
 *
 * <p>它们的安全性由另一条<b>更强</b>的条件保证：语句里带 {@code invitee_id = 当前用户}。
 * "这张邀请是发给我的"比"我在这个空间里"更强 —— 它把范围从"空间级"收到了"这一条记录"。
 * 这也解释了为什么兑换必须用 code + 当前用户一起去查，而不能只按 code 查、
 * 拿到之后再在 Java 里比对 invitee：只在 Java 里比对时，
 * 一次日志打印或一次异常信息就可能把"这个码存在但不是给你的"泄漏出去。
 */
public interface WorkspaceInviteMapper {

    /**
     * 插入邀请。
     *
     * @param invite 待插入的邀请
     * @return 影响行数
     */
    int insert(@Param("invite") InviteDraft invite);

    /**
     * 按邀请码查询：仅当这条邀请就是发给当前用户时才返回。
     *
     * @param code   邀请码
     * @param userId 当前用户自增主键
     * @return 命中时返回；码不存在、或不是发给该用户的，都返回空
     */
    @Unscoped(reason = "兑换者此刻还不是空间成员，用成员身份限定会让邀请码永远无法兑换。"
            + "安全性由更强的条件 invitee_id = 当前用户 保证（范围从空间级收到单条记录）。")
    Optional<WorkspaceInvite> findRedeemable(@Param("code") String code, @Param("userId") long userId);

    /**
     * 把邀请标记为已兑换。
     *
     * <p>{@code AND status = 'PENDING'} 是幂等与并发安全的全部依据：
     * 两个请求同时兑换同一个码，只有一个能把它从 PENDING 改走，另一个影响 0 行。
     * 判定"谁赢了"看影响行数，而不是先查后改 —— 后者在并发下必然出现两条成员关系。
     *
     * @param id  邀请自增主键
     * @param now 兑换时间
     * @return 影响行数；1 表示本次调用成功兑换
     */
    @Unscoped(reason = "同上：兑换者尚未成为成员。乐观并发控制由 status = 'PENDING' 条件承担。")
    int markAccepted(@Param("id") long id, @Param("now") Instant now);

    /**
     * 列出某个空间内待处理的邀请。
     *
     * <p>过期判断由调用方传入的 {@code now} 完成，而不是让 SQL 写
     * {@code CURRENT_TIMESTAMP}：列表里显示的"还有多久过期"必须与
     * 判定"能不能兑换"依据同一个时钟，否则会出现"列表里显示有效、点下去说已过期"。
     *
     * <p><b>必须带上限。</b>待处理邀请的条数没有天然上界 ——
     * 一个管理员可以反复邀请不同的人，而每条邀请在到期前都会留在 PENDING 里。
     * 与"我的空间"列表同一条纪律：接口一旦不设上限，它的代价就由数据决定，
     * 而不是由代码决定。
     *
     * @param workspaceId 空间自增主键
     * @param now         当前时间
     * @param limit       最多返回条数
     * @return 待处理邀请，按创建时间倒序
     */
    @ScopedTable
    List<WorkspaceInvite> findPending(@Param("workspaceId") long workspaceId,
                                      @Param("now") Instant now,
                                      @Param("limit") int limit);

    /**
     * 撤销一条待处理邀请。
     *
     * <h2>为什么条件里必须有 {@code workspace_id}</h2>
     * 只按 {@code code} 撤销会让"我在这两个空间里都有角色"变成一次越权：
     * 撤销动作要求的是<b>路径上那个空间</b>的 {@code workspace:member:invite} 能力，
     * 而语句若不带空间条件，同一个人的另一个空间（甚至只是普通成员的那个）里的邀请
     * 也会被一并改掉。
     *
     * <p>第三层防线会追加 {@code workspace_id IN (我参与的全部空间)} ——
     * 那是一道兜底，它的范围是"我的所有空间"，比"这一个空间"宽得多。
     * 这里写死的 {@code workspace_id = ?} 才是把权限收窄到路径上的那一个。
     * 两道条件的范围不同，因此不是重复。
     *
     * <p>{@code AND status = 'PENDING'} 让"撤销已兑换的邀请"变成影响 0 行，
     * 由调用方翻成 404：已经生效的成员关系不能被一次撤销抹掉。
     *
     * @param workspaceId 空间自增主键
     * @param code        邀请码
     * @return 影响行数
     */
    @ScopedTable
    int revoke(@Param("workspaceId") long workspaceId, @Param("code") String code);
}
