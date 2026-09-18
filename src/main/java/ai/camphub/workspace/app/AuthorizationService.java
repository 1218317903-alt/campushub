package ai.camphub.workspace.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.domain.Workspace;
import ai.camphub.workspace.domain.WorkspaceAction;
import ai.camphub.workspace.domain.WorkspaceMemberRole;
import ai.camphub.workspace.domain.WorkspaceResourceType;
import ai.camphub.workspace.domain.WorkspaceRoleInContext;
import ai.camphub.workspace.infrastructure.WorkspaceMapper;
import ai.camphub.workspace.infrastructure.WorkspaceMemberMapper;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 第二层防线：资源级鉴权的唯一入口（{@code docs/03-domain-permission.md §10.4}）。
 *
 * <h2>它回答的问题与第一层、第三层都不同</h2>
 * <table>
 *   <tr><th>层</th><th>问题</th><th>失败时</th></tr>
 *   <tr><td>一（{@code @PreAuthorize}）</td>
 *       <td>这个<b>身份</b>有没有做这类事的能力？</td><td>403</td></tr>
 *   <tr><td>二（本类）</td>
 *       <td>这个人对<b>这一条数据</b>能不能做这件事？</td><td>404 / 403</td></tr>
 *   <tr><td>三（拦截器）</td>
 *       <td>这条 SQL 有没有被限制在这个人的空间范围内？</td><td>空集</td></tr>
 * </table>
 * 第一层刻意只做身份判断（{@code hasAuthority('...')}），不做资源查询。
 * 原始设计（docs/03 §10.3）写的是 {@code @PreAuthorize("hasPermission(#req.workspaceId, ...)")}，
 * 即在第一层就做资源级判断。这里没有那样做，理由有两条：
 * <ul>
 *   <li><b>失败码会冲突。</b>第一层失败一律 403，而"看不到别人的空间"必须返回 404
 *       （docs/03 §10.5 第 1 条：URL 遍历不能读）。两层都在 404 语义上下判断，
 *       只会让"到底是谁拒绝的"变成需要读日志才能回答的问题。</li>
 *   <li>它需要额外的 {@code PermissionEvaluator} 装配，而那个装配所做的事
 *       与本类完全重复 —— 同一套规则有两个实现，就有了分叉的可能。</li>
 * </ul>
 *
 * <h2>为什么"不可见"返回 404 而不是 403</h2>
 * 403 等于承认"这个资源存在，只是不给你看"。攻击者据此可以把一个空间是否存在、
 * 甚至它的 public_id 是否正确，从响应码里读出来。404 让"不存在"与"不归你"
 * 在外部完全无法区分。<b>代价是真正无权限的用户也会看到"找不到"</b> ——
 * 这是有意的信息损失，与社区模块"非作者编辑帖子返回 404"的取舍一致。
 *
 * <h2>403 仍然存在，用在另一处</h2>
 * 调用者<b>能看到</b>这个空间、但没有做该动作的身份时（普通成员想删除空间），
 * 返回 403。此时"空间存在"已经是他本来就掌握的信息，再假装不存在只会让人困惑。
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;

    /**
     * 构造注入。
     *
     * @param workspaceMapper 空间 Mapper
     * @param memberMapper    成员 Mapper
     */
    public AuthorizationService(WorkspaceMapper workspaceMapper, WorkspaceMemberMapper memberMapper) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
    }

    // ------------------------------------------------------------------------
    // 第三层的接缝：授权集合
    // ------------------------------------------------------------------------

    /**
     * 某个用户被授权的全部空间主键。
     *
     * <p>它是"权限判定"与"检索过滤"之间的唯一接缝（docs/03 §10.4）：
     * 第三层防线与将来任何需要按空间过滤的检索，都只通过这里拿范围。
     * 换成别处各算一遍，就会出现在"空间 A 算进去了、空间 B 没算"这类
     * 只在特定查询上表现出的漏检。
     *
     * <p>集合是"他拥有的" ∪ "他是成员的"。拥有者刻意不写入成员表
     * （见 {@code V5__workspace.sql} 的偏离说明），因此这里必须并上两处 ——
     * 漏掉任何一处，拥有者或成员会在某些查询上莫名看不到自己的数据。
     *
     * @param userId 用户自增主键
     * @return 空间主键集合，可能为空
     */
    public Set<Long> authorizedWorkspaceIds(long userId) {
        Set<Long> ids = new HashSet<>(workspaceMapper.findOwnedIds(userId));
        ids.addAll(memberMapper.findMemberWorkspaceIds(userId));
        return ids;
    }

    /**
     * 在"以某个用户的身份"的前提下执行一段逻辑，并在结束时清理范围绑定。
     *
     * <h2>它给谁用</h2>
     * 演示数据生成（{@code DemoSeedRunner}）走的是应用服务，而不是 Mapper ——
     * 但那个进程里没有 HTTP 请求，也就没有 Spring Security 上下文，
     * 第三层防线会算出空集，让播种写入的数据随后一条都读不回来。
     * 用本方法显式声明"这段逻辑以这个用户的身份执行"，比提供一个"关掉权限"的开关安全：
     * 它保持范围绑定，只是把身份从"当前请求"换成"指定的用户"。
     *
     * <p>它与 {@link WorkspaceScopeContext#unscoped} 的区别是本质的：
     * 那个是彻底关掉过滤，只有防线自身的测试才该用；这个是换一个身份，过滤照常生效。
     *
     * @param userId 要代入的用户
     * @param action 待执行的逻辑
     * @param <T>    返回类型
     * @return 逻辑的返回值
     */
    public <T> T runAs(long userId, Supplier<T> action) {
        WorkspaceScopeContext.bind(authorizedWorkspaceIds(userId));
        try {
            return action.get();
        } finally {
            WorkspaceScopeContext.clear();
        }
    }

    // ------------------------------------------------------------------------
    // 判定
    // ------------------------------------------------------------------------

    /**
     * 判断某个用户在某个空间里的身份。
     *
     * @param userId      用户自增主键
     * @param workspaceId 空间自增主键
     * @return 身份；空间不存在、已删除、或该用户不是成员时为
     *         {@link WorkspaceRoleInContext#NONE}
     */
    public WorkspaceRoleInContext roleIn(long userId, long workspaceId) {
        Optional<Workspace> found = workspaceMapper.findById(workspaceId);
        if (found.isEmpty()) {
            return WorkspaceRoleInContext.NONE;
        }
        if (found.get().isOwnedBy(userId)) {
            return WorkspaceRoleInContext.OWNER;
        }
        return memberMapper.findRole(workspaceId, userId)
                .map(role -> role == WorkspaceMemberRole.ADMIN
                        ? WorkspaceRoleInContext.ADMIN
                        : WorkspaceRoleInContext.MEMBER)
                .orElse(WorkspaceRoleInContext.NONE);
    }

    /**
     * 判定是否允许，不抛异常。供需要按判定结果分支的调用方使用。
     *
     * @param userId      用户自增主键
     * @param workspaceId 空间自增主键
     * @param action      动作
     * @return 是否允许
     */
    public boolean decide(long userId, long workspaceId, WorkspaceAction action) {
        return action.allows(roleIn(userId, workspaceId));
    }

    /**
     * 判定并抛异常。所有会读写空间数据的入口都必须先经过它。
     *
     * @param userId      用户自增主键
     * @param workspaceId 空间自增主键
     * @param action      动作
     * @return 判定所依据的身份（调用者随后若还需要它，不必再查一次）
     * @throws BusinessException 空间不可见时 {@code 40400}；可见但无权限时 {@code 40300}
     */
    public WorkspaceRoleInContext assertCan(long userId, long workspaceId, WorkspaceAction action) {
        return assertCan(userId, workspaceId, action, true);
    }

    /**
     * 判定并抛异常，带归属维度。
     *
     * <h2>为什么返回身份而不是 {@code void}</h2>
     * 判定"能不能做"的凭据就是身份，而调用方常常紧接着就需要它 ——
     * 例如"这条笔记的删除按钮要不要显示"。让它返回，是把这次查询的结果交出去；
     * 若返回 {@code void}，那些调用点只能再调一次 {@link #roleIn}，
     * 于是同一次判定被算两遍。算两遍的代价不只是两次索引查询，
     * 还给了两处结果不一致的机会（一个在事务内、一个在事务外）。
     *
     * @param userId      用户自增主键
     * @param workspaceId 空间自增主键
     * @param action      动作
     * @param ownedBySelf 目标资源是否由调用者创建/上传；仅删除类动作会用到它
     * @return 判定所依据的身份
     * @throws BusinessException 空间不可见时 {@code 40400}；可见但无权限时 {@code 40300}
     */
    public WorkspaceRoleInContext assertCan(long userId, long workspaceId, WorkspaceAction action,
                                            boolean ownedBySelf) {
        WorkspaceRoleInContext role = roleIn(userId, workspaceId);
        if (role == WorkspaceRoleInContext.NONE) {
            // 刻意用 debug 而不是 warn：这个分支在正常使用里会被"探测别人空间"的
            // 请求频繁触发，把它打成 warn 只会制造噪声，让真正的异常淹没在里面。
            log.debug("资源不可见，按不存在处理：userId={} workspaceId={} action={}",
                    userId, workspaceId, action);
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!action.allows(role, ownedBySelf)) {
            log.info("已可见但无权限：userId={} workspaceId={} role={} action={}",
                    userId, workspaceId, role, action);
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return role;
    }

    /**
     * 确认某个用户至少能读这个空间，否则按不存在处理。
     *
     * <p>供那些"只要能看到空间就够了"的读取路径使用。它等价于
     * {@code assertCan(userId, workspaceId, READ_WORKSPACE)}，
     * 单独存在是为了让调用点的意图在代码上就看得出来。
     *
     * @param userId      用户自增主键
     * @param workspaceId 空间自增主键
     * @param resourceType 被访问的资源种类，仅用于日志与审计可读性
     * @throws BusinessException 不可见时 {@code 40400}
     */
    public void assertCanRead(long userId, long workspaceId, WorkspaceResourceType resourceType) {
        WorkspaceRoleInContext role = roleIn(userId, workspaceId);
        if (role == WorkspaceRoleInContext.NONE) {
            log.debug("资源不可见，按不存在处理：userId={} workspaceId={} resource={}",
                    userId, workspaceId, resourceType);
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }
}
