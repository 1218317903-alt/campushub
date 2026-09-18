package ai.camphub.workspace.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 空间内的动作。
 *
 * <h2>这是第二层防线的输入</h2>
 * {@code AuthorizationService.assertCan(userId, workspaceId, action)} 用它表达
 * "这个人想在这个空间里做这件事"。每个取值对应一组被允许的
 * {@link WorkspaceRoleInContext}，见 {@link #allowedRoles()}。
 *
 * <h2>为什么把允许的角色写在这个枚举里，而不是散在 Service 的 if 里</h2>
 * 权限矩阵是本阶段最需要被整体审视的东西 —— 它一旦散落，就只有把所有 Service
 * 读一遍才能回答"普通成员到底能做什么"。集中在这里之后，矩阵是一张可以逐行核对、
 * 也可以被单元测试逐格断言的表。
 *
 * <p>这张矩阵同时是 {@code docs/resource-authorization.md} 里那张表的事实来源：
 * 文档与代码不一致时，以这里为准，因为这里会跑测试。
 */
public enum WorkspaceAction {

    /** 读取空间基本信息（名称、描述、可见性、成员列表）。 */
    READ_WORKSPACE(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN, WorkspaceRoleInContext.MEMBER),

    /** 修改空间设置（名称、描述、可见性）。只有拥有者：改可见性等于改"谁能看到这个空间"。 */
    UPDATE_WORKSPACE(WorkspaceRoleInContext.OWNER),

    /** 删除空间。只有拥有者。 */
    DELETE_WORKSPACE(WorkspaceRoleInContext.OWNER),

    /** 邀请他人加入。拥有者与管理员。 */
    INVITE_MEMBER(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN),

    /** 移除成员。拥有者与管理员。 */
    REMOVE_MEMBER(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN),

    /** 修改成员角色。拥有者与管理员。 */
    UPDATE_MEMBER_ROLE(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN),

    /** 读取笔记（含列表与详情）。全体成员。 */
    READ_NOTES(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN, WorkspaceRoleInContext.MEMBER),

    /**
     * 创建笔记。全体成员。
     *
     * <p>刻意不复用 {@link #UPDATE_WORKSPACE}：后者是"改空间设置"、只有拥有者具备，
     * 而写笔记是空间存在的意义本身，普通成员必须能做。
     */
    CREATE_NOTE(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN, WorkspaceRoleInContext.MEMBER),

    /**
     * 编辑笔记。全体成员 —— 笔记是协作的。
     *
     * <p>与删除的区别是刻意的：编辑不会丢失内容（{@code body_md} 是事实来源，
     * 且任何一次编辑都是一次可追溯的 {@code updated_by} 变更），而删除会让
     * 其他成员的引用失效。因此在"协作"和"不让人误删别人的东西"之间，
     * 编辑开放、删除收紧。
     */
    UPDATE_NOTE(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN, WorkspaceRoleInContext.MEMBER),

    /**
     * 删除笔记。实际判定还要看"是不是自己创建的"，见
     * {@link #mayDeleteNoteOwnedBy(WorkspaceRoleInContext, boolean)}。
     */
    DELETE_NOTE(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN, WorkspaceRoleInContext.MEMBER),

    /** 读取文档元数据与列表。全体成员。 */
    READ_DOCUMENTS(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN, WorkspaceRoleInContext.MEMBER),

    /** 上传文档。全体成员。 */
    UPLOAD_DOCUMENT(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN, WorkspaceRoleInContext.MEMBER),

    /** 删除文档。同笔记：还要看是不是自己上传的。 */
    DELETE_DOCUMENT(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN, WorkspaceRoleInContext.MEMBER),

    /** 下载文档原文件。全体成员。 */
    DOWNLOAD_DOCUMENT(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN, WorkspaceRoleInContext.MEMBER),

    /**
     * 重新解析文档。同删除：还要看是不是自己上传的。
     *
     * <h2>为什么它需要归属判定，而上传不需要</h2>
     * 上传只是"加入一份新东西"，代价与影响都局限于自己。重新解析不同：
     * 它会<b>替换这份文档现有的可检索内容</b>（旧分块先删后写）。
     * 对一份别人上传、且已经解析成功的文档反复触发重解析，
     * 既能反复占用解析资源，也能让那份内容在一段时间里不可检索 ——
     * 因此普通成员只能重试自己上传的，拥有者与管理员可以处理任何一条。
     */
    RETRY_DOCUMENT_PARSE(WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN, WorkspaceRoleInContext.MEMBER);

    private final Set<WorkspaceRoleInContext> allowedRoles;

    WorkspaceAction(WorkspaceRoleInContext first, WorkspaceRoleInContext... rest) {
        EnumSet<WorkspaceRoleInContext> roles = EnumSet.of(first, rest);
        this.allowedRoles = java.util.Collections.unmodifiableSet(roles);
    }

    /**
     * 允许执行本动作的身份集合。
     *
     * @return 不可变集合
     */
    public Set<WorkspaceRoleInContext> allowedRoles() {
        return allowedRoles;
    }

    /**
     * 判断某个身份是否允许执行本动作。
     *
     * @param role 调用者在空间内的身份；{@link WorkspaceRoleInContext#NONE} 一律拒绝
     * @return 是否允许
     */
    public boolean allows(WorkspaceRoleInContext role) {
        return allowedRoles.contains(role);
    }

    /**
     * 本动作是否区分"这条资源是不是本人创建的"。
     *
     * <p>删除类动作与重新解析都是。它作为一个由枚举自身回答的问题存在，
     * 而不是让调用方去 {@code switch (action) { case DELETE_NOTE, ... -> ... }} ——
     * 后者意味着"哪些动作区分归属"这件事散落在调用点，
     * 而新增一个区分归属的动作时没有任何东西会提醒你去补上那一支。
     *
     * <p>新增 {@link #RETRY_DOCUMENT_PARSE} 时正是这个字段在提醒改动者：
     * 加一个动作需要回答两个问题（允许哪些身份、是否看归属），
     * 而第二个问题不回答就会默认变成"不看归属"。
     *
     * @return 是否区分归属
     */
    public boolean isOwnershipSensitive() {
        return this == DELETE_NOTE || this == DELETE_DOCUMENT
                || this == RETRY_DOCUMENT_PARSE;
    }

    /**
     * 带归属维度的完整判定。
     *
     * <p>对不区分归属的动作，{@code ownedBySelf} 被忽略。对删除类动作：
     * 成员只能删自己创建的，拥有者与管理员可以删任何一条 ——
     * 这正是"协作内容开放编辑、但删除需要正当性"这条取舍的落点。
     *
     * @param role        调用者在空间内的身份
     * @param ownedBySelf 这条资源是否由调用者创建/上传
     * @return 是否允许
     */
    public boolean allows(WorkspaceRoleInContext role, boolean ownedBySelf) {
        if (!allowedRoles.contains(role)) {
            return false;
        }
        if (!isOwnershipSensitive()) {
            return true;
        }
        return ownedBySelf || role == WorkspaceRoleInContext.OWNER || role == WorkspaceRoleInContext.ADMIN;
    }
}
