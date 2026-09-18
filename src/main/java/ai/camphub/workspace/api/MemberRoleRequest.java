package ai.camphub.workspace.api;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.domain.WorkspaceMemberRole;
import jakarta.validation.constraints.NotBlank;

/**
 * 修改成员角色的请求体。
 *
 * <h2>为什么是独立的请求体而不是 {@code @RequestParam String role}</h2>
 * 角色放在请求体里，让"改角色"这个动作的输入与"邀请"、"创建空间"保持一致 ——
 * 都是 JSON 对象。若它走查询参数，客户端的请求构造方式就多出一种，
 * 而多出来那一种往往在日志与审计里不出现（URL 上的参数会被网关单独记录，
 * 也可能被误打进访问日志）。
 *
 * <p>{@code role} 的类型是字符串，理由同 {@link InviteRequest}：
 * 取值域的合法性判定只有 {@code WorkspaceMemberRole.parse} 一处定义。
 *
 * @param role 新角色，取值 {@code MEMBER | ADMIN}；不区分大小写
 */
public record MemberRoleRequest(
        @NotBlank(message = "请指定成员角色")
        String role
) {

    /**
     * 解析角色。
     *
     * @return 角色
     * @throws BusinessException 取值无法识别时 {@code 40001}
     */
    public WorkspaceMemberRole toRole() {
        return WorkspaceMemberRole.parse(role)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST));
    }
}
