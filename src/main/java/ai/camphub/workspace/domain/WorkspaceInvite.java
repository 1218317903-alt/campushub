package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 定向邀请。
 *
 * @param id          自增主键，模块内部使用
 * @param code        邀请码；对外只暴露它，不暴露 id
 * @param workspaceId 所属空间自增主键
 * @param inviterId   发起邀请的成员自增主键
 * @param inviteeId   被邀请人自增主键。<b>兑换时用它做归属判定</b>：非本人一律 404
 * @param role        兑换后获得的角色
 * @param status      状态
 * @param expiresAt   到期时间
 * @param acceptedAt  兑换时间，未兑换时为 null
 * @param createdAt   创建时间
 */
public record WorkspaceInvite(
        long id,
        String code,
        long workspaceId,
        long inviterId,
        long inviteeId,
        WorkspaceMemberRole role,
        InviteStatus status,
        Instant expiresAt,
        Instant acceptedAt,
        Instant createdAt
) {

    /**
     * 判断这条邀请此刻能否被指定用户兑换。
     *
     * <p>三个条件必须同时成立：状态为 PENDING、未过期、且兑换人就是被邀请人。
     * 把"是不是被邀请人"也放进这个方法，是为了让调用点无法只检查其中两项 ——
     * 漏掉任一项的表现分别是"过期邀请仍可用""重复兑换"与"拿到码的人就能进"，
     * 三者都是越权。
     *
     * @param userId 尝试兑换的用户自增主键
     * @param now    当前时间
     * @return 是否可兑换
     */
    public boolean isRedeemableBy(long userId, Instant now) {
        return inviteeId == userId && status.isRedeemableAt(expiresAt, now);
    }
}
