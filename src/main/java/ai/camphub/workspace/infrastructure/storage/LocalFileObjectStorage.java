package ai.camphub.workspace.infrastructure.storage;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.app.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地文件系统的对象存储实现。
 *
 * <h2>它是真实后端，不是占位实现</h2>
 * 上传与下载都能真正跑通，被集成测试覆盖。选择它作为默认后端的理由是
 * "克隆下来就能跑"：不必先起一个对象存储服务。S3 兼容后端（{@link S3ObjectStorage}）
 * 是同等地位的另一条实现，通过 {@code app.workspace.storage.backend} 切换。
 *
 * <h2>路径安全：两道，而且是不同的两道</h2>
 * <ol>
 *   <li><b>格式校验</b>：{@code keyHint} 必须匹配 {@code [A-Za-z0-9_-]{1,64}}。
 *       本项目传入的是服务端生成的 {@code public_id}（62 进制随机串），本来就安全 ——
 *       但"本来就安全"不是一种可以依赖的性质：下一个人完全可能为了让文件名可读，
 *       把它改成"用原始文件名做前缀"。有了格式校验，那种改动会立刻写入失败，
 *       而不是变成一个路径穿越漏洞。</li>
 *   <li><b>归一化校验</b>：拼好的路径必须仍然位于存储根目录之内。
 *       它防的是校验被绕过、或存储根配置本身被写成相对路径时产生的意外逃逸。
 *       两道检查都要有，因为它们防的不是同一件事。</li>
 * </ol>
 *
 * <h2>原子写入</h2>
 * 先写临时文件，再原子改名。否则一个在上传中途断开的请求会留下半份内容，
 * 而它的元数据行看起来完全正常 —— 用户会下载到一个截断的文件，
 * 且没有任何东西能告出"这份文件是坏的"。
 *
 * <h2>完整性校验</h2>
 * 写完后比对实际字节数与调用方声明的大小。不一致就删掉并报错：
 * 内容不完整比没有内容更糟，因为它会被当成成品使用。
 */
