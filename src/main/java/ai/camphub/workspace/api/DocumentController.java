package ai.camphub.workspace.api;

import ai.camphub.common.web.PageResponse;
import ai.camphub.identity.domain.UserPrincipal;
import ai.camphub.workspace.app.DocumentDownload;
import ai.camphub.workspace.app.DocumentService;
import ai.camphub.workspace.app.DocumentUpload;
import ai.camphub.workspace.app.DocumentView;
import ai.camphub.workspace.app.DownloadLink;
import ai.camphub.workspace.config.WorkspaceProperties;
import ai.camphub.workspace.domain.DocumentChunk;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 空间文档接口：上传、列表、元数据、下载、下载链接、分块、重试解析、删除。
 *
 * <h2>上传用 multipart，而不是 base64 放进 JSON</h2>
 * base64 会把体积撑大约 1/3，并且要求服务端先把整份内容解码进内存才能开始处理 ——
 * 而这两个后果都落在同一个地方：内存。multipart 让内容以流的形式到达，
 * 落盘时以固定大小的缓冲区流过，内存占用与文件大小无关。
 *
 * <h2>下载为什么返回流而不是字节数组</h2>
 * 上限是配置里的几十 MiB 量级。把整份读进堆内存意味着并发几个下载就能把应用推近 OOM ——
 * 而这是一个<b>只读接口</b>，看起来毫无风险，因此最容易在容量规划里被忽略。
 * {@link InputStreamResource} 让 Spring 分块写出，内存占用与文件大小无关。
 *
 * <h2>下载响应的两个头都是安全决定，不是格式选择</h2>
 * <ul>
 *   <li><b>{@code Content-Type: application/octet-stream}</b>，<b>不回显</b>用户声明的类型。
 *       若把 {@code text/html} 原样回显并允许浏览器内联打开，上传一个 HTML
 *       就等于在本站域的源下拿到一个 XSS —— 内容会被当作本站的页面执行。</li>
 *   <li><b>{@code Content-Disposition: attachment}</b>，且文件名用 RFC 5987 编码
 *       （{@code filename*=UTF-8''...}）。中文文件名必须走 {@code filename*}
 *       才能被浏览器正确解码；而如果直接把原始文件名拼进头部，
 *       其中的 CR/LF 就是一次响应头注入。文件名在服务层已经被剥掉控制字符，
 *       这里再用框架的构造器编码一次 —— 两道处理防的不是同一件事。</li>
 * </ul>
 *
 * <h2>两条下载路径并存，各有各的理由</h2>
 * {@code /content} 走浏览器流式下载：请求带令牌、经过完整三层防线。
 * {@code /download-link} 返回一个短期有效的地址，由
 * {@code DocumentDownloadController} 兑换 —— 它存在的理由是
 * S3 后端能直接给出预签名直链（<b>字节不经过应用</b>），
 * 而本地后端用一枚有时限的签名令牌把同一件事做出来。
 * 两条路径的相同点是：<b>权限判定都发生在返回内容之前</b>。
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspacePublicId}/documents")
@Tag(name = "Workspace · Documents", description = "空间文档：上传、列表、下载、解析与删除")
public class DocumentController {

    /** 上传表单里文件字段的名字。写死成常量，接口文档与前端共用这一个事实。 */
    private static final String FILE_PART = "file";

    private final DocumentService documentService;
    private final WorkspaceProperties properties;

    /**
     * 构造注入。
     *
     * @param documentService 文档服务
     * @param properties      空间模块配置（分页默认值与下载行为）
     */
    public DocumentController(DocumentService documentService, WorkspaceProperties properties) {
        this.documentService = documentService;
        this.properties = properties;
    }

    /**
     * 上传文档。
     *
     * <h2>为什么用 {@code getOriginalFilename} 与 {@code getContentType} 而不是只看 {@code getSize}</h2>
     * 这三个值全部来自客户端，因此在服务层都被当作不可信输入处理：
     * 文件名会被净化（只取最后一段并剥掉控制字符），内容类型只用于白名单放行，
     * 字节数既用于上限校验也用于与<b>实际写入的字节数</b>比对。
     * 接口层不做任何判断，只负责把它们原样交给服务层 ——
     * 在这一层做半套校验，只会让"到底谁在负责"变得模糊。
     *
     * @param workspacePublicId 空间对外标识
     * @param principal         当前用户
     * @param file              上传的文件
     * @return 新建文档的元数据
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('document:upload')")
    @Operation(summary = "上传文档",
            description = "multipart/form-data，文件字段名为 file。字节写入存储后端后登记元数据，"
                    + "并在同一个事务里入队解析任务；内容类型必须在白名单内，否则 40022。"
                    + "同样的内容重复上传不会去重 —— 秒传依赖内容哈希与引用计数，属于后续阶段。")
    public DocumentResponse upload(@PathVariable("workspacePublicId") String workspacePublicId,
                                   @AuthenticationPrincipal UserPrincipal principal,
                                   @RequestPart(FILE_PART) MultipartFile file) {
        return DocumentResponse.from(documentService.upload(principal.userId(), workspacePublicId,
                toUpload(file)));
    }

    /**
     * 分页查询空间内文档。
     *
     * @param workspacePublicId 空间对外标识
     * @param page              页码，从 1 开始
     * @param size              页大小，省略时用配置的默认值
     * @param principal         当前用户
     * @return 分页结果，按上传时间倒序
     */
    @GetMapping
    @PreAuthorize("hasAuthority('document:read')")
    @Operation(summary = "文档列表",
            description = "按上传时间倒序，不含已删除的文档。响应不含 storageKey 与 sha256。")
    public PageResponse<DocumentResponse> list(
            @PathVariable("workspacePublicId") String workspacePublicId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return PageResponse.from(
                documentService.list(principal.userId(), workspacePublicId, page, effectiveSize(size)),
                DocumentResponse::from);
    }

