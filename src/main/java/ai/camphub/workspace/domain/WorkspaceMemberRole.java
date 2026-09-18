package ai.camphub.workspace.domain;

/**
 * 成员在空间内的角色。
 *
 * <h2>它不是平台角色</h2>
 * 这两个取值是 {@code (user, workspace)} 这个二元组的属性，不是挂在账号上的全局角色。
 * 它们存在 {@code workspace_member} 表而不是 {@code role} 表，理由见
 * {@code docs/03-domain-permission.md §10.1}：一旦塞进全局角色表，立刻产生
 * "在 A 空间是管理员，于是能管 B 空间"的越权。
 *
 * <h2>为什么没有 OWNER</h2>
 * 拥有者不是一种"被授予的角色"，而是空间的一个属性（{@code workspace.owner_id}）。
 * 把它也做成角色值，就会出现"成员表说是 OWNER、空间表说是别人"的双真相，
 * 而恰好是鉴权判定要读这两处。拥有者在判定流程里由 {@link WorkspaceRoleInContext#OWNER}
 * 表达，不落库。
 */
public enum WorkspaceMemberRole {

    /** 普通成员：可读写空间内的笔记与文档，不能管理成员、不能改空间设置。 */
    MEMBER,

    /** 空间管理员：在普通成员之上，可管理成员（邀请、移除、改角色）。 */
    ADMIN;

    /**
     * 解析客户端传入的角色。
     *
     * @param raw 原始取值，可为 null
     * @return 解析结果，无法识别时为空
     */
    public static java.util.Optional<WorkspaceMemberRole> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        for (WorkspaceMemberRole value : values()) {
            if (value.name().equalsIgnoreCase(raw.strip())) {
                return java.util.Optional.of(value);
            }
        }
        return java.util.Optional.empty();
    }
}
