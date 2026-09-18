package ai.camphub.workspace.app;

import ai.camphub.workspace.config.WorkspaceProperties;
import ai.camphub.workspace.domain.WorkspaceDocument;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 短期下载链接的构造。
 *
 * <h2>它存在的原因：两条路径，一个契约</h2>
 * "一个在时限内可直接使用、不需要额外凭据的下载地址"是一个<b>对外契约</b>，
 * 而它有两种实现方式，取决于存储后端：
 * <ol>
 *   <li><b>后端能给直链</b>（对象存储）：返回存储服务自己签发的预签名 URL，
 *       客户端直连存储，字节不经过本应用。</li>
 *   <li><b>后端给不出直链</b>（本地磁盘）：返回本应用签发的一个带签名令牌的
 *       下载地址。客户端仍然经过本应用，但不需要带 Authorization 头。</li>
 * </ol>
 * 把这段选择收在一个地方，是为了让"客户端拿到的是什么"只有一个答案来源 ——
 * 而不是让每个调用点各判断一次后端类型（那迟早会有一处判断错，
 * 而表现是"有些环境下下载链接打不开"）。
 *
 * <h2>为什么路径常量在这里，而不是在控制器里</h2>
 * 本服务要拼出链接，控制器要注册路由，两处必须一致。把它定义在应用层、
 * 由控制器引用（{@code api} 依赖 {@code app} 是允许的方向），
 * 路径就只有一处出处。反过来（把常量放 api、让 app 引用）会形成反向依赖。
 */
public class DownloadLinkService {

    /**
     * 本应用签发的短期下载路径前缀。
     *
     * <h2>为什么令牌在路径里，而不是 query 参数</h2>
     * 路径里的令牌不会随相对链接被继承，也不会因为前端做一次
     * {@code location.href = ...} 而丢失；而 query 参数在部分场景下
     * 会被使用方改写或丢弃（例如某些下载管理器只保留路径）。
     * 令牌本身是 URL 安全字符集，放在路径里不需要额外转义。
     */
    public static final String LOCAL_DOWNLOAD_PATH = "/api/v1/document-downloads/";

    /** 路由中令牌的占位符，供控制器拼接映射路径。 */
    public static final String TOKEN_VARIABLE = "{token}";

    private final ObjectStorage objectStorage;
    private final DownloadTokenService downloadTokens;
    private final WorkspaceProperties properties;
    private final Clock clock;

    /**
     * 构造服务。
     *
     * @param objectStorage  对象存储端口
     * @param downloadTokens 令牌签发与校验
     * @param properties     空间模块配置（对外基地址）
     * @param clock          时钟
     */
    public DownloadLinkService(ObjectStorage objectStorage,
                               DownloadTokenService downloadTokens,
                               WorkspaceProperties properties,
                               Clock clock) {
        this.objectStorage = objectStorage;
        this.downloadTokens = downloadTokens;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 为一份文档构造下载链接。
     *
     * <p>调用方<b>必须已经完成授权判定</b>：本方法只负责构造链接，
     * 不做任何权限检查。令牌一经签发就带着"已授权"的含义，
     * 因此签发的时机就是权限判定的时机 —— 把两者分开写在不同的类里，
     * 是为了让"签发前有没有判过权限"这个问题在调用点一眼可见。
     *
     * @param document          文档（必须仍有字节）
     * @param workspacePublicId 空间对外标识
     * @return 下载链接
     */
    public DownloadLink create(WorkspaceDocument document, String workspacePublicId) {
        Duration ttl = downloadTokens.ttl();
        Instant expiresAt = clock.instant().plus(ttl);

        Optional<URI> direct =
                objectStorage.directGetUrl(document.storageKey(), document.name(), ttl);
        if (direct.isPresent()) {
            return new DownloadLink(direct.get(), true, expiresAt);
        }

        String token = downloadTokens.issue(workspacePublicId, document.publicId());
        return new DownloadLink(localUrlOf(token), false, expiresAt);
    }

    /**
     * 拼接本应用签发的下载地址。
     *
     * <h2>基地址为空时返回相对地址</h2>
     * 那对同源前端已经完全够用，而且<b>比猜一个绝对地址更正确</b> ——
     * 应用无法知道自己被部署在哪个域名下（反向代理、端口映射、多环境），
     * 猜错的结果是一个指向 {@code localhost} 的、在用户浏览器里打不开的链接。
     * 需要绝对地址时由部署者显式配置。
     *
     * @param token 令牌
     * @return 地址
     */
    private URI localUrlOf(String token) {
        String path = LOCAL_DOWNLOAD_PATH + token;
        String base = properties.documents().publicBaseUrl();
        if (base == null || base.isBlank()) {
            return URI.create(path);
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + path);
    }
}
