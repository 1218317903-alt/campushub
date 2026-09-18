package ai.camphub.workspace.app;

import ai.camphub.workspace.domain.Workspace;
import ai.camphub.workspace.domain.WorkspaceAction;
import ai.camphub.workspace.domain.WorkspaceRoleInContext;

/**
 * 一次"解析 + 判定"的完整结果：空间本身，以及调用者在其中的身份。
 *
 * <h2>为什么把两者绑在一起返回</h2>
 * 判定"能不能做这个动作"的凭据就是身份。原先 {@code requireWorkspaceFor} 只返回空间，
 * 于是任何还需要知道身份的地方（例如"这条笔记的删除按钮要不要显示"）
 * 都得再调一次 {@code roleIn} —— 那是两次索引查询，而它的结果与刚才判定时用的是同一个。
 *
 * <p>这与 {@code AuthorizationService} 里"为什么不做 PermissionEvaluator"是同一条思路：
 * <b>同一套规则只算一次</b>。算两次的代价不只是性能 ——
 * 它给了两处结果不一致的机会（一个在事务内、一个在事务外，或落在不同的读视图上），
 * 而那种不一致只会在极少数时序下出现。
 *
 * @param workspace 空间
 * @param role      调用者在该空间内的身份；{@link WorkspaceRoleInContext#NONE} 不可能出现
 *                  —— 调用方只会在判定通过之后才构造它
 */
public record WorkspaceAccess(Workspace workspace, WorkspaceRoleInContext role) {

    /**
     * 判断调用者是否可以删除一条指定的资源。
     *
     * <p>它把 {@code DELETE_NOTE} / {@code DELETE_DOCUMENT} 的归属维度判定收在一处，
     * 让"界面要不要显示删除按钮"与"服务端会不会真的允许"用的是同一张矩阵 ——
     * 而不是在接口层再写一遍 {@code role == OWNER || role == ADMIN || mine}。
     * 两处各写一遍的话，前端会显示一个点下去报 404 的按钮，
     * 而那个 404 在测试里通常被当成"权限正常"放过去。
     *
     * @param action      删除动作
     * @param ownedBySelf 目标资源是否由调用者创建/上传
     * @return 是否允许
     */
    public boolean canDelete(WorkspaceAction action, boolean ownedBySelf) {
        return action.allows(role, ownedBySelf);
    }
}
