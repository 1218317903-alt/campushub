package ai.camphub.workspace.app;

import ai.camphub.workspace.domain.Workspace;
import ai.camphub.workspace.domain.WorkspaceRoleInContext;

/**
 * 空间 + "我在其中的身份"。
 *
 * <h2>为什么需要把身份和空间放在一起返回</h2>
 * 界面上的每一个按钮都取决于调用者在这个空间里的身份：拥有者看到"空间设置"与"删除空间"，
 * 管理员看到"邀请成员"与"成员管理"，普通成员两样都看不到。
 * 若响应里只有空间本身，前端就必须再请求一次成员列表、在其中找到自己 ——
 * 那是一次额外的往返，而且它在界面上表现为"按钮先出现，半秒后才消失"。
 *
 * <p>把身份随空间一起返回，让"我能做什么"在一次请求里得到回答。
 * 它<b>不是</b>权限判定的替代：第二层防线仍然逐次判定，
 * 这里的字段只是让界面不必去猜。
 *
 * <h2>为什么带的是算出来的身份而不是落库的角色</h2>
 * 拥有者不是 {@code workspace_member} 里的一行（见 {@code V5__workspace.sql} 的偏离说明 3），
 * 因此它的身份只能由 {@link WorkspaceRoleInContext#OWNER} 表达。
 * 若这里用 {@code WorkspaceMemberRole}，拥有者会得到一个 {@code null} 字段，
 * 而"null 表示拥有者"这条约定不会有人记得。
 *
 * @param workspace 空间本身
 * @param myRole    调用者在该空间内的身份，不可能是 {@link WorkspaceRoleInContext#NONE}
 *                  —— 调用方只会在已经确认可见之后才构造它
 */
public record WorkspaceSummary(Workspace workspace, WorkspaceRoleInContext myRole) {
}
