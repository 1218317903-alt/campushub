package ai.camphub.workspace.api;

import ai.camphub.workspace.app.DocumentDownload;
import ai.camphub.workspace.app.TokenDownloadService;
import ai.camphub.workspace.config.WorkspaceProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短期下载链接的兑换端点。
 *
 * <h2>这是本项目唯一一个不做身份鉴权的接口</h2>
 * 它不标注 {@code @PreAuthorize}，也不经过资源级判定 —— 因为请求里没有身份。
 * 安全性完全由令牌的签名与时限承担，相关取舍写在
 * {@link ai.camphub.workspace.app.DownloadTokenService} 与
 * {@link TokenDownloadService} 的类注释里。把它单独成一个控制器，
 * 而不是挂在 {@code DocumentController} 下，是为了让"有一个接口不走防线"
 * 这件事在包结构上就看得见 —— 挂在别的控制器里，它会淹没在十几个
 * 都带 {@code @PreAuthorize} 的方法中间。
 *
 * <h2>为什么它不在 {@code /api/v1/workspaces/...} 之下</h2>
 * 那个前缀下的每个路径都带着一个空间标识，而这条路径的令牌本身就编码了
 * 空间与文档。让 URL 形状与"这里没有调用者身份"这件事一致，
 * 避免读代码的人以为它也要走空间级的判定。
 *
 * <h2>令牌出现在 URL 路径里，这是一个已知的取舍</h2>
 * 它意味着令牌会进入各级访问日志。这与对象存储预签名 URL 的取舍完全相同，
 * 也是"能不能把它放进 {@code <a href>}"的前提。缓解手段是有效期（默认 5 分钟）
 * 与响应上的 {@code Cache-Control: no-store}。
 */
@RestController
@RequestMapping("/api/v1/document-downloads")
@Tag(name = "Workspace · Documents", description = "空间文档：上传、列表、下载、解析与删除")
public class DocumentDownloadController {

    private final TokenDownloadService tokenDownloads;
    private final WorkspaceProperties properties;

    /**
     * 构造注入。
     *
     * @param tokenDownloads 令牌兑换服务
     * @param properties     空间模块配置（下载是否允许内联）
     */
    public DocumentDownloadController(TokenDownloadService tokenDownloads,
                                      WorkspaceProperties properties) {
        this.tokenDownloads = tokenDownloads;
        this.properties = properties;
    }

    /**
     * 兑换令牌并返回文件内容。
     *
     * <h2>三个响应头都是安全决定</h2>
     * <ul>
     *   <li><b>{@code Content-Type: application/octet-stream}</b>：不回显用户声明的类型。
     *       这条路径和带身份的下载走的是同一份判断 —— 若这里能回显 {@code text/html}
     *       并允许内联，那么"上传一个 HTML 再把它分享出去"就成了一次链接式的 XSS。</li>
     *   <li><b>{@code Content-Disposition}</b>：默认 {@code attachment}，文件名按 RFC 5987 编码。</li>
     *   <li><b>{@code Cache-Control: no-store}</b>：这一条是令牌路径特有的。
     *       内容位于一个"凭地址即可访问"的 URL 上，若被中间代理缓存下来，
     *       5 分钟的有效期就会变成一个远长于此的暴露窗口 ——
     *       而且失效之后链接依然可用，会让"过期"这件事彻底失去意义。</li>
     * </ul>
     *
     * @param token 令牌
     * @return 内容流
     */
    @GetMapping("/{token}")
    @Operation(summary = "用短期令牌下载文档",
            description = "令牌由 POST /api/v1/workspaces/{workspacePublicId}/documents/"
                    + "{docPublicId}/download-link 签发，默认 5 分钟有效。"
                    + "本接口不要求登录凭据 —— 令牌本身即凭据。")
    public ResponseEntity<InputStreamResource> download(@PathVariable("token") String token) {
        DocumentDownload download = tokenDownloads.redeem(token);

        ContentDisposition disposition = (properties.documents().downloadInline()
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore())
                .contentLength(download.sizeBytes())
                .body(new InputStreamResource(download.content()));
    }
}
