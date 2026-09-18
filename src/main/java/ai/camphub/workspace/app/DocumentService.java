package ai.camphub.workspace.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.common.hash.Sha256;
import ai.camphub.common.random.RandomValues;
import ai.camphub.common.web.Page;
import ai.camphub.identity.app.UserDirectory;
import ai.camphub.identity.domain.UserBrief;
import ai.camphub.platform.audit.app.AuditService;
import ai.camphub.platform.audit.domain.AuditAction;
import ai.camphub.platform.audit.domain.AuditResult;
import ai.camphub.workspace.config.WorkspaceProperties;
import ai.camphub.workspace.domain.DocumentChunk;
import ai.camphub.workspace.domain.DocumentDraft;
import ai.camphub.workspace.domain.DocumentTaskType;
import ai.camphub.workspace.domain.Workspace;
import ai.camphub.workspace.domain.WorkspaceAction;
import ai.camphub.workspace.domain.WorkspaceDocument;
import ai.camphub.workspace.infrastructure.DocumentChunkMapper;
import ai.camphub.workspace.infrastructure.DocumentMapper;
import ai.camphub.workspace.infrastructure.DocumentTaskMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 空间文档的应用服务：元数据、字节与解析流水线的入口。
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
 * <h2>上传把"写字节"与"入队解析"放在同一个事务里</h2>
 * 元数据插入与任务入队是同一个本地事务，因此不会出现"文档存在但永远不会被解析"
 * 这种状态 —— 那是数据库队列相对 MQ 的主要优势，不用白不用。
 * <b>但字节写入不在事务里</b>（文件不在数据库中，无法参与回滚），
 * 因此插入失败时这里会尽力删掉刚写入的对象；这不是万无一失的，
 * 进程在这一步崩溃仍会留下孤儿文件（影响仅限于占用磁盘）。
 *
 * <h2>删除：标记 + 入队 + 尽力立即清理</h2>
 * 三件事，顺序是刻意的：
 * <ol>
 *   <li>先软删元数据行。反过来的话，一旦删字节成功、标记失败，
 *       库里就会留下一行"声称有内容、实际取不到"的文档。</li>
 *   <li>入队 {@code CLEANUP} 任务。它让"删字节"这件事变成可重试的 ——
 *       进程崩溃留下的半途状态会被自动补上，而不是消失在一次无人观察的崩溃里。</li>
 *   <li>尽力立即删字节并置空存储键，让磁盘空间马上释放。
 *       失败只记警告：任务会重试。这条"加速路径"让常见情况下用户感知不到延迟，
 *       而它的失败不影响正确性。</li>
 * </ol>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final String TARGET_DOCUMENT = "DOCUMENT";

    /** 文件名长度上限，与 {@code document.name VARCHAR(255)} 对齐。 */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final DocumentTaskMapper taskMapper;
    private final WorkspaceService workspaceService;
    private final AuthorizationService authorization;
    private final ObjectStorage objectStorage;
    private final DownloadLinkService downloadLinks;
    private final UserDirectory userDirectory;
    private final AuditService auditService;
    private final WorkspaceProperties properties;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param documentMapper   文档数据访问
     * @param chunkMapper      分块数据访问
     * @param taskMapper       任务队列访问
     * @param workspaceService 空间服务（复用"解析 + 判定"）
     * @param authorization    第二层防线
     * @param objectStorage    对象存储端口
     * @param downloadLinks    短期下载链接构造
     * @param userDirectory    用户展示信息
     * @param auditService     审计
     * @param properties       空间模块配置
     * @param clock            时钟
     */
    public DocumentService(DocumentMapper documentMapper,
                           DocumentChunkMapper chunkMapper,
                           DocumentTaskMapper taskMapper,
                           WorkspaceService workspaceService,
                           AuthorizationService authorization,
                           ObjectStorage objectStorage,
                           DownloadLinkService downloadLinks,
                           UserDirectory userDirectory,
                           AuditService auditService,
                           WorkspaceProperties properties,
                           Clock clock) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.taskMapper = taskMapper;
        this.workspaceService = workspaceService;
        this.authorization = authorization;
        this.objectStorage = objectStorage;
        this.downloadLinks = downloadLinks;
        this.userDirectory = userDirectory;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 上传文档：写入字节、登记元数据、入队解析。
     *
     * @param userId            上传者自增主键
     * @param workspacePublicId 空间对外标识
     * @param upload            上传内容
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
        MessageDigest digest = Sha256.newDigest();
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
            // delete 现在会在存储故障时抛异常，因此必须单独接住 ——
            // 否则它会盖掉真正的失败原因（插入失败）。
            try {
                objectStorage.delete(storageKey);
            } catch (RuntimeException cleanupFailure) {
                ex.addSuppressed(cleanupFailure);
            }
            throw ex;
        }

        WorkspaceDocument document = requireDocument(access.workspace().id(), publicId);
        // 与元数据插入同一个事务：要么"文档存在且会被解析"，要么两者都不成立。
        taskMapper.insertIfAbsent(document.id(), DocumentTaskType.PARSE,
                properties.worker().maxAttempts());

        auditService.record(AuditAction.DOCUMENT_UPLOAD, AuditResult.SUCCESS, userId,
                TARGET_DOCUMENT, publicId,
                Map.of("workspace", workspacePublicId, "size", String.valueOf(upload.sizeBytes()),
                        "type", contentType));
        // 上传者一定是上传者本人，因此这一条必然可删 —— 但仍然走同一个 assemble，
        // 而不是在这里硬编码 true：删除矩阵一旦调整（例如引入只读角色），
        // 硬编码的那个 true 不会跟着变，而它会表现为"按钮在、点了报 403"。
        return assemble(userId, document, access);
    }

    /**
     * 分页查询空间内文档。
     *
     * @param userId            当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param page              页码，从 1 开始
     * @param size              页大小
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
                .map(document -> assemble(userId, document, briefs.get(document.uploaderId()), access))
                .toList();
        return Page.of(items, effectivePage, effectiveSize, documentMapper.countByWorkspace(workspaceId));
    }

    /**
     * 查询文档元数据。
     *
     * @param userId            当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
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
     * @param userId            当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @return 下载内容
     */
    @Transactional(readOnly = true)
    public DocumentDownload download(long userId, String workspacePublicId, String docPublicId) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.DOWNLOAD_DOCUMENT);
        WorkspaceDocument document = requireStoredDocument(access.workspace().id(), docPublicId);

        InputStream content = objectStorage.open(document.storageKey());
        auditService.record(AuditAction.DOCUMENT_DOWNLOAD, AuditResult.SUCCESS, userId,
                TARGET_DOCUMENT, docPublicId, Map.of("workspace", workspacePublicId));
        return new DocumentDownload(document.name(), document.sizeBytes(), content);
    }

    /**
     * 构造一个短期下载链接。
     *
     * <h2>授权在这里完成，之后就不再检查</h2>
     * 返回的链接可以被任何持有它的人使用（这正是"短期直链"的定义）。
     * 因此<b>本方法就是权限判定发生的地方</b>：一旦链接签发出去，
     * 后面那个端点只验签、不认人。
     *
     * <p>链接的有效期由配置决定，默认 5 分钟 —— 它是"链接泄漏后的暴露窗口"。
     *
     * <h2>为什么单独记一条审计</h2>
     * 它与 {@link #download} 的审计语义不同：这里是"某人获准得到一个可转发的地址"，
     * 而不是"某人下载了一次"。当同一份文件在短时间内被大量下载时，
     * 前者能说明这些下载是同一个来源授权的，后者不能。
     *
     * @param userId            当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @return 下载链接
     */
    @Transactional(readOnly = true)
    public DownloadLink createDownloadLink(long userId, String workspacePublicId,
                                           String docPublicId) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.DOWNLOAD_DOCUMENT);
        WorkspaceDocument document = requireStoredDocument(access.workspace().id(), docPublicId);

        DownloadLink link = downloadLinks.create(document, workspacePublicId);
        auditService.record(AuditAction.DOCUMENT_DOWNLOAD_LINK, AuditResult.SUCCESS, userId,
                TARGET_DOCUMENT, docPublicId,
                Map.of("workspace", workspacePublicId, "direct", String.valueOf(link.direct())));
        return link;
    }

    /**
     * 触发重新解析。
     *
     * <h2>为什么重置任务而不是新建</h2>
     * 见 {@code DocumentTaskMapper#resetForRetry}：唯一键只允许同一文档存在一行解析任务。
     * 这里额外处理"任务行不存在"的情形 —— 理论上上传时一定入队过，
     * 但一条"任务不存在"的路径若直接报错，会让一个可以自愈的状态变成卡死。
     *
     * @param userId            当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @return 重置后的文档元数据
     */
    @Transactional
    public DocumentView retryParse(long userId, String workspacePublicId, String docPublicId) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.READ_DOCUMENTS);
        WorkspaceDocument document = requireStoredDocument(access.workspace().id(), docPublicId);
        authorization.assertCan(userId, access.workspace().id(),
                WorkspaceAction.RETRY_DOCUMENT_PARSE, document.uploaderId() == userId);

        if (taskMapper.resetForRetry(document.id(), DocumentTaskType.PARSE, clock.instant()) == 0) {
            // 任务不存在，或正处于"排队中/执行中"。前者补一条，后者不该被打断。
            taskMapper.insertIfAbsent(document.id(), DocumentTaskType.PARSE,
                    properties.worker().maxAttempts());
        } else {
            documentMapper.markAwaitingRetry(document.id());
        }

        auditService.record(AuditAction.DOCUMENT_REPARSE, AuditResult.SUCCESS, userId,
                TARGET_DOCUMENT, docPublicId, Map.of("workspace", workspacePublicId));
        return assemble(userId, requireDocument(access.workspace().id(), docPublicId), access);
    }

    /**
     * 分页读取文档的解析分块。
     *
     * <h2>它存在是为了让人能核对解析质量</h2>
     * 一份 PDF 抽出的是不是它该有的内容、标题路径是否合理，只有把分块拿出来看
     * 才能回答。没有这条查询，"解析成功"就只是一个没人验证过的状态位。
     *
     * @param userId            当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @param page              页码，从 1 开始
     * @param size              页大小
     * @return 分页的分块
     */
    @Transactional(readOnly = true)
    public Page<DocumentChunk> chunks(long userId, String workspacePublicId, String docPublicId,
                                      int page, int size) {
        WorkspaceAccess access = workspaceService.requireAccessFor(
                userId, workspacePublicId, WorkspaceAction.READ_DOCUMENTS);
        WorkspaceDocument document = requireDocument(access.workspace().id(), docPublicId);

        int effectiveSize = effectivePageSize(size);
        int effectivePage = Math.max(page, 1);
        long offset = (long) (effectivePage - 1) * effectiveSize;
        List<DocumentChunk> chunks =
                chunkMapper.findByDocument(document.id(), effectiveSize, offset);
        return Page.of(chunks, effectivePage, effectiveSize,
                chunkMapper.countByDocument(document.id()));
    }

    /**
     * 删除文档。上传者本人、或空间拥有者/管理员。
     *
     * @param userId            当前用户自增主键
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
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
        taskMapper.insertIfAbsent(document.id(), DocumentTaskType.CLEANUP,
                properties.worker().maxAttempts());

        // 加速路径：能立刻清掉就立刻清掉，让磁盘空间马上释放。
        // 失败只记警告 —— CLEANUP 任务会重试，正确性不依赖这一步。
        if (document.hasStoredContent()) {
            try {
                objectStorage.delete(document.storageKey());
                documentMapper.clearStorageKey(document.id());
            } catch (RuntimeException ex) {
                log.warn("立即清理字节失败，已交由清理任务重试：documentPublicId={}", docPublicId, ex);
            }
        }

        auditService.record(AuditAction.DOCUMENT_DELETE, AuditResult.SUCCESS, userId,
                TARGET_DOCUMENT, docPublicId, Map.of("workspace", workspacePublicId));
    }

    // ------------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------------

    /**
     * 把文档元数据与展示信息装成视图。
     *
     * <p>上传、列表、详情、重试四条路径共用它，理由与笔记侧的
     * {@code NoteService#assemble} 相同：四处分别组装过一遍之后，
     * 早晚会有一处漏掉一个"我能不能删/能不能重试"字段，
     * 而漏掉的表现是界面上的按钮与真实权限不一致。
     *
     * @param userId   当前用户自增主键
     * @param document 文档元数据
     * @param access   当前用户在该空间内的身份
     * @return 视图
     */
    private DocumentView assemble(long userId, WorkspaceDocument document, WorkspaceAccess access) {
        return assemble(userId, document, userDirectory.findBrief(document.uploaderId()).orElse(null),
                access);
    }

    /**
     * 装上已经取好的上传者信息，避免列表路径逐条查询用户。
     *
     * @param userId   当前用户自增主键
     * @param document 文档元数据
     * @param uploader 上传者展示信息，可为 null
     * @param access   当前用户在该空间内的身份
     * @return 视图
     */
    private DocumentView assemble(long userId, WorkspaceDocument document, UserBrief uploader,
                                  WorkspaceAccess access) {
        boolean ownedBySelf = document.uploaderId() == userId;
        return new DocumentView(document, uploader,
                access.canDelete(WorkspaceAction.DELETE_DOCUMENT, ownedBySelf),
                access.canDelete(WorkspaceAction.RETRY_DOCUMENT_PARSE, ownedBySelf));
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
     * 取文档并要求它仍有可读的字节。
     *
     * @param workspaceId 空间自增主键
     * @param publicId    文档对外标识
     * @return 元数据
     * @throws BusinessException 不存在时 40400；字节取不到时 50300
     */
    private WorkspaceDocument requireStoredDocument(long workspaceId, String publicId) {
        WorkspaceDocument document = requireDocument(workspaceId, publicId);
        if (!document.hasStoredContent()) {
            // 元数据在、字节不在。返回 503 而不是 404：资源确实存在、权限也确实有。
            // 现在的常见成因是"删除后字节已清"，但那类文档在查询时已经被排除，
            // 因此走到这里通常意味着存储被外部清理或历史行。
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return document;
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
     * <h2>白名单比"能解析的类型"更宽，这是有意的</h2>
     * 它回答的是"哪些内容允许被存下来"。压缩包、Office 文档、图片都能存能下载，
     * 只是当前没有解析器读得懂 —— 那些文档会以 FAILED +
     * "暂不支持解析这种类型的文件"收尾。这比反过来（拒绝存储）更诚实：
     * "存下来并可以下载"本身有价值，而"我们读不懂它"是一个能力边界，
     * 不是一次错误。
     *
     * <p>两个方向里只有一个是必须成立的：<b>能被读懂的必须能被上传</b>。
     * 反方向不成立也不该成立，否则白名单就成了"能解析什么"的副本。
     * 那条必须成立的方向由 {@code DocumentParsingRegistryTest} 在构建期断言，
     * 而不是靠改配置的人记得同时改另一处。
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
