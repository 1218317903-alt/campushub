package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 待插入的邀请。字段与 INSERT 的列一一对应。
 *
 * <p>没有 {@code status}：新邀请永远是 {@code PENDING}（列的默认值）。
 * 提供一个可以指定初始状态的插入参数，等于给调用方一个"造出一条已兑换邀请"的入口，
 * 而那条邀请对应的成员关系并不存在。
 *
 * @param code        邀请码，由 {@code RandomValues.publicId()} 生成
 * @param workspaceId 所属空间自增主键
 * @param inviterId   发起人自增主键
 * @param inviteeId   被邀请人自增主键
 * @param role        兑换后获得的角色
 * @param expiresAt   到期时间
 */
public record InviteDraft(
        String code,
        long workspaceId,
        long inviterId,
        long inviteeId,
        WorkspaceMemberRole role,
        Instant expiresAt
) {
}
