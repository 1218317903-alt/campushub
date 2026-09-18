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
import ai.camphub.workspace.domain.DocumentDraft;
import ai.camphub.workspace.domain.Workspace;
import ai.camphub.workspace.domain.WorkspaceAction;
import ai.camphub.workspace.domain.WorkspaceDocument;
import ai.camphub.workspace.infrastructure.DocumentMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 空间文档的应用服务：元数据与字节的读写。
 *
 * <h2>三个字段全部来自用户，而它们都被用于某个决定</h2>
 * <ul>
 *   <li>{@code fileName} —— 会出现在下载响应的 {@code Content-Disposition} 头里，
 *       也用于展示。<b>绝不参与路径拼接</b>：存储键由服务端生成（文档的 public_id）。</li>
 *   <li>{@code contentType} —— 用户声明，因此只用于白名单放行，<b>不回显到响应</b>。
 *       下载时统一用 {@code application/octet-stream} + {@code attachment}：
 *       若把用户声明的类型回显成 {@code text/html} 并允许内联打开，
 *       上传一个 HTML 就等于在本站域的源下拿到一个 XSS。</li>
 *   <li>{@code sizeBytes} —— 用户声明，因此既用于上限校验，也用于和实际写入的字节数比对。
 *       声明值与实际值不一致时整次上传失败（不会留下一份被截断的"成品"）。</li>
 * </ul>
 *
 * <h2>删除的顺序是刻意的</h2>
 * 先标记元数据行删除，再删字节。反过来的话，一旦删字节成功、标记失败，
 * 库里就会留下一行"声称有内容、实际取不到"的文档 —— 而用户在界面上看到的是完整的元数据，
 * 直到点击下载才会发现内容没了。这个顺序下最坏的结果只是一份无人引用的孤儿文件，
 * 而孤儿文件是可被扫描清理的（Phase 05 会加上清理任务）。
 *
 * <h2>上传失败时的孤儿文件</h2>
 * 写字节与插入元数据不在同一个事务里（字节不在数据库里，无法参与回滚）。
 * 因此插入失败时这里会尽力删掉刚写入的对象。这不是万无一失的：
 * 进程在这一步崩溃仍会留下孤儿。彻底的方案是一个兜底的清理任务，
 * 属于 Phase 05 的范围 —— 在此之前，孤儿文件的影响仅限于占用磁盘。
 */
@Service
public class DocumentService {

    private static final String TARGET_DOCUMENT = "DOCUMENT";

    /** 文件名长度上限，与 {@code document.name VARCHAR(255)} 对齐。 */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    private final DocumentMapper documentMapper;
    private final WorkspaceService workspaceService;
    private final AuthorizationService authorization;
    private final ObjectStorage objectStorage;
    private final UserDirectory userDirectory;
    private final AuditService auditService;
    private final WorkspaceProperties properties;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param documentMapper   文档数据访问
     * @param workspaceService 空间服务（复用"解析 + 判定"）
     * @param authorization    第二层防线
     * @param objectStorage    对象存储端口
     * @param userDirectory    用户展示信息
     * @param auditService     审计
     * @param properties       空间模块配置
     * @param clock            时钟
     */
    public DocumentService(DocumentMapper documentMapper,
                           WorkspaceService workspaceService,
                           AuthorizationService authorization,
                           ObjectStorage objectStorage,
                           UserDirectory userDirectory,
                           AuditService auditService,
                           WorkspaceProperties properties,
                           Clock clock) {
        this.documentMapper = documentMapper;
        this.workspaceService = workspaceService;
        this.authorization = authorization;
        this.objectStorage = objectStorage;
        this.userDirectory = userDirectory;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 上传文档：写入字节、登记元数据。
     *
     * @param userId           上传者自增主键
     * @param workspacePublicId 空间对外标识
     * @param upload           上传内容
     * @return 新建文档的元数据
     */
    @Transactional
    public DocumentView upload(long userId, String workspacePublicId, DocumentUpload upload) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.UPLOAD_DOCUMENT);
        String fileName = requireFileName(upload.fileName());
        String contentType = requireAllowedContentType(upload.contentType());
        requireSize(upload.sizeBytes());

        String publicId = RandomValues.publicId();
        MessageDigest digest = newSha256();
        String storageKey;
        try (InputStream content = new DigestInputStream(upload.content(), digest)) {
            storageKey = objectStorage.store(publicId, content, upload.sizeBytes());
        } catch (IOException ex) {
            throw new UncheckedIOException("读取上传内容失败", ex);
        }

