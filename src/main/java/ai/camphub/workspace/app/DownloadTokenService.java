package ai.camphub.workspace.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.config.WorkspaceProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 短期下载令牌的签发与校验。
 *
 * <h2>它解决的是"没有对象存储时，Signed URL 应该等于什么"</h2>
 * S3 能给出一个带签名、带过期时间的直链，浏览器可以把它塞进 {@code <a href>}
 * 直接用，不需要带任何请求头。本地磁盘后端给不出这样的直链 —— 但"一个短期有效、
 * 不需要额外凭据、可直接使用的下载地址"这个能力本身是需要的：
 * 前端要做"新标签页打开/另存为"就必须有一个能直接用的 URL，
 * 而用 {@code fetch} + Blob 拼一个临时对象 URL 只是把同一件事做在客户端，
 * 还要把整个文件读进内存。
 *
 * <p>因此本服务是那个能力的本地方案：<b>把权限判定提前到签发那一刻，
 * 把结果编码成一个有时限的凭据</b>。
 *
 * <h2>它绕过了三层防线，这一点必须说清楚</h2>
 * 校验这个令牌的端点<b>不做</b> {@code @PreAuthorize}、不调
 * {@code AuthorizationService}、也不经过空间过滤 —— 因为它拿到的请求里
 * 根本没有身份。安全性完全来自三件事：
 *
 * <ol>
 *   <li><b>签发时的完整判定</b>。令牌只能由已经通过三层防线的下载路径签发
 *       （见 {@code DocumentService#createDownloadLink}）。因此"谁能得到这个链接"
 *       与"谁能在界面上点下载"是同一个集合。</li>
 *   <li><b>极短的时限</b>。有效期默认 5 分钟，它就是"链接泄漏后的暴露窗口"。
 *       这是一个明确的取舍：窗口越短越安全，也越容易在慢网络上失效 ——
 *       因此它由配置决定，而不是写死在代码里。</li>
 *   <li><b>签名覆盖了全部内容</b>。工作空间与文档标识、到期时间都在被签名的载荷里，
 *       改任何一个字节都会导致校验失败。因此不存在"把别人的文档标识填进去"
 *       这种伪造。</li>
 * </ol>
 *
 * <h2>为什么用 HMAC 而不是复用 JWT 的签名设施</h2>
 * 令牌的内容完全由服务端生成、也只有服务端读，不需要标准化的
 * base64 JSON 结构（{@code header.payload.signature}）。用一个自包含的短格式
 * 让 URL 更短，也让"这个字符串是什么"不必再引一层标准去解释。
 * 签名算法与 JWT 侧同为 HMAC-SHA256，因此没有引入新的密码学假设。
 *
 * <h2>密钥缺失时的行为</h2>
 * 未配置时会随机生成一个进程级密钥并警告一次。这不是"安全默认值"，
 * 而是"开发环境能跑起来"的默认值：重启后所有在途链接失效（用户重试即可），
 * 而日志里的那条警告会提醒部署者去配置它。
 */
@Service
public class DownloadTokenService {

    private static final Logger log = LoggerFactory.getLogger(DownloadTokenService.class);

    /** 载荷各段之间的分隔符。 */
    private static final String FIELD_SEPARATOR = ":";

    /** 载荷与签名之间的分隔符。 */
    private static final String SIGNATURE_SEPARATOR = ".";

    /** 签名算法。与 JWT 侧一致，不引入新的密码学假设。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    /**
     * 构造服务。
     *
     * @param properties 空间模块配置
     * @param clock      时钟；注入而非直接取 {@code Instant.now()}，
     *                   否则"令牌过期"这件事只能靠真的等 5 分钟来测试
     */
    public DownloadTokenService(WorkspaceProperties properties, Clock clock) {
        this.clock = clock;
        WorkspaceProperties.Documents documents = properties.documents();
        this.ttl = Duration.ofSeconds(documents.downloadTokenTtlSeconds());
        this.secret = resolveSecret(documents.downloadTokenSecret());
    }

    /**
     * 令牌有效期。
     *
     * @return 有效期
     */
    public Duration ttl() {
        return ttl;
    }

    /**
     * 签发一个绑定到具体文档的下载令牌。
     *
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     * @return 令牌字符串
     */
    public String issue(String workspacePublicId, String docPublicId) {
        Instant expiresAt = clock.instant().plus(ttl);
        String payload = workspacePublicId + FIELD_SEPARATOR + docPublicId
                + FIELD_SEPARATOR + expiresAt.getEpochSecond();
        return encode(payload) + SIGNATURE_SEPARATOR + encode(sign(payload));
    }

    /**
     * 校验令牌。
     *
     * @param token 令牌字符串
     * @return 有效时返回它指向的目标；无效、被篡改或已过期时为空
     */
    public Optional<DownloadTicket> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int separator = token.indexOf(SIGNATURE_SEPARATOR);
        if (separator <= 0 || separator == token.length() - 1) {
            return Optional.empty();
        }
        Optional<String> payload = decode(token.substring(0, separator));
        Optional<String> signature = decode(token.substring(separator + 1));
        if (payload.isEmpty() || signature.isEmpty()) {
            return Optional.empty();
        }

        // 先验签再看内容。反过来的话，一个被篡改的载荷会先被解析 ——
        // 而解析不可信输入（哪怕是 split 一个字符串）是应该尽量避免的动作。
        //
        // 比较用 MessageDigest.isEqual 而不是 Arrays.equals：后者一旦遇到第一个
        // 不同的字节就返回，比较耗时会随"前缀匹配了多少"变化 ——
        // 那是一个能被用来逐字节猜签名的旁路。isEqual 是常量时间的。
        byte[] expected = sign(payload.get());
        if (!MessageDigest.isEqual(expected, signature.get().getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }

        String[] fields = payload.get().split(FIELD_SEPARATOR, -1);
        if (fields.length != 3) {
            return Optional.empty();
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(fields[2]);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (clock.instant().getEpochSecond() > expiresAt) {
            return Optional.empty();
        }
        return Optional.of(new DownloadTicket(fields[0], fields[1]));
    }

    /**
     * 校验令牌，失败时抛业务异常。
     *
     * @param token 令牌
     * @return 它指向的目标
     * @throws BusinessException 无效或已过期时 {@code 40024}
     */
    public DownloadTicket requireValid(String token) {
        return verify(token).orElseThrow(() -> new BusinessException(ErrorCode.DOWNLOAD_LINK_INVALID));
    }

    /**
     * 解析签名密钥。
     *
     * @param configured 配置值
     * @return 密钥字节
     */
    private static byte[] resolveSecret(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        log.warn("未配置 app.workspace.documents.download-token-secret，已生成随机密钥。"
                + "重启后所有在途的下载链接会立即失效；生产环境必须显式配置该值。");
        return generated;
    }

    /**
     * 计算签名。
     *
     * @param payload 载荷
     * @return 签名字节
     */
    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            // HMAC-SHA256 是 JDK 必须支持的算法。走到这里说明运行环境被裁剪过，
            // 属于部署问题而非输入问题。
            throw new IllegalStateException("运行环境不支持 " + HMAC_ALGORITHM, ex);
        }
    }

    /**
     * 编码为 URL 安全且无填充的 base64。
     *
     * <p>去掉填充符是因为令牌要出现在 URL 路径里，而 {@code =} 在路径中虽然合法，
     * 却容易被各级代理与日志系统做各种转义处理 —— 那会让"链接在某些环境下打不开"
     * 变成一个难以复现的问题。
     *
     * @param value 原始字符串
     * @return 编码结果
     */
    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 编码任意字节为 URL 安全且无填充的 base64。
     *
     * <p>签名是一段二进制，不是文本。把它先转成字符串再编码，会多依赖一次
     * 字符集往返 —— 那个往返在 UTF-8 下恰好无损，因此不会立刻出错，
     * 但它让"签名的编码方式"变成一个可以被人无意改动的细节。
     * 直接编码字节，这条路就不存在。
     *
     * @param value 原始字节
     * @return 编码结果
     */
    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * 解码 URL 安全 base64。
     *
     * <p>返回 {@code Optional} 而不是抛异常：用户可能手工拼错或截断这个链接，
     * 因此"解码失败"是正常输入而不是异常情况。把它表达成一个可选值，
     * 调用方就无法忘记处理它。
     *
     * @param value 编码字符串
     * @return 解码结果；格式非法时为空
     */
    private static Optional<String> decode(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return Optional.of(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * 令牌指向的目标。
     *
     * @param workspacePublicId 空间对外标识
     * @param docPublicId       文档对外标识
     */
    public record DownloadTicket(String workspacePublicId, String docPublicId) {
    }
}
