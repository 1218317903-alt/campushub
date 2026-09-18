package ai.camphub.workspace.api;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.domain.WorkspaceMemberRole;
import jakarta.validation.constraints.NotBlank;

/**
 * 发起邀请的请求体。
 *
 * <h2>为什么按登录名邀请，而不是按用户对外标识</h2>
 * 邀请的发起方式是"我知道对方叫什么"。成员管理（改角色、移除）走的是
 * {@code userPublicId}，因为那里的对象是<b>列表上已经看得见的那一行</b>；
 * 而邀请的对象此刻还不在任何列表里，操作者手上只有对方告诉他的登录名。
 * 两者都是"指向一个用户"，但来源不同，因此入口也不同（见 {@code UserDirectory}
 * 里那两个按名字 / 按对外标识查询的方法）。
 *
 * <h2>{@code role} 是字符串的理由</h2>
 * 同 {@link WorkspaceRequest}：取值域的默认值与合法性判定只有
 * {@code WorkspaceMemberRole.parse} 一处定义，不引入第二个由框架驱动的实现。
 *
 * @param username 被邀请人的登录名
 * @param role     兑换后获得的角色；留空时按 MEMBER
 */
public record InviteRequest(
        @NotBlank(message = "请填写被邀请人的用户名")
        String username,

        String role
) {

    /**
     * 解析角色。
     *
     * <h2>默认值为什么在这里，而不在 {@code WorkspaceMemberRole}</h2>
     * {@code role} 是<b>可选</b>字段：邀请时不写就是想按普通成员拉人进来。
     * 而 {@code MemberRoleRequest#role}（改角色）是必填的 {@code @NotBlank} ——
     * 那个入口不存在"未指定"。因此"未指定时取什么"只属于可选入口，
     * 不适合塞进枚举本身：放进枚举，必填入口就会多出一个永远走不到的默认分支，
     * 而后来的人会以为改角色也可以不写。
     *
     * <p>默认取 {@link WorkspaceMemberRole#MEMBER} 而不是 ADMIN：
     * "没有指定"应当落在<b>权限更小</b>的一侧。
     *
     * @return 角色；未指定或为空白时为 {@code MEMBER}
     * @throws BusinessException 取值无法识别时 {@code 40001}
     */
    public WorkspaceMemberRole toRole() {
        if (role == null || role.isBlank()) {
            return WorkspaceMemberRole.MEMBER;
        }
        return WorkspaceMemberRole.parse(role)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST));
    }
}
