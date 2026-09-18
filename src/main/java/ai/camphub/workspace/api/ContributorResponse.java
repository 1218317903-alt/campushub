package ai.camphub.workspace.api;

import ai.camphub.identity.domain.UserBrief;

/**
 * 响应里出现的"某个人"（笔记作者、编辑者、文档上传者）。
 *
 * <h2>为什么不复用 community 的同名类型</h2>
 * 社区模块里已经有一个字段完全相同的 {@code AuthorResponse}。
 * 直接引用它会让空间模块<b>编译期依赖社区模块</b> —— 两个业务域之间的一条无意义边：
 * 社区改一次字段名，空间模块跟着编译失败，而后者的功能与社区毫无关系。
 *
 * <p>这与 {@code PageResponse} 的处理方式不同（那个迁到了 {@code common}），
 * 差别在于能否共享：分页信封是纯粹的传输结构，不含任何业务语义，
 * 因此适合放进共享内核；而"作者"是<b>社区语境</b>的概念（它有 mayEdit 之类的兄弟字段），
 * 空间模块要展示的只是"昵称 + 头像 + 对外标识"。
 * 把同名不同语境的两个类型塞进 common，会让 common 变成业务概念的堆积地。
 *
 * <h2>为什么不带用户自增主键</h2>
 * 自增主键是可遍历的，绝不出现在任何响应里。它与 {@code WorkspaceMemberView}
 * 分属两层的理由相同：内部类型带主键以便去重与批量查询，对外类型只带对外标识。
 *
 * @param publicId  用户对外标识
 * @param nickname  展示名
 * @param avatarUrl 头像地址，可为 null
 */
public record ContributorResponse(String publicId, String nickname, String avatarUrl) {

    /**
     * 从用户展示信息构造。
     *
     * @param brief 用户展示信息；账号已被软删除时为 null
     * @return 响应；{@code brief} 为 null 时返回 null
     */
    public static ContributorResponse from(UserBrief brief) {
        // 刻意返回 null 而不是一个空壳对象：一个昵称为空串的人与"这个人不存在"
        // 在界面上无法区分，而前者看起来像数据损坏。调用方（JSON 序列化）
        // 会把它写成 null，前端据此显示"已注销用户"。
        if (brief == null) {
            return null;
        }
        return new ContributorResponse(brief.publicId(), brief.nickname(), brief.avatarUrl());
    }
}
