package ai.camphub.workspace.domain;

/**
 * 某个用户在某个空间里的实际身份。
 *
 * <h2>为什么单独有 OWNER 这一档</h2>
 * 它与 {@link WorkspaceMemberRole} 不是同一层概念：后者是"被授予的角色"（落库），
 * 前者是"在这个空间里这个人实际是什么身份"（算出来的）。拥有者在库里只是
 * {@code workspace.owner_id} 上的一行，但所有判定都要把它当成比 ADMIN 更高的一档 ——
 * 只有拥有者能改空间设置、能删除空间、能转让空间。
 *
 * <p>把它建模成一个包含 {@code NONE} 的枚举，而不是用 {@code null} 表示"不是成员"，
 * 是因为 {@code null} 会让每个调用点都必须先判空再比较，而漏判的表现是
 * "拿 null 去 switch"这类只有走到才会暴露的错误。{@link #NONE} 让"不是成员"
 * 成为一个需要显式处理的分支。
 */
public enum WorkspaceRoleInContext {

    /** 空间拥有者。空间设置、删除、成员管理的最终决定权。 */
    OWNER,

    /** 空间管理员。可管理成员，不能改空间设置或删除空间。 */
    ADMIN,

    /** 普通成员。可读写空间内的笔记与文档。 */
    MEMBER,

    /** 不是这个空间的成员，或者空间对本主体不可见。 */
    NONE
}
