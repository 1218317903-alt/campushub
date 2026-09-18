package ai.camphub.workspace.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.common.random.RandomValues;
import ai.camphub.common.web.Page;
import ai.camphub.identity.app.UserDirectory;
import ai.camphub.identity.domain.UserBrief;
import ai.camphub.platform.audit.app.AuditService;
import ai.camphub.platform.audit.domain.AuditAction;
import ai.camphub.platform.audit.domain.AuditResult;
import ai.camphub.workspace.config.WorkspaceProperties;
import ai.camphub.workspace.domain.InviteDraft;
import ai.camphub.workspace.domain.MembershipRole;
import ai.camphub.workspace.domain.Workspace;
import ai.camphub.workspace.domain.WorkspaceAction;
import ai.camphub.workspace.domain.WorkspaceDraft;
import ai.camphub.workspace.domain.WorkspaceInvite;
import ai.camphub.workspace.domain.WorkspaceMember;
import ai.camphub.workspace.domain.WorkspaceMemberRole;
import ai.camphub.workspace.domain.WorkspaceMemberView;
import ai.camphub.workspace.domain.WorkspaceRoleInContext;
import ai.camphub.workspace.domain.WorkspaceVisibility;
import ai.camphub.workspace.infrastructure.WorkspaceInviteMapper;
import ai.camphub.workspace.infrastructure.WorkspaceMapper;
import ai.camphub.workspace.infrastructure.WorkspaceMemberMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 空间、成员与邀请的应用服务。
 *
 * <h2>每一个方法的第一件或第二件事都是鉴权</h2>
 * 顺序是刻意的，而且不允许调换：
 * <ol>
 *   <li><b>解析</b>：把 URL 上的 {@code publicId} 换成内部主键。此时还没有做任何判定。</li>
 *   <li><b>判定</b>：{@link AuthorizationService#assertCan} —— 不可见时抛 404，
 *       可见但无权限时抛 403。</li>
 *   <li><b>取数</b>：真正读或写业务数据。第三层防线在这一步兜底。</li>
 * </ol>
 * 把"解析"放在最前面不是因为它安全，而是因为它必须发生 —— 但它<b>不构成授权</b>。
 * 因此有一条纪律：<b>任何拿到 {@code Workspace} 的地方，都必须紧跟着一次明确的判定</b>。
 * 下面所有方法都遵守这一点，{@code requireWorkspace} 存在的意义就是让这两步无法被拆开。
 *
 * <h2>为什么成员列表里要"合成"拥有者</h2>
 * 拥有者不是 {@code workspace_member} 里的一行（见 {@code V5__workspace.sql} 的偏离说明 3）。
 * 若成员列表只查成员表，界面上会出现"这个空间没有管理员"，而拥有者自己也看不到自己 ——
 * 那是一个让人怀疑数据丢失的显示。因此这里把拥有者合成进列表，
 * 它的角色是 {@link WorkspaceRoleInContext#OWNER}，加入时间取空间的创建时间。
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    /** 审计目标类型。用常量而不是散落的字符串字面量：审计查询要按它筛选。 */
    private static final String TARGET_WORKSPACE = "WORKSPACE";
    private static final String TARGET_MEMBER = "WORKSPACE_MEMBER";
    private static final String TARGET_INVITE = "WORKSPACE_INVITE";

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceInviteMapper inviteMapper;
    private final AuthorizationService authorization;
    private final UserDirectory userDirectory;
    private final AuditService auditService;
    private final WorkspaceProperties properties;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param workspaceMapper 空间数据访问
     * @param memberMapper    成员数据访问
     * @param inviteMapper    邀请数据访问
     * @param authorization   第二层防线
     * @param userDirectory   用户展示信息（跨模块只读）
     * @param auditService    审计
     * @param properties      空间模块配置
     * @param clock           时钟
     */
    public WorkspaceService(WorkspaceMapper workspaceMapper,
                            WorkspaceMemberMapper memberMapper,
                            WorkspaceInviteMapper inviteMapper,
                            AuthorizationService authorization,
                            UserDirectory userDirectory,
                            AuditService auditService,
                            WorkspaceProperties properties,
                            Clock clock) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
        this.inviteMapper = inviteMapper;
        this.authorization = authorization;
        this.userDirectory = userDirectory;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------
    // 空间
    // ------------------------------------------------------------------------

    /**
     * 创建空间。创建者成为拥有者，并且不写入成员表。
     *
     * @param ownerId    创建者自增主键
     * @param name       名称
     * @param description 描述，可为 null
     * @param visibility 可见性
     * @return 新建的空间及其身份（恒为拥有者）
     */
    @Transactional
    public WorkspaceSummary create(long ownerId, String name, String description,
                                   WorkspaceVisibility visibility) {
        String trimmedName = requireName(name);
        String trimmedDescription = requireDescription(description);

        String publicId = RandomValues.publicId();
        workspaceMapper.insert(new WorkspaceDraft(publicId, trimmedName, trimmedDescription, visibility, ownerId));
        Workspace created = requireByPublicId(publicId);

        // 审计的 targetId 存对外标识而不是自增主键：审计日志会被人直接查看，
        // 而自增主键在日志里既无法与界面上的东西对上，也让"日志被谁看到"变成信息泄漏。
        auditService.record(AuditAction.WORKSPACE_CREATE, AuditResult.SUCCESS, ownerId,
                TARGET_WORKSPACE, publicId, Map.of("visibility", visibility.name()));
        return new WorkspaceSummary(created, WorkspaceRoleInContext.OWNER);
    }

    /**
     * 分页查询当前用户可见的空间，并标出他在每一个里的身份。
     *
     * <p>不需要第二层判定：这条查询的筛选条件本身就是"我是拥有者或成员"，
     * 而第三层防线还会再兜一道。为它写一次 {@code assertCan} 反而无处安放 ——
     * 它涉及的是一批空间，不是一个空间。
     *
     * @param userId 当前用户自增主键
     * @param page   页码，从 1 开始
     * @param size   页大小
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public Page<WorkspaceSummary> listMine(long userId, int page, int size) {
        int effectiveSize = effectivePageSize(size);
        int effectivePage = Math.max(page, 1);
        long offset = (long) (effectivePage - 1) * effectiveSize;
        List<Workspace> items = workspaceMapper.findVisibleTo(userId, effectiveSize, offset);

        // 角色用一次批量查询解决，而不是逐行调 roleIn：
        // roleIn 每行要跑两次查询（一次取空间、一次取角色），一页 20 行就是 40 次往返，
        // 而这个页面恰恰是登录后的第一个着陆点。
        Map<Long, WorkspaceMemberRole> roles = memberMapper.findRolesOf(userId).stream()
                .collect(Collectors.toMap(MembershipRole::workspaceId, MembershipRole::role));

        List<WorkspaceSummary> summaries = items.stream()
                .map(workspace -> new WorkspaceSummary(workspace, roleOf(workspace, userId, roles)))
                .toList();
        return Page.of(summaries, effectivePage, effectiveSize, workspaceMapper.countVisibleTo(userId));
    }

    /**
     * 查询空间详情，附带调用者的身份。
     *
     * @param userId   当前用户自增主键
     * @param publicId 空间对外标识
     * @return 空间及其身份
     */
    @Transactional(readOnly = true)
    public WorkspaceSummary detail(long userId, String publicId) {
        WorkspaceAccess access = requireAccess(userId, publicId, WorkspaceAction.READ_WORKSPACE);
        return new WorkspaceSummary(access.workspace(), access.role());
    }

    /**
     * 修改空间设置。仅拥有者。
     *
     * @param userId      当前用户自增主键
     * @param publicId    空间对外标识
     * @param name        新名称
     * @param description 新描述，可为 null
     * @param visibility  新可见性
     * @return 更新后的空间及其身份
     */
    @Transactional
    public WorkspaceSummary update(long userId, String publicId, String name, String description,
                                   WorkspaceVisibility visibility) {
        Workspace workspace = requireWorkspace(userId, publicId, WorkspaceAction.UPDATE_WORKSPACE);
        workspaceMapper.updateSettings(workspace.id(), requireName(name),
                requireDescription(description), visibility);
        auditService.record(AuditAction.WORKSPACE_UPDATE, AuditResult.SUCCESS, userId,
                TARGET_WORKSPACE, publicId, Map.of("visibility", visibility.name()));
        return new WorkspaceSummary(requireByPublicId(publicId), WorkspaceRoleInContext.OWNER);
    }

    /**
     * 删除空间（软删除）。仅拥有者。
     *
     * <p>刻意<b>不</b>级联清理成员、笔记与文档：它们都通过外键挂在空间上，
     * 而空间只是被软删除，行仍然存在。级联清理属于"物理删除"的语义，
     * 与软删除混在一起会让"这条笔记为什么不见了"变成需要查两处才能回答的问题。
     *
     * @param userId   当前用户自增主键
     * @param publicId 空间对外标识
     */
    @Transactional
    public void delete(long userId, String publicId) {
        Workspace workspace = requireWorkspace(userId, publicId, WorkspaceAction.DELETE_WORKSPACE);
        workspaceMapper.softDelete(workspace.id(), clock.instant());
        auditService.record(AuditAction.WORKSPACE_DELETE, AuditResult.SUCCESS, userId,
                TARGET_WORKSPACE, publicId, null);
    }

    // ------------------------------------------------------------------------
    // 成员
    // ------------------------------------------------------------------------

    /**
     * 列出空间成员，包含合成的拥有者。
     *
     * @param userId   当前用户自增主键
     * @param publicId 空间对外标识
     * @return 成员列表，拥有者排在最前
     */
    @Transactional(readOnly = true)
    public List<WorkspaceMemberView> members(long userId, String publicId) {
        Workspace workspace = requireWorkspace(userId, publicId, WorkspaceAction.READ_WORKSPACE);

        List<WorkspaceMember> rows = memberMapper.findMembers(workspace.id());
        List<Long> userIds = new ArrayList<>(rows.size() + 1);
        userIds.add(workspace.ownerId());
        rows.forEach(row -> userIds.add(row.userId()));

        // 一次批量查询换出全部昵称，而不是逐条查 —— 成员列表每页最多几十行，
        // 但逐条查会把一次往返变成几十次，而这个差别只在真实数据下才看得出来。
        Map<Long, UserBrief> briefs = userDirectory.findBriefs(userIds);

        List<WorkspaceMemberView> views = new ArrayList<>(rows.size() + 1);
        UserBrief owner = briefs.get(workspace.ownerId());
        if (owner != null) {
            views.add(new WorkspaceMemberView(owner.publicId(), owner.nickname(), owner.avatarUrl(),
                    WorkspaceRoleInContext.OWNER, workspace.createdAt()));
        }
        for (WorkspaceMember row : rows) {
            UserBrief brief = briefs.get(row.userId());
            if (brief == null) {
                // 用户行查不到说明账号已被软删除。跳过而不是显示一个空壳：
                // 成员列表上挂一条没有名字的记录，比少一条更容易让人误解。
                log.warn("成员对应的用户已不存在，列表跳过：workspaceId={} userId={}",
                        workspace.id(), row.userId());
                continue;
            }
            views.add(new WorkspaceMemberView(brief.publicId(), brief.nickname(), brief.avatarUrl(),
                    roleInContext(row.role()), row.joinedAt()));
        }
        return views;
    }

    /**
     * 自己退出空间。
     *
     * <h2>为什么它与"移除成员"分开，而且不需要权限点</h2>
     * "退出"处置的是自己已经获得的成员关系，不是对别人的管理权 ——
     * 因此它在权限点这一层没有对应能力，控制器上也没有 {@code @PreAuthorize}。
     * 唯一的前置条件是"你确实是这个空间的成员"，而那由第二层回答。
     *
     * <p>若把它挂在 {@code workspace:member:remove} 上（那样代码更省事），
     * 将来任何一个"不能移除成员但应当能自己退出"的角色都会走不通，
     * 而修它的办法只能是再加一个权限点 —— 那时才发现这个能力位本来就不该存在。
     *
     * @param userId            当前用户自增主键
     * @param workspacePublicId 空间对外标识
     */
    @Transactional
    public void leave(long userId, String workspacePublicId) {
        Workspace workspace = requireByPublicId(workspacePublicId);
        WorkspaceRoleInContext myRole = authorization.roleIn(userId, workspace.id());
        if (myRole == WorkspaceRoleInContext.NONE) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (myRole == WorkspaceRoleInContext.OWNER) {
            // 允许拥有者退出会让空间变成"没有人能删除、没有人能管理成员"的孤儿，
            // 而库里没有任何字段能表达"这个空间无主"。
            throw new BusinessException(ErrorCode.WORKSPACE_OWNER_CANNOT_LEAVE);
        }
        if (memberMapper.delete(workspace.id(), userId) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        auditService.record(AuditAction.WORKSPACE_MEMBER_LEAVE, AuditResult.SUCCESS, userId,
                TARGET_MEMBER, String.valueOf(userId), Map.of("workspace", workspacePublicId));
    }

    /**
     * 移除空间成员（管理员或拥有者操作）。
     *
     * @param userId             当前用户自增主键
     * @param workspacePublicId  空间对外标识
     * @param targetUserPublicId 目标用户对外标识
     */
    @Transactional
    public void removeMember(long userId, String workspacePublicId, String targetUserPublicId) {
        Workspace workspace = requireWorkspace(userId, workspacePublicId, WorkspaceAction.REMOVE_MEMBER);
        UserBrief target = userDirectory.findBriefByPublicId(targetUserPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        if (memberMapper.delete(workspace.id(), target.userId()) != 1) {
            // 删 0 行表示这个人本来就不是成员 —— 包括"目标是拥有者"这种情况，
            // 因为拥有者不在成员表里。两种情形都不该被确认，一律 404。
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        auditService.record(AuditAction.WORKSPACE_MEMBER_REMOVE, AuditResult.SUCCESS, userId,
                TARGET_MEMBER, targetUserPublicId, Map.of("workspace", workspacePublicId));
    }

    /**
     * 修改成员在空间内的角色。
     *
     * @param userId             当前用户自增主键
     * @param workspacePublicId  空间对外标识
     * @param targetUserPublicId 目标用户对外标识
     * @param role               新角色
     */
    @Transactional
    public void updateMemberRole(long userId, String workspacePublicId, String targetUserPublicId,
                                 WorkspaceMemberRole role) {
        Workspace workspace = requireWorkspace(userId, workspacePublicId, WorkspaceAction.UPDATE_MEMBER_ROLE);
        UserBrief target = userDirectory.findBriefByPublicId(targetUserPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        if (target.userId() == workspace.ownerId()) {
            // 拥有者的角色由 owner_id 决定，不在这张表里，因此无法被"降级"或"提权"。
            // 直接 404 而不是 400：从这张表的角度看，这个人确实不是一名成员。
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        int affected = memberMapper.updateRole(workspace.id(), target.userId(), role);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        auditService.record(AuditAction.WORKSPACE_MEMBER_ROLE_UPDATE, AuditResult.SUCCESS, userId,
                TARGET_MEMBER, targetUserPublicId,
                Map.of("workspace", workspacePublicId, "role", role.name()));
    }

    // ------------------------------------------------------------------------
    // 邀请
    // ------------------------------------------------------------------------

    /**
     * 邀请一个用户加入空间。
     *
     * <h2>为什么邀请是定向的</h2>
     * 生成的邀请码只在被邀请人手里有效（兑换时按 {@code invitee_id} 定位）。
     * 公开邀请链接一旦泄漏到群聊或搜索引擎，就等于把"谁能进这个私有空间"的决定权
     * 交给了任何一个拿到链接的人。
     *
     * <p>返回的邀请对象里<b>带着邀请码</b>。这是它唯一一次出现在响应里：
     * 邀请人需要把它转达给被邀请人，而系统不代发消息（通知属于 Phase 10）。
     *
     * @param userId           发起邀请的成员自增主键
     * @param workspacePublicId 空间对外标识
     * @param inviteeUsername  被邀请人的登录名
     * @param role             兑换后获得的角色
     * @return 新建的邀请（含邀请码）与被邀请人的展示信息
     */
    @Transactional
    public InviteView invite(long userId, String workspacePublicId, String inviteeUsername,
                             WorkspaceMemberRole role) {
        Workspace workspace = requireWorkspace(userId, workspacePublicId, WorkspaceAction.INVITE_MEMBER);

        UserBrief invitee = userDirectory.findBriefByUsername(inviteeUsername)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (invitee.userId() == userId) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        if (authorization.roleIn(invitee.userId(), workspace.id()) != WorkspaceRoleInContext.NONE) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        if (isFull(workspace)) {
            throw new BusinessException(ErrorCode.WORKSPACE_MEMBER_LIMIT);
        }

        Instant expiresAt = clock.instant()
                .plus(properties.members().inviteValidHours(), ChronoUnit.HOURS);
        String code = RandomValues.publicId();
        inviteMapper.insert(new InviteDraft(code, workspace.id(), userId, invitee.userId(), role, expiresAt));

        // 审计里刻意**不**记录邀请码本身：它是进入私有空间的凭据，
        // 而审计日志的可见范围比空间成员更宽。记录"邀请了谁、给了什么角色"足够回答
        // "这个陌生成员是怎么进来的"，而码本身泄漏只会让审计表变成一份口令清单。
        auditService.record(AuditAction.WORKSPACE_MEMBER_INVITE, AuditResult.SUCCESS, userId,
                TARGET_INVITE, invitee.publicId(),
                Map.of("workspace", workspacePublicId, "role", role.name()));

        return new InviteView(inviteMapper.findRedeemable(code, invitee.userId())
                .orElseThrow(() -> new IllegalStateException("刚插入的邀请读不回来：" + code)),
                invitee);
    }

    /**
     * 列出空间内待处理的邀请。
     *
     * <p>顺带把被邀请人的展示信息一次批量换出来：待处理列表唯一要显示的就是
     * "我邀请了谁、给了他什么角色、还有多久过期"，没有前者这张表在界面上无法阅读。
     *
     * @param userId           当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @return 待处理邀请，按创建时间倒序
     */
    @Transactional(readOnly = true)
    public List<InviteView> pendingInvites(long userId, String workspacePublicId) {
        Workspace workspace = requireWorkspace(userId, workspacePublicId, WorkspaceAction.INVITE_MEMBER);
        List<WorkspaceInvite> invites = inviteMapper.findPending(workspace.id(), clock.instant(),
                properties.feed().maxPageSize());

        List<Long> inviteeIds = new ArrayList<>(invites.size());
        invites.forEach(invite -> inviteeIds.add(invite.inviteeId()));
        Map<Long, UserBrief> briefs = userDirectory.findBriefs(inviteeIds);

        return invites.stream()
                .map(invite -> new InviteView(invite, briefs.get(invite.inviteeId())))
                .toList();
    }

    /**
     * 撤销一条待处理邀请。
     *
     * @param userId           当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param code             邀请码
     */
    @Transactional
    public void revokeInvite(long userId, String workspacePublicId, String code) {
        Workspace workspace = requireWorkspace(userId, workspacePublicId, WorkspaceAction.INVITE_MEMBER);
        // 空间条件由语句带（见 WorkspaceInviteMapper#revoke）：这里判的是"我能不能管这个空间的邀请"，
        // 而语句判的是"这条邀请是不是这个空间的" —— 两者都要有，范围不同。
        if (inviteMapper.revoke(workspace.id(), code) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        auditService.record(AuditAction.WORKSPACE_INVITE_REVOKE, AuditResult.SUCCESS, userId,
                TARGET_INVITE, code, Map.of("workspace", workspacePublicId));
    }

    /**
     * 接受邀请加入空间。
     *
     * <h2>四种拒绝，三种响应码，都是刻意的</h2>
     * <ul>
     *   <li><b>码不存在 / 不是发给我的 / 已兑换 / 已撤销 → 404.</b>
     *       把它们合并成同一个码，意味着"这个码存在吗"这个问题从响应里读不出来。
     *       已兑换的码返回 404 而不是"你已经是成员"，也是同一个取舍：
     *       否则攻击者可以用自己的账号批量试码，靠响应区分"存在且用过"与"不存在"。</li>
     *   <li><b>码是我的、但已过期 → 40020.</b> 这一支必须与 404 分开，
     *       因为调用方已经证明了他就是受邀人（码在他手上），此时"过期了"
     *       是可操作的反馈（让邀请人重发），而"不存在"不是。</li>
     *   <li><b>我已经在这个空间里 → 409.</b> 这是"重复加入"，
     *       与邀请的有效性无关，因此给一个能让人看懂的状态码。</li>
     * </ul>
     *
     * @param userId 被邀请人自增主键
     * @param code   邀请码
     * @return 加入的空间
     */
    @Transactional
    public WorkspaceSummary acceptInvite(long userId, String code) {
        WorkspaceInvite invite = inviteMapper.findRedeemable(code, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Instant now = clock.instant();
        if (!invite.status().isRedeemableAt(invite.expiresAt(), now)) {
            throw new BusinessException(ErrorCode.INVITE_CODE_INVALID);
        }
        if (authorization.roleIn(userId, invite.workspaceId()) != WorkspaceRoleInContext.NONE) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        Workspace workspace = workspaceMapper.findById(invite.workspaceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (isFull(workspace)) {
            throw new BusinessException(ErrorCode.WORKSPACE_MEMBER_LIMIT);
        }

        // 条件更新承担"一次性"语义。两个并发请求同时兑换同一个码时，
        // 只有一个能把 PENDING 改走；另一个拿到 0 行，在这里被拒。
        // 若换成"先查状态再改"，两个请求都会读到 PENDING，最终写出两条成员关系 ——
        // 而成员表的唯一键只会挡下第二条，表现为一个 500 而不是一个明确的错误。
        if (inviteMapper.markAccepted(invite.id(), now) != 1) {
            throw new BusinessException(ErrorCode.INVITE_CODE_INVALID);
        }
        memberMapper.insert(workspace.id(), userId, invite.role());

        auditService.record(AuditAction.WORKSPACE_MEMBER_JOIN, AuditResult.SUCCESS, userId,
                TARGET_WORKSPACE, workspace.publicId(),
                Map.of("role", invite.role().name()));
        return new WorkspaceSummary(workspace, roleInContext(invite.role()));
    }

    // ------------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------------

    /**
     * 解析空间并完成第二层判定，同时把判定所依据的身份带出来。
     *
     * <p>这两步被绑在一个方法里，是为了让"拿到空间对象却没有判定"这种写法不容易出现：
     * 调用方必须显式说出它想做哪个动作。解析与判定分离的版本在评审里看起来更灵活，
     * 而灵活性在这里恰好是风险 —— 它允许"先解析、忘了判定"的代码通过评审。
     *
     * <p>它比 {@link #requireWorkspace} 多返回一个身份，而那个身份是判定过程中
     * <b>本来就算出来</b>的（{@code assertCan} 返回它）。因此"要身份"这件事
     * 不产生任何额外查询 —— 需要它的调用方（笔记、文档的删除按钮判定）直接用这个方法，
     * 而不是自己再调一次 {@code roleIn}。
     *
     * @param userId   当前用户自增主键
     * @param publicId 空间对外标识
     * @param action   打算执行的动作
     * @return 空间与调用者在其中的身份
     * @throws BusinessException 空间不可见时 40400；可见但无权限时 40300
     */
    private WorkspaceAccess requireAccess(long userId, String publicId, WorkspaceAction action) {
        Workspace workspace = requireByPublicId(publicId);
        WorkspaceRoleInContext role = authorization.assertCan(userId, workspace.id(), action);
        return new WorkspaceAccess(workspace, role);
    }

    /**
     * 解析空间并完成第二层判定，只要空间本身。
     *
     * <p>它是 {@link #requireAccess} 的一个投影，只用于那些确实不需要身份的调用点。
     * 两者共用同一次判定，不存在"多判一次"的问题。
     *
     * @param userId   当前用户自增主键
     * @param publicId 空间对外标识
     * @param action   打算执行的动作
     * @return 空间
     * @throws BusinessException 空间不可见时 40400；可见但无权限时 40300
     */
    private Workspace requireWorkspace(long userId, String publicId, WorkspaceAction action) {
        return requireAccess(userId, publicId, action).workspace();
    }

    /**
     * 按对外标识取空间，不做任何判定。
     *
     * <p>私有，且只应该被 {@link #requireWorkspace} 与 {@link #removeOrLeave} 使用 ——
     * 后者需要自己区分"我是成员"与"我有管理权"两种情况，因此必须先拿到身份再决定动作。
     *
     * @param publicId 空间对外标识
     * @return 空间
     * @throws BusinessException 不存在或已删除时 40400
     */
    private Workspace requireByPublicId(String publicId) {
        return workspaceMapper.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * 判断空间是否已达成员上限（不含拥有者）。
     *
     * <p>直接数成员行而不是维护一个计数列：见 {@code V5__workspace.sql} 的偏离说明 4。
     * 成员行数被 {@code max-members} 本身约束住，因此这个"数一下"的代价上界
     * 是配置里的那个数字，不是数据的规模。
     *
     * <p>用 {@code countMembers} 而不是 {@code findMembers}，是因为这条判断有一条调用路径
     * 来自<b>还没加入空间的人</b>（兑换邀请码）。那个人的授权集合里没有这个空间，
     * 而 {@code findMembers} 带着自动空间过滤 —— 它会数出 0，
     * 让上限在兑换路径上静默失效。理由的完整版本写在
     * {@code WorkspaceMemberMapper#countMembers} 上。
     *
     * @param workspace 空间
     * @return 是否已满
     */
    private boolean isFull(Workspace workspace) {
        int maxMembers = properties.members().maxMembers();
        long count = memberMapper.countMembers(workspace.id());
        if (count >= maxMembers) {
            log.info("空间成员数已达上限：workspaceId={} count={} limit={}",
                    workspace.id(), count, maxMembers);
            return true;
        }
        return false;
    }

    /**
     * 校验空间名称。
     *
     * @param name 原始名称
     * @return 去除首尾空白后的名称
     * @throws BusinessException 为空或超长时 40022
     */
    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        String trimmed = name.strip();
        if (trimmed.length() > properties.limits().maxNameLength()) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        return trimmed;
    }

    /**
     * 校验空间描述。
     *
     * @param description 原始描述，可为 null
     * @return 去除首尾空白后的描述；空串归一为 null
     * @throws BusinessException 超长时 40022
     */
    private String requireDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.strip();
        if (trimmed.length() > properties.limits().maxDescriptionLength()) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        return trimmed;
    }

    /**
     * 截断页大小到配置上限。
     *
     * @param size 客户端请求的页大小
     * @return 生效的页大小
     */
    private int effectivePageSize(int size) {
        int max = properties.feed().maxPageSize();
        if (size <= 0) {
            return properties.feed().defaultPageSize();
        }
        return Math.min(size, max);
    }

    /**
     * 计算调用者在某个空间里的身份，取数来自"本页已经拿到的空间行 + 一次批量查出的角色"。
     *
     * <p>它与 {@link AuthorizationService#roleIn} 的结论必须一致 ——
     * 那条路径每次都重新查一遍库。这里不查，是因为列表场景里空间行与角色都已在手上；
     * 两处唯一的区别是数据来源，规则本身只有一条：<b>拥有者优先于任何被授予的角色</b>。
     * 若哪天出现了"在成员表里也是 ADMIN、同时又是拥有者"的数据，这里与 roleIn 都会返回 OWNER ——
     * 但那份数据本身是错的（拥有者不该写入成员表），应当在数据层被拦住。
     *
     * @param workspace 空间行
     * @param userId    调用者自增主键
     * @param roles     调用者的成员角色（键为空间主键）
     * @return 身份
     */
    private WorkspaceRoleInContext roleOf(Workspace workspace,
                                          long userId,
                                          Map<Long, WorkspaceMemberRole> roles) {
        if (workspace.isOwnedBy(userId)) {
            return WorkspaceRoleInContext.OWNER;
        }
        WorkspaceMemberRole role = roles.get(workspace.id());
        return role == null ? WorkspaceRoleInContext.NONE : roleInContext(role);
    }

    /**
     * 把落库的角色换算成场景身份。
     *
     * @param role 成员角色
     * @return 对应的场景身份
     */
    private WorkspaceRoleInContext roleInContext(WorkspaceMemberRole role) {
        return role == WorkspaceMemberRole.ADMIN
                ? WorkspaceRoleInContext.ADMIN
                : WorkspaceRoleInContext.MEMBER;
    }

    /**
     * 供其它服务复用的"解析 + 判定 + 带出身份"，避免三个服务各写一遍。
     *
     * @param userId   当前用户自增主键
     * @param publicId 空间对外标识
     * @param action   打算执行的动作
     * @return 空间与调用者在其中的身份
     */
    WorkspaceAccess requireAccessFor(long userId, String publicId, WorkspaceAction action) {
        return requireAccess(userId, publicId, action);
    }

    /**
     * 供其它服务复用：按自增主键取空间（内部使用，不做判定）。
     *
     * @param workspaceId 空间自增主键
     * @return 空间
     */
    Optional<Workspace> findById(long workspaceId) {
        return workspaceMapper.findById(workspaceId);
    }
}
