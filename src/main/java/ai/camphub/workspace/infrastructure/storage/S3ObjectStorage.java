package ai.camphub.workspace.infrastructure.storage;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.app.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * S3 兼容的对象存储实现（AWS S3 · MinIO · 其它 S3 协议实现）。
 *
 * <h2>同一份代码怎么同时对接 MinIO 与 AWS S3</h2>
 * 差异只有两处，都落在配置里（{@code app.workspace.storage.s3}）：
 * <ul>
 *   <li><b>endpoint</b>：自建服务需要一个自建端点，AWS 用默认端点。</li>
 *   <li><b>path-style</b>：MinIO 必须以 path-style 寻址（桶名作为路径第一段），
 *       AWS 默认用 virtual-hosted style（桶名进域名）。</li>
 * </ul>
 * 因此本类里<b>不存在</b>任何 {@code if (isMinio)} 之类的分支 ——
 * 那种分支一旦出现，"我们到底在测哪一条路径"就会变成每次改动都要重新回答的问题。
 *
 * <h2>键的布局与本地后端完全一致</h2>
 * {@code documents/<前两位>/<public_id>}。这不是巧合：它意味着迁移后端时
 * 存储键不需要重写，{@code document.storage_key} 这一列的值在两条后端下含义相同。
 * 若两者布局不同，"换后端"就变成一次带数据搬迁的迁移，而不是改一个配置项。
 *
 * <h2>失败分类与本地后端对齐</h2>
 * <ul>
 *   <li><b>读取时对象不存在</b> → {@code 503 DEPENDENCY_UNAVAILABLE}，
 *       与本地实现一致。元数据说"有内容"但对象不在，是依赖不可用，
 *       不是"文档不存在"（那会返回 404 并让用户以为自己的文件被删了）。</li>
 *   <li><b>写入失败</b> → 抛 {@code UncheckedIOException}，变成 5xx + traceId。</li>
 *   <li><b>删除失败</b> → 同样抛出，理由与本地实现相同：
 *       CLEANUP 任务必须能感知失败才能重试（见 {@code LocalFileObjectStorage#delete}）。</li>
 * </ul>
 */
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    /** 与本地实现相同的键前缀与形状校验。 */
    private static final Pattern KEY_HINT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final String KEY_PREFIX = "documents/";

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    /**
     * 构造注入。
     *
     * @param client    已配置的客户端
     * @param presigner 已配置的预签名器（与客户端共享端点与凭据）
     * @param bucket    桶名
     */
    public S3ObjectStorage(S3Client client, S3Presigner presigner, String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public String store(String keyHint, InputStream content, long sizeBytes) {
        if (keyHint == null || !KEY_HINT.matcher(keyHint).matches()) {
            throw new IllegalArgumentException("存储键前缀不合法：" + keyHint);
        }
        String key = keyOf(keyHint);
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            // 显式声明长度，让 SDK 用的是"确定的长度"而不是分块传输。
                            // 声明值与实际字节数不符时上传会失败 —— 这正是我们要的：
                            // 一份被截断的对象看起来完全正常，直到有人下载它。
                            .contentLength(sizeBytes)
                            .build(),
                    RequestBody.fromInputStream(content, sizeBytes));
            log.info("文档字节已上传：bucket={} key={} size={}", bucket, key, sizeBytes);
            return key;
        } catch (S3Exception ex) {
            throw new UncheckedIOException(
                    new IOException("上传到对象存储失败：bucket=" + bucket + " key=" + key
                            + " status=" + ex.statusCode(), ex));
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            ResponseInputStream<GetObjectResponse> response = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
            return response;
        } catch (NoSuchKeyException ex) {
            log.error("对象存储中不存在该键：bucket={} key={}", bucket, storageKey);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (S3Exception ex) {
            log.error("读取对象失败：bucket={} key={} status={}", bucket, storageKey,
                    ex.statusCode(), ex);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (S3Exception ex) {
            // 键不存在时 S3 返回 204（删除是幂等的），因此走到这里一定是别的问题：
            // 权限、网络、桶不存在。这些都应当被 CLEANUP 任务感知并重试。
            throw new UncheckedIOException(
                    new IOException("删除对象失败：bucket=" + bucket + " key=" + storageKey
                            + " status=" + ex.statusCode(), ex));
        }
    }

    /**
     * 生成预签名直链。
     *
     * <h2>两个参数都是安全决定</h2>
     * <ul>
     *   <li><b>{@code signatureDuration}</b>：链接的有效期。它就是"链接泄漏后的暴露窗口"，
     *       因此取调用方给的短时限，而不是 SDK 的默认（7 天）。</li>
     *   <li><b>{@code responseContentDisposition}</b>：让存储服务在响应里带上
     *       {@code attachment; filename=...}。少了它，浏览器会用存储键
     *       （一串随机字符）作为下载文件名，而用户看不出那是自己的哪份文件。</li>
     * </ul>
     *
     * @param storageKey   存储键
     * @param downloadName 建议的下载文件名
     * @param ttl          有效时长
     * @return 预签名 URL
     */
    @Override
    public Optional<URI> directGetUrl(String storageKey, String downloadName, Duration ttl) {
        GetObjectRequest getObject = GetObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .responseContentDisposition(contentDisposition(downloadName))
                .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(getObject)
                        .build());
        URL url = presigned.url();
        return Optional.of(URI.create(url.toString()));
    }

    /**
     * 构造 {@code Content-Disposition} 头的值。
     *
     * <h2>为什么要自己拼而不是用框架的构造器</h2>
     * 这个字符串会被发给对象存储，由它在响应里原样回给浏览器 ——
     * 它<b>不经过本应用响应头处理的任何一层</b>。因此文件名里的 CR/LF
     * 在这里就是一次响应头注入，必须在这一处防住。
     *
     * <p>处理方式是双重的：先把引号与控制字符从文件名里去掉（防注入），
     * 再用 RFC 5987 的 {@code filename*} 传递完整文件名（让中文名能被正确解码）。
     * 只做前者会让中文名以乱码保存，只做后者则挡不住注入。
     *
     * @param downloadName 原始文件名
     * @return 头部值
     */
    private static String contentDisposition(String downloadName) {
        if (downloadName == null || downloadName.isBlank()) {
            return "attachment";
        }
        StringBuilder ascii = new StringBuilder(downloadName.length());
        downloadName.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        && codePoint != '"' && codePoint != '\\')
                .forEach(ascii::appendCodePoint);
        String safeName = ascii.toString().strip();
        if (safeName.isEmpty()) {
            return "attachment";
        }
        String encoded = java.net.URLEncoder.encode(downloadName, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + safeName + "\"; filename*=UTF-8''" + encoded;
    }

    /**
     * 由 keyHint 生成存储键，布局与本地后端一致。
     *
     * @param keyHint 已校验的前缀
     * @return 存储键
     */
    private static String keyOf(String keyHint) {
        return KEY_PREFIX + keyHint.substring(0, 2) + "/" + keyHint;
    }
}