public class LocalFileObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileObjectStorage.class);

    /** 允许的 keyHint 形状。见类注释第 1 条。 */
    private static final Pattern KEY_HINT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 键的固定前缀。与 S3 实现保持一致，使两条后端的键布局可以互相迁移。 */
    private static final String KEY_PREFIX = "documents/";

    /** 临时文件后缀。以点开头，避免被当成正式文件枚举到。 */
    private static final String TEMP_SUFFIX = ".part";

    private final Path root;

    /**
     * 构造注入。
     *
     * @param root 存储根目录；不存在时会在首次写入时创建
     */
    public LocalFileObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String store(String keyHint, InputStream content, long sizeBytes) {
        if (keyHint == null || !KEY_HINT.matcher(keyHint).matches()) {
            // 这条消息可以带 keyHint 本身：它是服务端生成的标识，不是用户内容。
            // 但绝不回给客户端 —— 它出现在日志里，用于定位"哪次调用传了不合法的前缀"。
            throw new IllegalArgumentException("存储键前缀不合法：" + keyHint);
        }
        String key = keyOf(keyHint);
        Path target = resolveInsideRoot(key);
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);

        try {
            Files.createDirectories(target.getParent());
            long written = Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            if (written != sizeBytes) {
                Files.deleteIfExists(temp);
                throw new BusinessException(ErrorCode.INVALID_WORKSPACE_CONTENT);
            }
            Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("文档字节已落盘：key={} size={} root={}", key, written, root);
            return key;
        } catch (IOException ex) {
            deleteQuietly(temp);
            // 包装成受检异常之外的运行时异常：磁盘故障不是调用方能用参数修正的问题，
            // 但必须变成 5xx + traceId，而不是静默变成"上传成功但文件不在"。
            throw new UncheckedIOException("写入文档内容失败：key=" + key, ex);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        Path target = resolveInsideRoot(storageKey);
        if (!Files.isRegularFile(target)) {
            // 元数据说"有内容"但字节不见了。返回 503 而不是 404：
            // 资源确实存在、权限也确实有，只是承载它的东西暂时取不到。
            // 404 会让使用者以为自己的文档被删了。
            log.error("存储键对应的内容不存在：key={} path={}", storageKey, target);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException ex) {
            log.error("打开文档内容失败：key={}", storageKey, ex);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * 删除内容。
     *
     * <h2>两种失败被刻意区分开</h2>
     * <ul>
     *   <li><b>键不存在</b>：静默成功。这是删除的常见情形（重试、重复清理），
     *       而且它的语义"让它不存在"已经满足。</li>
     *   <li><b>存储故障</b>（权限、磁盘错误）：<b>抛出</b>异常。
     *       这一点在 Phase 05 之前是吞掉的，当时删除只发生在请求线程里，
     *       吞掉它顶多丢一条日志。现在删除还会由 CLEANUP 任务重试 ——
     *       若失败被吞成成功，任务会标记完成，字节永久留在存储上，
     *       而库里再没有任何线索指向它。失败必须传上去，才能被重试。</li>
     * </ul>
     *
     * @param storageKey 存储键
     * @throws UncheckedIOException 存储本身出错时
     */
    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        Path target;
        try {
            target = resolveInsideRoot(storageKey);
        } catch (IllegalArgumentException ex) {
            // 逃出根目录的键**绝不执行删除**，但也不抛异常：
            // 一个能穿越的删除键会删掉根目录之外的任意文件，所以不删；
            // 而抛出异常只会在清理路径上盖掉真正的失败原因。
            // 记一条 warn，是这里唯一有信息量的动作。
            log.warn("拒绝删除逃出存储根目录的键：key={}", storageKey);
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new UncheckedIOException("删除文档内容失败：key=" + storageKey, ex);
        }
    }

    /**
     * 本地磁盘后端<b>无法</b>提供绕开应用服务器的直链。
     *
     * <p>字节就在这个进程能访问的磁盘上，任何"直链"最终仍然要由本应用读出来再写出去。
     * 因此这里如实返回空，由应用层改用自己签发的短期令牌链接
     * （见 {@code DownloadTokenService}）—— 那条路径有同样的对外语义
     * （一个时限内可直接使用的下载地址），只是不省那次数据搬运。
     *
     * @param storageKey   存储键
     * @param downloadName 建议的下载文件名
     * @param ttl          有效时长
     * @return 恒为空
     */
    @Override
    public Optional<URI> directGetUrl(String storageKey, String downloadName, Duration ttl) {
        return Optional.empty();
    }

    /**
     * 由 keyHint 生成最终存储键。
     *
     * <p>按前两位分目录：单个目录下堆几万个文件会让某些文件系统的目录遍历变慢，
     * 而分两层之后每个目录的文件数下降两个数量级。分目录对读取没有影响 ——
     * 键整串记在数据库里，读的时候按原样解析。
     *
     * @param keyHint 已校验的前缀
     * @return 存储键
     */
    private static String keyOf(String keyHint) {
        return KEY_PREFIX + keyHint.substring(0, 2) + "/" + keyHint;
    }

    /**
     * 把存储键解析成绝对路径，并确认它没有逃出根目录。
     *
     * @param key 存储键
     * @return 绝对且已归一化的路径
     * @throws IllegalArgumentException 路径逃出根目录时
     */
    private Path resolveInsideRoot(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("存储键解析后逃出根目录：" + key);
        }
        return resolved;
    }

    /**
     * 尽力删除，不抛异常。用于失败路径上的清理。
     *
     * <p>与 {@link #delete} 的区别是明确的：这个方法用在"本来就已经在处理另一个失败"
     * 的路径上，那里再抛一个异常只会盖掉真正的失败原因。
     *
     * @param path 待删除路径
     */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("清理存储文件失败：path={}", path, ex);
        }
    }
}
