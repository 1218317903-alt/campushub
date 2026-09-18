package ai.camphub.workspace.domain;

/**
 * 一条成员关系里的"空间 + 角色"。
 *
 * <h2>为什么需要它，而不是复用 {@link WorkspaceMember}</h2>
 * {@link WorkspaceMember} 带的是 {@code user_id} —— 它回答"这个空间有哪些人"。
 * 列表中要回答的是相反方向的问题："这个人参与了哪些空间、分别是什么角色"。
 * 两者来自同一张表，但用途相反，因此字段也不同（这里带 {@code workspaceId}，
 * 那里带 {@code userId}）。
 *
 * <p>把它单独定义出来，是为了让"列出我参与的每个空间时我是管理员还是普通成员"
 * 能用<b>一次</b>查询回答。若改成逐条查，一个有二十个空间的用户打开列表页
 * 就会产生二十次往返 —— 而这类 N+1 在功能测试里完全看不出来。
 *
 * @param workspaceId 空间自增主键
 * @param role        成员在该空间内的角色（不含 OWNER，拥有者不在成员表里）
 */
public record MembershipRole(
        long workspaceId,
        WorkspaceMemberRole role
) {
}
