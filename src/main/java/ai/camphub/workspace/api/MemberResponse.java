package ai.camphub.workspace.api;

import ai.camphub.workspace.domain.WorkspaceMemberView;
import java.time.Instant;

/**
 * 成员列表中的一行。
 *
 * <h2>为什么 {@code role} 的取值里会有 OWNER</h2>
 * 成员列表必须包含拥有者 —— 否则界面上会出现"这个空间没有管理员"的错觉。
 * 而拥有者在库里不是一条成员行（它是 {@code workspace.owner_id}），
 * 因此它的角色只能由 {@code OWNER} 表达。这一点与
 * {@code WorkspaceMemberView} 的取舍一致，并由此继承了"拥有者排在第一行"的约定。
 *
 * <h2>为什么用 {@code userPublicId} 而不是 {@code publicId}</h2>
 * 这是成员操作（改角色、移除）要用的定位参数，字段名带上 {@code user} 前缀，
 * 让"这是用户标识而非空间标识"在请求 URL 的构造处不易被弄错 ——
 * 两者都是 22 位随机串，填错不会报错，只会操作到另一个对象上。
 *
 * @param userPublicId 成员的用户对外标识
 * @param nickname     展示名
 * @param avatarUrl    头像地址，可为 null
 * @param role         在该空间内的身份
 * @param joinedAt     加入时间；拥有者取空间的创建时间
 */
public record MemberResponse(
        String userPublicId,
        String nickname,
        String avatarUrl,
        String role,
        Instant joinedAt
) {

    /**
     * 从应用层视图构造。
     *
     * @param view 成员列表项
     * @return 响应
     */
    public static MemberResponse from(WorkspaceMemberView view) {
        return new MemberResponse(
                view.userPublicId(),
                view.nickname(),
                view.avatarUrl(),
                view.role().name(),
                view.joinedAt());
    }
}
