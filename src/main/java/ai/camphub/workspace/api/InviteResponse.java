package ai.camphub.workspace.api;

import ai.camphub.workspace.app.InviteView;
import ai.camphub.workspace.domain.WorkspaceInvite;
import java.time.Instant;

/**
 * 待处理邀请的对外表示。
 *
 * <h2>它刻意不暴露的东西</h2>
 * {@code WorkspaceInvite} 里有 {@code id} / {@code inviterId} / {@code inviteeId}
 * 三个自增主键，一个都不出现在这里：
 * <ul>
 *   <li>{@code id} 与 {@code inviteeId} 是可遍历的内部标识，
 *       暴露它们等于给了一个"用自增数字猜用户"的入口；</li>
 *   <li>{@code inviterId} 对当前调用者没有信息量 —— 能读到这张列表的人
 *       本身就是空间的拥有者或管理员，而"邀请的归属"在服务端判定里已经用过。</li>
 * </ul>
 *
 * <h2>邀请码在这里出现是刻意的</h2>
 * 它是本系统中邀请码唯一一次出现在响应里的地方（另一次是刚创建邀请的响应）。
 * 邀请人需要把码转达给被邀请人，而系统不代发消息（通知属于 Phase 10）。
 * 能被读到这张列表的人已经具备 {@code workspace:member:invite} 能力，
 * 也就是说他本来就能自己发新邀请 —— 因此这里不构成额外泄漏。
 *
 * @param code      邀请码
 * @param invitee   被邀请人的展示信息，可为 null（账号已注销）
 * @param role      兑换后获得的角色
 * @param status    状态，取值 {@code PENDING | ACCEPTED | REVOKED}；
 *                  本接口只返回 PENDING
 * @param expiresAt 到期时间
 * @param createdAt 创建时间
 */
public record InviteResponse(
        String code,
        ContributorResponse invitee,
        String role,
        String status,
        Instant expiresAt,
        Instant createdAt
) {

    /**
     * 从应用层视图构造。
     *
     * @param view 邀请与被邀请人展示信息
     * @return 响应
     */
    public static InviteResponse from(InviteView view) {
        WorkspaceInvite invite = view.invite();
        return new InviteResponse(
                invite.code(),
                ContributorResponse.from(view.invitee()),
                invite.role().name(),
                invite.status().name(),
                invite.expiresAt(),
                invite.createdAt());
    }
}