        DocumentDraft draft = new DocumentDraft(publicId, access.workspace().id(), userId, fileName,
                contentType, upload.sizeBytes(), storageKey, HexFormat.of().formatHex(digest.digest()));
        try {
            documentMapper.insert(draft);
        } catch (RuntimeException ex) {
            // 尽力清理刚写入的字节，避免元数据插入失败留下孤儿。
            // 这里吞掉清理自身的异常：真正的失败原因是插入，报告它才有意义。
            objectStorage.delete(storageKey);
            throw ex;
        }

        auditService.record(AuditAction.DOCUMENT_UPLOAD, AuditResult.SUCCESS, userId,
                TARGET_DOCUMENT, publicId,
                Map.of("workspace", workspacePublicId, "size", String.valueOf(upload.sizeBytes()),
                        "type", contentType));
        // 上传者一定是上传者本人，因此这一条必然可删 —— 但仍然走同一个 assemble，
        // 而不是在这里硬编码 true：删除矩阵一旦调整（例如引入只读角色），
        // 硬编码的那个 true 不会跟着变，而它会表现为"按钮在、点了报 403"。
        return assemble(userId, requireDocument(access.workspace().id(), publicId), access);
    }

    /**
     * 分页查询空间内文档。
     *
     * @param userId           当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param page             页码，从 1 开始
     * @param size             页大小
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public Page<DocumentView> list(long userId, String workspacePublicId, int page, int size) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.READ_DOCUMENTS);
        long workspaceId = access.workspace().id();

        int effectiveSize = effectivePageSize(size);
        int effectivePage = Math.max(page, 1);
        long offset = (long) (effectivePage - 1) * effectiveSize;
        List<WorkspaceDocument> documents =
                documentMapper.findByWorkspace(workspaceId, effectiveSize, offset);

        List<Long> uploaderIds = new ArrayList<>(documents.size());
        documents.forEach(document -> uploaderIds.add(document.uploaderId()));
        Map<Long, UserBrief> briefs = userDirectory.findBriefs(uploaderIds);

        List<DocumentView> items = documents.stream()
                .map(document -> new DocumentView(document, briefs.get(document.uploaderId()),
                        access.canDelete(WorkspaceAction.DELETE_DOCUMENT, document.uploaderId() == userId)))
                .toList();
        return Page.of(items, effectivePage, effectiveSize, documentMapper.countByWorkspace(workspaceId));
    }

    /**
     * 查询文档元数据。
     *
     * @param userId           当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param docPublicId      文档对外标识
     * @return 元数据
     */
    @Transactional(readOnly = true)
    public DocumentView detail(long userId, String workspacePublicId, String docPublicId) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.READ_DOCUMENTS);
        return assemble(userId, requireDocument(access.workspace().id(), docPublicId), access);
    }

    /**
     * 下载原文件。
     *
     * <p>每一次成功的下载都写审计 —— 这是本阶段唯一被审计的读取动作，
     * 因为它把文件内容交到了请求方手上，而数据外带的事后排查只能从这条记录开始。
     *
     * @param userId           当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param docPublicId      文档对外标识
     * @return 下载内容
     */
    @Transactional(readOnly = true)
    public DocumentDownload download(long userId, String workspacePublicId, String docPublicId) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.DOWNLOAD_DOCUMENT);
        WorkspaceDocument document = requireDocument(access.workspace().id(), docPublicId);
        if (!document.hasStoredContent()) {
            // 元数据在、字节不在。这一支留给迁移过来的历史行（字节接入之前登记的元数据），
            // 返回 503 而不是 404：资源确实存在、权限也确实有。
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }

        InputStream content = objectStorage.open(document.storageKey());
        auditService.record(AuditAction.DOCUMENT_DOWNLOAD, AuditResult.SUCCESS, userId,
                TARGET_DOCUMENT, docPublicId, Map.of("workspace", workspacePublicId));
        return new DocumentDownload(document.name(), document.sizeBytes(), content);
    }

    /**
     * 删除文档。上传者本人、或空间拥有者/管理员。
     *
     * <p>元数据行的删除与字节的删除不在同一个事务里，顺序见类注释。
     *
     * @param userId           当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param docPublicId      文档对外标识
     */
    @Transactional
    public void delete(long userId, String workspacePublicId, String docPublicId) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.READ_DOCUMENTS);
        WorkspaceDocument document = requireDocument(access.workspace().id(), docPublicId);
        authorization.assertCan(userId, access.workspace().id(), WorkspaceAction.DELETE_DOCUMENT,
                document.uploaderId() == userId);

        if (documentMapper.softDelete(document.id(), clock.instant()) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        objectStorage.delete(document.storageKey());

        auditService.record(AuditAction.DOCUMENT_DELETE, AuditResult.SUCCESS, userId,
                TARGET_DOCUMENT, docPublicId, Map.of("workspace", workspacePublicId));
    }

    // ------------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------------

    /**
     * 把文档元数据与展示信息装成视图。
     *
     * <p>上传、列表、详情三条路径共用它，理由与笔记侧的 {@code NoteService#assemble} 相同：
     * 三处分别组装过一遍之后，早晚会有一处漏掉"我能不能删"这个字段，
     * 而漏掉的表现是界面上的按钮与真实权限不一致。
     *
     * @param userId   当前用户自增主键
     * @param document 文档元数据
     * @param access   当前用户在该空间内的身份
     * @return 视图
     */
    private DocumentView assemble(long userId, WorkspaceDocument document, WorkspaceAccess access) {
        return new DocumentView(document,
                userDirectory.findBrief(document.uploaderId()).orElse(null),
                access.canDelete(WorkspaceAction.DELETE_DOCUMENT, document.uploaderId() == userId));
    }

    /**
     * 按 (空间, 对外标识) 取文档，取不到按不存在处理。
     *
     * @param workspaceId 空间自增主键
     * @param publicId    文档对外标识
     * @return 元数据
     * @throws BusinessException 不存在或已删除时 40400
     */
    private WorkspaceDocument requireDocument(long workspaceId, String publicId) {
        return documentMapper.findByPublicId(workspaceId, publicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * 净化文件名。
     *
     * <h2>为什么只取最后一段并剥掉控制字符</h2>
     * 有三类字符在这里是危险的，而它们分别对应一种攻击：
     * <ul>
     *   <li><b>路径分隔符</b>（{@code /} 与 {@code \}）：某些浏览器会把整个
     *       {@code C:\Users\x\secret.txt} 当文件名发上来。只保留最后一段之后，
     *       用户看到的是 {@code secret.txt}，而不是自己的目录结构。</li>
     *   <li><b>控制字符</b>（{@code \r} {@code \n}）：它们出现在
     *       {@code Content-Disposition} 头里就是响应头注入 —— 可以在同一个响应里
     *       塞进任意头部，包括 {@code Set-Cookie}。这里直接把它们剥掉，
     *       而不是依赖框架去转义（框架的 URL 编码只覆盖非 ASCII，不覆盖 CR/LF）。</li>
     *   <li><b>长度</b>：超过列宽会插入失败，且失败信息里带着用户输入的内容。</li>
     * </ul>
     *
     * @param raw 原始文件名
     * @return 净化后的文件名
     * @throws BusinessException 为空、只剩点号、或超长时 40022
     */
    private String requireFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        String normalized = raw.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String base = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;

        StringBuilder cleaned = new StringBuilder(base.length());
        base.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(cleaned::appendCodePoint);
        String result = cleaned.toString().strip();

        if (result.isEmpty() || result.equals(".") || result.equals("..")) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        if (result.length() > MAX_FILE_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        return result;
    }

    /**
     * 校验声明的内容类型是否在白名单内。
     *
     * <p>白名单匹配<b>忽略参数与大小写</b>：{@code text/markdown; charset=utf-8}
     * 与 {@code TEXT/MARKDOWN} 都是同一个类型。按原始字符串精确匹配会让一批
     * 正常的上传被拒，而作者会倾向于"把白名单放宽"而不是"去解析参数" ——
     * 于是安全边界在一次修复体验问题的改动里被削弱。
     *
     * @param raw 客户端声明的类型
     * @return 归一化（小写、去掉参数）后的类型
     * @throws BusinessException 不在白名单内时 40022
     */
    private String requireAllowedContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        String normalized = raw.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        boolean allowed = properties.documents().allowedTypes().stream()
                .map(candidate -> candidate.strip().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
        if (!allowed) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
        return normalized;
    }

    /**
     * 校验声明的大小。
     *
     * @param sizeBytes 声明字节数
     * @throws BusinessException 为 0、负数或超上限时 40022
     */
    private void requireSize(long sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes > properties.documents().maxSizeBytes()) {
            throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
        }
    }

    /**
     * 构造 SHA-256 摘要器。
     *
     * @return 摘要器
     */
    private MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须支持的算法。走到这里说明运行环境被裁剪过，
            // 那是部署问题而不是输入问题，因此是 500 而不是 400。
            throw new IllegalStateException("运行环境不支持 SHA-256", ex);
        }
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
