package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 成员列表中的一行（读模型）。
 *
 * <h2>为什么角色类型是 {@link WorkspaceRoleInContext} 而不是 {@link WorkspaceMemberRole}</h2>
 * 成员列表必须包含拥有者 —— 否则界面上会出现"这个空间没有管理员"的错觉。
 * 而拥有者在库里不是一条成员行（它是 {@code workspace.owner_id}），
 * 因此它的角色只能由 {@link WorkspaceRoleInContext#OWNER} 表达。
 * 让这个 record 直接用带 OWNER 的枚举，就不需要在响应组装时把两种类型缝在一起。
 *
 * <p>注意这里<b>不带用户自增主键</b>：成员列表是要给前端看的，
 * 而自增主键是可遍历的。成员间的操作（改角色、移除）走用户的 {@code publicId}。
 *
 * @param userPublicId 用户对外标识
 * @param nickname     展示名
 * @param avatarUrl    头像地址，可为 null
 * @param role         在空间内的身份
 * @param joinedAt     加入时间；拥有者取空间的创建时间
 */
public record WorkspaceMemberView(
        String userPublicId,
        String nickname,
        String avatarUrl,
        WorkspaceRoleInContext role,
        Instant joinedAt
) {
}