    /**
     * 查询文档元数据。
     *
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @param principal         当前用户
     * @return 元数据
     */
    @GetMapping("/{docPublicId}")
    @PreAuthorize("hasAuthority('document:read')")
    @Operation(summary = "文档元数据",
            description = "只返回元数据，不返回内容；内容走 /content，解析结果走 /chunks。")
    public DocumentResponse detail(@PathVariable("workspacePublicId") String workspacePublicId,
                                   @PathVariable("docPublicId") String docPublicId,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return DocumentResponse.from(
                documentService.detail(principal.userId(), workspacePublicId, docPublicId));
    }

    /**
     * 下载原文件。
     *
     * <p>每一次成功下载都会写审计 —— 这是本阶段唯一被审计的读取动作，
     * 因为它把文件内容交到了请求方手上，而数据外带类事件的事后排查只能从这条记录开始。
     *
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @param principal         当前用户
     * @return 内容流
     */
    @GetMapping("/{docPublicId}/content")
    @PreAuthorize("hasAuthority('document:download')")
    @Operation(summary = "下载文档",
            description = "返回文件原内容。Content-Type 恒为 application/octet-stream，"
                    + "Content-Disposition 默认 attachment —— 回显用户声明的类型并允许内联，"
                    + "等于上传一个 HTML 就得到一次 XSS。")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable("workspacePublicId") String workspacePublicId,
            @PathVariable("docPublicId") String docPublicId,
            @AuthenticationPrincipal UserPrincipal principal) {

        DocumentDownload download = documentService.download(
                principal.userId(), workspacePublicId, docPublicId);

        // 文件名在服务层已剥掉控制字符；这里再用 RFC 5987 编码一次。
        // 两道处理防的不是同一件事：前者防响应头注入，后者让中文名能被浏览器正确解码。
        ContentDisposition disposition = (properties.documents().downloadInline()
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                // 显式声明长度，客户端才能显示进度；同时让"读取中途失败"表现为截断而不是静默的短响应。
                .contentLength(download.sizeBytes())
                .body(new InputStreamResource(download.content()));
    }

    /**
     * 生成一个短期下载链接。
     *
     * <h2>它返回的地址可以被转发，这一点是设计的一部分</h2>
     * 链接与当前登录凭据无关，因此可以贴进聊天窗口、写进脚本、或者在另一个标签页打开 ——
     * 这正是"直链"的用途。安全边界由三件事划定：签发前的完整授权判定、
     * 有效期（默认 5 分钟）、以及对内容的签名。
     *
     * <p>响应里的 {@code direct} 告诉客户端这是不是一条<b>绕过应用</b>的对象存储直链：
     * 为 true 时字节直接从存储返回，因此这条链接不会出现在应用日志里，
     * 也不会因为应用重启而失效（它由存储端校验）。前端不需要据此改变行为，
     * 但这个区别在排查"为什么下载很快/很慢"时必须可见。
     *
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @param principal         当前用户
     * @return 下载链接
     */
    @PostMapping("/{docPublicId}/download-link")
    @PreAuthorize("hasAuthority('document:download')")
    @Operation(summary = "生成短期下载链接",
            description = "返回一个带时限、可转发的下载地址。令牌本身即凭据，"
                    + "因此它只在签发时做权限判定 —— 有效期由配置决定，默认 5 分钟。")
    public DownloadLinkResponse createDownloadLink(
            @PathVariable("workspacePublicId") String workspacePublicId,
            @PathVariable("docPublicId") String docPublicId,
            @AuthenticationPrincipal UserPrincipal principal) {
        DownloadLink link = documentService.createDownloadLink(
                principal.userId(), workspacePublicId, docPublicId);
        return DownloadLinkResponse.from(link);
    }

    /**
     * 分页读取解析分块。
     *
     * <h2>为什么把它做成公开接口，而不是只留在库里</h2>
     * 解析质量是这个阶段唯一需要"看"才能判断的东西：PDF 抽出来的分块顺序对不对、
     * 标题路径合不合理、有没有把一整份文档切成一个巨大的块 ——
     * 这些都没有办法从状态位读出来。没有这条查询，"解析成功"就只是一个
     * 没人验证过的字段，而它会在接入检索时才暴露问题。
     *
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @param page              页码，从 1 开始
     * @param size              页大小，省略时用配置的默认值
     * @param principal         当前用户
     * @return 分页的分块，按序号升序
     */
    @GetMapping("/{docPublicId}/chunks")
    @PreAuthorize("hasAuthority('document:read')")
    @Operation(summary = "文档解析分块",
            description = "按序号升序返回解析出的分块，用于核对解析质量。"
                    + "文档尚未解析成功时返回空列表，而不是 404 —— "
                    + "它确实存在，只是还没有内容。")
    public PageResponse<DocumentChunkResponse> chunks(
            @PathVariable("workspacePublicId") String workspacePublicId,
            @PathVariable("docPublicId") String docPublicId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return PageResponse.from(
                documentService.chunks(principal.userId(), workspacePublicId, docPublicId,
                        page, effectiveSize(size)),
                DocumentChunkResponse::from);
    }

    /**
     * 触发重新解析。
     *
     * <h2>为什么是 POST 而不是 PUT</h2>
     * 它不替换任何客户端提交的表示，而是"请再做一次那件异步的事"。
     * 用 POST 与"创建一次解析意图"相符，也让它在语义上天然可以重复调用 ——
     * 重复调用不会产生第二份任务（唯一键保证），只是把已有的任务重置回队列。
     *
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @param principal         当前用户
     * @return 重置后的元数据
     */
    @PostMapping("/{docPublicId}/parse")
    @PreAuthorize("hasAuthority('document:retry')")
    @Operation(summary = "重新解析文档",
            description = "把这份文档的解析任务放回队列（重试次数重置）。上传者本人、"
                    + "或空间拥有者/管理员。执行中与排队中的任务不会被重置 —— "
                    + "重复调用是安全的。")
    public DocumentResponse retryParse(@PathVariable("workspacePublicId") String workspacePublicId,
                                       @PathVariable("docPublicId") String docPublicId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        DocumentView view = documentService.retryParse(
                principal.userId(), workspacePublicId, docPublicId);
        return DocumentResponse.from(view);
    }

    /**
     * 删除文档（软删除）。
     *
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @param principal         当前用户
     */
    @DeleteMapping("/{docPublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('document:delete')")
    @Operation(summary = "删除文档",
            description = "上传者本人、或空间拥有者/管理员。元数据先标记删除，再入队清理字节 —— "
                    + "反过来最坏会留下一行'声称有内容、实际取不到'的文档。")
    public void delete(@PathVariable("workspacePublicId") String workspacePublicId,
                       @PathVariable("docPublicId") String docPublicId,
                       @AuthenticationPrincipal UserPrincipal principal) {
        documentService.delete(principal.userId(), workspacePublicId, docPublicId);
    }

    /**
     * 把 Spring 的 {@code MultipartFile} 转成应用层输入。
     *
     * <h2>为什么在接口层做这一次转换，而不是让服务层直接吃 {@code MultipartFile}</h2>
     * 让应用层依赖 Spring Web 的类型，意味着服务层的方法只能由 HTTP 请求驱动 ——
     * 想为它写一个不经过 Servlet 的测试就得构造一个假的 multipart 对象，
     * 而想从别处（将来的导入功能、演示数据生成）复用它也无从下手。
     * 转换只发生在这里一处，代价是一个方法与两行代码。
     *
     * <p>这里的 {@code getInputStream()} 只是一个尚未被消费的流：真正的读取、
     * 哈希计算与大小比对都在服务层与存储适配器里发生。它可能抛出的
     * {@code IOException} 被包成 {@code UncheckedIOException} 交给统一异常处理 ——
     * 那是"这份上传内容读不出来"，属于 5xx，而不是调用方能靠改参数修正的 4xx。
     *
     * @param file 上传的文件
     * @return 应用层上传输入
     */
    private static DocumentUpload toUpload(MultipartFile file) {
        try {
            return new DocumentUpload(
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    file.getInputStream());
        } catch (IOException ex) {
            throw new UncheckedIOException("读取上传内容失败", ex);
        }
    }

    /**
     * 解析实际生效的页大小。
     *
     * @param size 客户端请求的页大小，可为 null
     * @return 传给服务层的页大小
     */
    private int effectiveSize(Integer size) {
        return size == null ? properties.feed().defaultPageSize() : size;
    }

    /**
     * 解析分块的对外表示。
     *
     * <p>它不含 {@code workspaceId} 与 {@code documentId}：前者是内部的范围列，
     * 后者已经从请求路径里知道。返回它们只会多暴露两个可以拿来猜的东西。
     *
     * @param ordinal   块序号，自 0 连续递增
     * @param heading   所属标题路径，可为 null
     * @param content   块内容
     * @param charCount 字符数
     */
    public record DocumentChunkResponse(int ordinal, String heading, String content, int charCount) {

        /**
         * 从领域对象构造。
         *
         * @param chunk 分块
         * @return 响应
         */
        public static DocumentChunkResponse from(DocumentChunk chunk) {
            return new DocumentChunkResponse(
                    chunk.ordinal(), chunk.heading(), chunk.content(), chunk.charCount());
        }
    }
}
