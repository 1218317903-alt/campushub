package ai.camphub.workspace.infrastructure.scope;

import ai.camphub.identity.domain.UserPrincipal;
import ai.camphub.workspace.app.AuthorizationService;
import ai.camphub.workspace.app.WorkspaceScopeContext;
import ai.camphub.workspace.domain.ScopedTable;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 第三层防线：MyBatis 拦截器，把标注了 {@link ScopedTable} 的查询自动限制到
 * "调用者被授权的空间"范围内。
 *
 * <h2>为什么它只是防护网，而不是主防线</h2>
 * 真正决定"这件事能不能做"的是第二层（{@code AuthorizationService}），
 * 它知道动作、知道身份、也允许对同一条数据给出细致的判断。
 * 这一层只知道一件事：<b>这条 SQL 碰的是私有数据，而调用者被授权的空间是这些</b>。
 * 它的价值在于覆盖"某条查询忘了写 workspace_id 过滤"这一种失误 ——
 * 这种失误在手工测试里几乎看不出来（用自己造的数据怎么测都正常），
 * 却会在生产上把别人的私有内容返回给不相干的人。
 *
 * <h2>为什么拦在 {@code StatementHandler.prepare}</h2>
 * 这是能拿到最终 SQL 的最早位置：{@code BoundSql} 已经由 SqlSource 组装完成、
 * 动态标签都已求值，而 {@code ?} 占位符还没有被绑定。
 * 在它之前（Executor 层）拿不到 BoundSql；在它之后（ParameterHandler 层）
 * 语句已经创建，改 SQL 也不会生效。
 *
 * <h2>关于改写 {@code BoundSql.sql} 这个 final 字段</h2>
 * MyBatis 没有为 SQL 提供可变入口，插件改写 SQL 只能通过
 * {@code MetaObject} 写那个字段。这里的做法是<b>实测确认过</b>的：
 * 在 MyBatis 3.5.19 + JDK 21 下 {@code SystemMetaObject.forObject(boundSql).setValue("sql", ...)}
 * 可以生效（{@code Reflector} 会为非静态 final 字段生成字段级的 setter）。
 * 这不是"照抄某个博客"，而是先写了探针验证再落地的 —— 因为它的失败模式是
 * <b>静默</b>的：字段没写进去，SQL 照跑，只是没有过滤，测试也不会红。
 *
 * <h2>授权集合从哪里来</h2>
 * 优先读 {@link WorkspaceScopeContext} 里已经绑定的值（一次请求算一次）；
 * 未绑定时当场按当前登录用户算一次并绑定。
 * 没有登录用户时绑定空集 —— 于是查询退化成 {@code 1 = 0}。
 * 这个默认方向是刻意的：<b>拿不到身份时应当查不到数据，而不是查到全部</b>。
 */
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class WorkspaceScopeInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceScopeInterceptor.class);

    /**
     * Mapper 方法 → 需要限定的列。{@code Optional.empty()} 表示该方法未被标注，
     * 缓存它同样重要：否则每次查询都要做一次反射查注解。
     */
    private final Map<String, Optional<String>> columnByStatementId = new ConcurrentHashMap<>();

    /** 懒取以打破 "拦截器 → AuthorizationService → Mapper → SqlSessionFactory → 拦截器" 的环。 */
    private final ObjectProvider<AuthorizationService> authorizationService;

    /**
     * 构造注入。
     *
     * @param authorizationService 授权服务（懒取）
     */
    public WorkspaceScopeInterceptor(ObjectProvider<AuthorizationService> authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 显式绕过：整条防线不参与。注意这里必须"完全不改写"，
        // 而不是"按空集改写" —— 后者会变成 1 = 0，把绕过做成更严格的过滤。
        if (WorkspaceScopeContext.isBypassed()) {
            return invocation.proceed();
        }

        StatementHandler handler = (StatementHandler) invocation.getTarget();
        Optional<String> column = columnFor(handler);
        if (column.isEmpty()) {
            return invocation.proceed();
        }

        BoundSql boundSql = handler.getBoundSql();
        String original = boundSql.getSql();
        Set<Long> authorized = authorizedWorkspaceIds();
        String rewritten = WorkspaceScopeSql.apply(original, column.get(), authorized);

        if (!rewritten.equals(original)) {
            SystemMetaObject.forObject(boundSql).setValue("sql", rewritten);
            if (log.isDebugEnabled()) {
                log.debug("第三层防线改写 SQL：{} -> {}", original, rewritten);
            }
        }
        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    /**
     * 解析当前语句对应的 Mapper 方法上的 {@link ScopedTable} 注解。
     *
     * @param handler 语句处理器
     * @return 需要限定的列；未标注时为空
     */
    private Optional<String> columnFor(StatementHandler handler) {
        String statementId = resolveStatementId(handler);
        if (statementId == null) {
            return Optional.empty();
        }
        return columnByStatementId.computeIfAbsent(statementId, this::lookupColumn);
    }

    /**
     * 取 MappedStatement 的 id（形如 {@code 全限定接口名.方法名}）。
     *
     * <p>MyBatis 没有把 MappedStatement 暴露在 {@code StatementHandler} 接口上，
     * 只能沿着 {@code RoutingStatementHandler.delegate.mappedStatement} 读。
     * <b>解析失败时抛异常而不是返回 null</b>：返回 null 等于这一层悄悄失效，
     * 而"防线失效"和"防线没被触发"在日志里长得一样。
     * 真出现这种失败，集成测试会立刻变红，比在生产上静默少一道防线好得多。
     *
     * @param handler 语句处理器
     * @return 语句 id
     */
    private String resolveStatementId(StatementHandler handler) {
        try {
            Object mapped = SystemMetaObject.forObject(handler).getValue("delegate.mappedStatement");
            if (mapped instanceof MappedStatement statement) {
                return statement.getId();
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "无法解析 MappedStatement，第三层防线无法判定是否需要追加空间过滤", ex);
        }
        throw new IllegalStateException(
                "StatementHandler 上取不到 MappedStatement（MyBatis 内部结构可能已变化）");
    }

    /**
     * 反射查注解。
     *
     * @param statementId 语句 id，形如 {@code ai.camphub...Mapper.findByPublicId}
     * @return 需要限定的列
     */
    private Optional<String> lookupColumn(String statementId) {
        int separator = statementId.lastIndexOf('.');
        if (separator <= 0) {
            return Optional.empty();
        }
        String className = statementId.substring(0, separator);
        String methodName = statementId.substring(separator + 1);
        try {
            Class<?> mapperType = Class.forName(className);
            for (Method method : mapperType.getMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                ScopedTable annotation = method.getAnnotation(ScopedTable.class);
                if (annotation != null) {
                    return Optional.of(annotation.column());
                }
            }
            return Optional.empty();
        } catch (ClassNotFoundException ex) {
            // Mapper 接口一定在应用自己的类路径上。取不到说明语句 id 的形状变了，
            // 那意味着下面那条"每条私有查询都必须显式回答问题"的纪律已经失效。
            throw new IllegalStateException("找不到语句所属的 Mapper 接口：" + className, ex);
        }
    }

    /**
     * 取本次调用被授权的空间主键集合。
     *
     * @return 集合；未登录或解析不出身份时为空集
     */
    private Set<Long> authorizedWorkspaceIds() {
        Optional<Set<Long>> bound = WorkspaceScopeContext.current();
        if (bound.isPresent()) {
            return bound.get();
        }
        Set<Long> resolved = resolveFromSecurityContext();
        WorkspaceScopeContext.bind(resolved);
        return resolved;
    }

    /**
     * 从 Spring Security 上下文解析当前用户被授权的空间。
     *
     * @return 集合；无登录用户时为空集
     */
    private Set<Long> resolveFromSecurityContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return Set.of();
        }
        return authorizationService.getObject().authorizedWorkspaceIds(principal.userId());
    }
}
