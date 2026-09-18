package ai.camphub.workspace.domain;

/**
 * 空间可见性。
 *
 * <h2>为什么只有两个值</h2>
 * {@code docs/03-domain-permission.md §8} 的原始设计里还有 {@code PUBLIC_READONLY}。
 * Phase 04 刻意不实现它，理由是它不是"枚举多一个值"，而是一整条独立链路：
 * 公开只读意味着必须同时定义公开预览（谁能看到什么）、撤回（撤回后在他人缓存/搜索结果中
 * 如何失效）、以及"公开的是整个空间还是空间内某几篇文档"这一层策略。
 * 在枚举里放进一个尚未有代码支持的取值，会让后来的人以为这条链路已经存在。
 *
 * <p>项目有一条硬约束：<b>私有内容绝不自动公开</b>。可见性只有一个入口、且默认
 * {@link #PRIVATE}，是这条约束在类型层面的表达 —— 新建空间除非明确指定，
 * 否则不可能是对他人可见的。
 *
 * <h2>两个取值当前对"谁能读"没有区别，不要照字面去实现差异</h2>
 * 两个取值<b>都</b>只对拥有者与被邀请的成员可见；区别不在这里。
 * 具体说：{@code PRIVATE} 说的是"起点是只有拥有者"，{@code TEAM} 说的是
 * "起点就是这个团队"，而两者<b>扩大可见范围的手段都只有邀请这一条</b>。
 * 因此一个 {@code PRIVATE} 空间在邀请了一名成员之后，那名成员是能读到它的 ——
 * 这是邀请功能赖以成立的前提。
 *
 * <p>授权判定路径（{@code AuthorizationService}）刻意完全不读可见性：
 * 谁是成员由 {@code workspace.owner_id} 与 {@code workspace_member} 回答。
 * 若哪天在这里加上"PRIVATE 只给拥有者看"的一支，表现会是
 * <b>所有用默认值创建的空间里，成员一律读不到内容</b> ——
 * 而那是先有邀请、后失效的静默故障。当前两个取值的差异只体现在
 * 对外展示与将来的发现/推荐策略上（见 {@code docs/resource-authorization.md} §11）。
 */
public enum WorkspaceVisibility {

    /** 起点只有拥有者可见（不含他邀请来的成员）。成员邀请是唯一的扩大可见范围的手段。 */
    PRIVATE,

    /** 起点就是空间内成员可见。仍然不是公开：非成员看不到任何内容。 */
    TEAM;

    /**
     * 解析客户端传入的可见性。
     *
     * <p>取值不区分大小写，空白或空串视为 {@link #PRIVATE} —— 让"没有指定"落在
     * 最保守的一侧。无法识别的取值返回空，由调用方决定如何响应
     * （而不是在这里悄悄降级成 PRIVATE，那会让一个拼错的参数看起来像是生效了）。
     *
     * @param raw 原始取值，可为 null
     * @return 解析结果，无法识别时为空
     */
    public static java.util.Optional<WorkspaceVisibility> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.of(PRIVATE);
        }
        for (WorkspaceVisibility value : values()) {
            if (value.name().equalsIgnoreCase(raw.strip())) {
                return java.util.Optional.of(value);
            }
        }
        return java.util.Optional.empty();
    }
}
