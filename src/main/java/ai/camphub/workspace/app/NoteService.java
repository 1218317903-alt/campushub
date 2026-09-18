package ai.camphub.workspace.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.common.rendering.MarkdownRenderer;
import ai.camphub.common.random.RandomValues;
import ai.camphub.common.web.Page;
import ai.camphub.identity.app.UserDirectory;
import ai.camphub.identity.domain.UserBrief;
import ai.camphub.platform.audit.app.AuditService;
import ai.camphub.platform.audit.domain.AuditAction;
import ai.camphub.platform.audit.domain.AuditResult;
import ai.camphub.workspace.config.WorkspaceProperties;
import ai.camphub.workspace.domain.NoteDetail;
import ai.camphub.workspace.domain.NoteDraft;
import ai.camphub.workspace.domain.NoteSummary;
import ai.camphub.workspace.domain.Workspace;
import ai.camphub.workspace.domain.WorkspaceAction;
import ai.camphub.workspace.infrastructure.NoteMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 空间内协作笔记的应用服务。
 *
 * <h2>渲染边界与社区完全一致</h2>
 * 写入时把 Markdown 渲染并净化一次，{@code body_md} 与 {@code body_html} 一起落库
 * （ADR 0005）。复用的是同一个 {@link MarkdownRenderer} Bean ——
 * 而不是在空间模块里再拼一遍 HTML。两套渲染实现意味着两套净化白名单，
 * 而它们不可能长期一致，差异会以 XSS 的形式暴露出来。
 *
 * <h2>为什么这里没有 {@code requireOwnedBy}</h2>
 * 社区帖子的编辑权只属于作者，因此 {@code PostService} 有一处"非作者一律 404"。
 * 笔记是协作内容：任何成员都能编辑（见 {@link WorkspaceAction#UPDATE_NOTE} 的注释），
 * 因此编辑路径只判定"你是不是成员"，不判定"这是不是你写的"。
 * 只有删除区分归属 —— 那一条由 {@link WorkspaceAction#allows(ai.camphub.workspace.domain.WorkspaceRoleInContext, boolean)}
 * 的关联维度承担，而不是靠这里写一个 if。
 */
@Service
public class NoteService {

    private static final String TARGET_NOTE = "NOTE";

    private final NoteMapper noteMapper;
    private final WorkspaceService workspaceService;
    private final AuthorizationService authorization;
    private final MarkdownRenderer markdownRenderer;
    private final UserDirectory userDirectory;
    private final AuditService auditService;
    private final WorkspaceProperties properties;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param noteMapper       笔记数据访问
     * @param workspaceService 空间服务（复用"解析 + 判定"）
     * @param authorization    第二层防线（删除时需要归属维度）
     * @param markdownRenderer 内容渲染边界
     * @param userDirectory    用户展示信息
     * @param auditService     审计
     * @param properties       空间模块配置
     * @param clock            时钟
     */
    public NoteService(NoteMapper noteMapper,
                       WorkspaceService workspaceService,
                       AuthorizationService authorization,
                       MarkdownRenderer markdownRenderer,
                       UserDirectory userDirectory,
                       AuditService auditService,
                       WorkspaceProperties properties,
                       Clock clock) {
        this.noteMapper = noteMapper;
        this.workspaceService = workspaceService;
        this.authorization = authorization;
        this.markdownRenderer = markdownRenderer;
        this.userDirectory = userDirectory;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 创建笔记。
     *
     * @param userId           作者自增主键
     * @param workspacePublicId 空间对外标识
     * @param title            标题
     * @param bodyMd           Markdown 正文
     * @return 新建笔记的详情
     */
    @Transactional
    public NoteDetailView create(long userId, String workspacePublicId, String title, String bodyMd) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.CREATE_NOTE);
        String safeTitle = requireTitle(title);
        String safeBody = requireBody(bodyMd);

        String publicId = RandomValues.publicId();
        NoteDraft draft = new NoteDraft(
                publicId,
                access.workspace().id(),
                userId,
                userId,
                safeTitle,
                markdownRenderer.summarize(safeBody, properties.notes().summaryLength()),
                safeBody,
                markdownRenderer.render(safeBody));
        noteMapper.insert(draft);

        auditService.record(AuditAction.NOTE_CREATE, AuditResult.SUCCESS, userId,
                TARGET_NOTE, publicId, Map.of("workspace", workspacePublicId));
        return detail(userId, workspacePublicId, publicId);
    }

    /**
     * 分页查询空间内笔记。
     *
     * @param userId           当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param page             页码，从 1 开始
     * @param size             页大小
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public Page<NoteCardView> list(long userId, String workspacePublicId, int page, int size) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.READ_NOTES);
        long workspaceId = access.workspace().id();

        int effectiveSize = effectivePageSize(size);
        // 页码下限归一到 1：客户端传 0 或负数时不去纠正会让 OFFSET 变成负数，
        // 而 MySQL 对负 OFFSET 是语法错误 —— 表现为一个 500 而不是一个空页。
        int effectivePage = Math.max(page, 1);
        long offset = (long) (effectivePage - 1) * effectiveSize;
        List<NoteSummary> notes = noteMapper.findSummaries(workspaceId, effectiveSize, offset);

        // 作者与编辑者一起去重后批量换昵称：不去重的话，一个用户改了 20 条笔记
        // 会让同一份用户信息被查 20 次 —— 批量接口的收益正好被这一步抵消掉。
        List<Long> userIds = new ArrayList<>(notes.size() * 2);
        notes.forEach(note -> {
            userIds.add(note.authorId());
            userIds.add(note.updatedBy());
        });
        Map<Long, UserBrief> briefs = userDirectory.findBriefs(userIds);

        // 删除权在同一页里是同一个"场景身份"算出来的，因此每行只差"是不是我写的"。
        // 把它算在这里而不是让前端猜：管理员与拥有者本来就能删别人的笔记，
        // 而前端从列表数据里看不出调用者在这个空间里的身份。
        List<NoteCardView> items = notes.stream()
                .map(note -> new NoteCardView(note,
                        briefs.get(note.authorId()), briefs.get(note.updatedBy()),
                        access.canDelete(WorkspaceAction.DELETE_NOTE, note.authorId() == userId)))
                .toList();
        return Page.of(items, effectivePage, effectiveSize, noteMapper.countByWorkspace(workspaceId));
    }

    /**
     * 查询笔记详情。
     *
     * @param userId           当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param notePublicId     笔记对外标识
     * @return 详情
     */
    @Transactional(readOnly = true)
    public NoteDetailView detail(long userId, String workspacePublicId, String notePublicId) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.READ_NOTES);
        NoteDetail note = requireNote(access.workspace().id(), notePublicId);
        return assemble(userId, note, access);
    }

    /**
     * 编辑笔记。任何成员都可以编辑（协作内容）。
     *
     * <p>{@code author_id} 不会被改动 —— 更新语句里根本没有这一列（见 {@code NoteMapper.xml}）。
     * 这是把"不能把别人的笔记过户到自己名下"做成 SQL 层的性质，
     * 而不是靠这里记得不要去改它。
     *
     * @param userId           编辑者自增主键
     * @param workspacePublicId 空间对外标识
     * @param notePublicId     笔记对外标识
     * @param title            新标题
     * @param bodyMd           新正文
     * @return 更新后的详情
     */
    @Transactional
    public NoteDetailView update(long userId, String workspacePublicId, String notePublicId,
                                 String title, String bodyMd) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.UPDATE_NOTE);
        requireNote(access.workspace().id(), notePublicId);

        String safeTitle = requireTitle(title);
        String safeBody = requireBody(bodyMd);
        NoteDraft draft = new NoteDraft(
                notePublicId,
                access.workspace().id(),
                userId,
                userId,
                safeTitle,
                markdownRenderer.summarize(safeBody, properties.notes().summaryLength()),
                safeBody,
                markdownRenderer.render(safeBody));
        // 影响 0 行说明笔记在这两次查询之间被删掉了。返回 404 而不是当成功：
        // "更新成功"却什么都没变，会让人以为自己的修改生效了。
        if (noteMapper.update(draft) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        auditService.record(AuditAction.NOTE_UPDATE, AuditResult.SUCCESS, userId,
                TARGET_NOTE, notePublicId, Map.of("workspace", workspacePublicId));
        // 复用已经算好的 access，而不是回头调 detail()：后者会把"解析 + 判定"
        // 整条链路再跑一遍，而这次判定与刚才针对同一个空间的那一次必然同结论。
        return assemble(userId, requireNote(access.workspace().id(), notePublicId), access);
    }

    /**
     * 删除笔记。成员只能删自己创建的；拥有者与管理员可以删任何一条。
     *
     * @param userId           当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param notePublicId     笔记对外标识
     */
    @Transactional
    public void delete(long userId, String workspacePublicId, String notePublicId) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.READ_NOTES);
        NoteDetail note = requireNote(access.workspace().id(), notePublicId);

        // 先把归属算出来，再交给判定 —— 顺序不能反：判定需要知道这条笔记是谁的。
        // 这里用 READ_NOTES 取得空间可见性（否则连"存不存在"都不该告诉调用方），
        // 再用带归属维度的 DELETE_NOTE 判定真正的权限。若调用方看不到空间，
        // requireNote 那一步已经抛了 404，走不到这里。
        authorization.assertCan(userId, access.workspace().id(), WorkspaceAction.DELETE_NOTE,
                note.authorId() == userId);

        if (noteMapper.softDelete(note.id(), clock.instant()) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        auditService.record(AuditAction.NOTE_DELETE, AuditResult.SUCCESS, userId,
                TARGET_NOTE, notePublicId, Map.of("workspace", workspacePublicId));
    }

    /**
     * 把笔记内容与展示信息装成视图。
     *
     * <p>创建、编辑、详情三条路径都走这里：它们要展示的东西完全一样，
     * 而"作者与编辑者是哪两个人、我能不能删"这三件事在三条路径上分别实现过一遍，
     * 早晚会有一条漏掉某个字段 —— 而漏掉的那个字段在界面上表现得像权限异常。
     *
     * @param userId 当前用户自增主键
     * @param note   笔记详情
     * @param access 当前用户在该空间内的身份
     * @return 详情视图
     */
    private NoteDetailView assemble(long userId, NoteDetail note, WorkspaceAccess access) {
        Map<Long, UserBrief> briefs = userDirectory.findBriefs(List.of(note.authorId(), note.updatedBy()));
        return new NoteDetailView(note, briefs.get(note.authorId()), briefs.get(note.updatedBy()),
                access.canDelete(WorkspaceAction.DELETE_NOTE, note.authorId() == userId));
    }

    /**
     * 按 (空间, 对外标识) 取笔记，取不到按不存在处理。
     *
     * @param workspaceId 空间自增主键
     * @param publicId    笔记对外标识
     * @return 笔记详情
     * @throws BusinessException 不存在或已删除时 40400
     */
    private NoteDetail requireNote(long workspaceId, String publicId) {
        return noteMapper.findDetail(workspaceId, publicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * 校验标题。
     *
     * @param title 原始标题
     * @return 去除首尾空白后的标题
     * @throws BusinessException 为空或超长时 40022
     */
    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        String trimmed = title.strip();
        if (trimmed.length() > properties.notes().maxTitleLength()) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        return trimmed;
    }

    /**
     * 校验正文长度。
     *
     * <p>按<b>码点</b>而不是 Java 字符计长：一个 emoji 在 Java 里占两个 {@code char}，
     * 按字符计会让"看起来没超"的中文 emoji 混排内容被拒。社区模块的正文校验
     * 用的是同一个口径，两处必须一致 —— 否则同一段内容在帖子里能发、在笔记里不能发。
     *
     * @param bodyMd 原始正文
     * @return 去除首尾空白后的正文
     * @throws BusinessException 为空或超长时 40022
     */
    private String requireBody(String bodyMd) {
        if (bodyMd == null || bodyMd.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        String trimmed = bodyMd.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > properties.notes().maxBodyLength()) {
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
}
