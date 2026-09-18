package ai.camphub.workspace.app;

import ai.camphub.identity.domain.UserBrief;
import ai.camphub.workspace.domain.WorkspaceInvite;

/**
 * 待处理邀请 + 被邀请人的展示信息。
 *
 * <h2>为什么需要它</h2>
 * "我邀请了谁"要显示一个人，而 {@link WorkspaceInvite} 里只有 {@code inviteeId}
 * （内部自增主键）。把它换成昵称需要一次批量查询，这一步属于应用层
 * （它知道"要展示什么"），不该由 Mapper 承担，也不该由接口层各自去查。
 *
 * <p>{@code invitee} 可能为 null（账号已被软删除）。刻意不用空壳对象占位：
 * 一个昵称为空串的人与"这个人不存在"在界面上无法区分。
 *
 * @param invite  邀请本体（含邀请码）
 * @param invitee 被邀请人的展示信息，可为 null
 */
public record InviteView(WorkspaceInvite invite, UserBrief invitee) {
}
