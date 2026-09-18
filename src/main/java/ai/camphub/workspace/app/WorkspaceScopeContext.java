package ai.camphub.workspace.app;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 当前线程"已授权空间集合"的显式载体 —— 第三层防线的数据来源。
 *
 * <h2>为什么需要它，而不是让拦截器每次自己去查</h2>
 * 一次请求内通常要跑好几条被限制的查询（列表 SQL + 计数 SQL 就是两条）。
 * 若每条查询都重新解析一次"调用者被授权哪些空间"，同一次请求会重复执行同样的
 * 小查询若干次。绑定进线程后，整条请求链路共用一份结果。
 *
 * <h2>为什么必须是 ThreadLocal，以及为什么必须有人负责清理</h2>
 * Tomcat 的请求线程来自线程池、会被下一个请求复用。若本线程上的集合没有被清掉，
 * <b>下一个请求会继承上一个请求的授权范围</b> —— 这正是"越权"二字最典型的一种成因，
 * 而且它只在并发下出现，本机单请求手工测试永远看不到。
 * 因此 {@code WorkspaceScopeResetFilter} 在每次请求结束时无条件清理，
 * 这里不提供任何"自动过期"之类的兜底 —— 兜底会掩盖忘记清理的问题。
 *
 * <h2>它放在 app 包而不是 infrastructure</h2>
 * "当前调用者是谁"是应用层的语境，它的写入方（{@code AuthorizationService}）
 * 与读取方（拦截器）分属 app 与 infrastructure。放在 app 使两者的依赖方向
 * 都指向同一处，而不是绕一圈。
 */
public final class WorkspaceScopeContext {

    /** 已绑定的授权空间集合。null 表示"本次请求还没绑定"。 */
    private static final ThreadLocal<Set<Long>> BOUND = new ThreadLocal<>();

    /** 显式绕过标记，见 {@link #unscoped(Supplier)}。 */
    private static final ThreadLocal<Boolean> BYPASSED = new ThreadLocal<>();

    private WorkspaceScopeContext() {
    }

    /**
     * 绑定本次调用的授权空间集合。
     *
     * @param workspaceIds 空间主键集合；传空集合表示"一个都没有"（而不是"不限"）
     */
    public static void bind(Set<Long> workspaceIds) {
        BOUND.set(Set.copyOf(workspaceIds));
    }

    /**
     * 读取已绑定的集合。
     *
     * @return 已绑定时非空；空表示尚未绑定，由读取方决定如何去取
     */
    public static Optional<Set<Long>> current() {
        return Optional.ofNullable(BOUND.get());
    }

    /**
     * 当前是否处于显式绕过状态。
     *
     * @return 绕过时为 true
     */
    public static boolean isBypassed() {
        return Boolean.TRUE.equals(BYPASSED.get());
    }

    /**
     * 在<b>不做空间过滤</b>的前提下执行一段逻辑。
     *
     * <h2>它只能被用在两个地方</h2>
     * <ol>
     *   <li><b>第三层防线自身的测试</b>：需要构造"服务层已经放行、但数据层仍应兜底"
     *       的场景时，必须先把过滤关掉，否则测的是过滤而不是兜底。</li>
     *   <li><b>跨空间的运维/迁移逻辑</b>：例如将来的一次性回填。当前代码里没有这种调用。</li>
     * </ol>
     * 它<b>不是</b>给业务代码"临时绕过权限"用的逃生门。任何一次新增调用都应当在
     * 代码评审里被当成一个问题来回答 —— 这也是它被命名为 {@code unscoped}
     * 而不是 {@code ignoreAuthorization} 的原因：名字要说清它关掉的是哪一层。
     *
     * @param action 待执行的逻辑
     * @param <T>    返回类型
     * @return 逻辑的返回值
     */
    public static <T> T unscoped(Supplier<T> action) {
        Boolean previous = BYPASSED.get();
        BYPASSED.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                BYPASSED.remove();
            } else {
                BYPASSED.set(previous);
            }
        }
    }

    /**
     * 清理本线程的绑定与绕过标记。由请求结束时的过滤器调用。
     */
    public static void clear() {
        BOUND.remove();
        BYPASSED.remove();
    }
}
