package ai.camphub.workspace.infrastructure.storage;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.app.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地文件系统的对象存储实现。
 *
 * <h2>为什么 Phase 04 就用它，而不是等 Phase 05 的 MinIO</h2>
 * 一个"上传永远返回 503、下载永远返回 503"的文档功能对使用者等于不存在。
 * 本地磁盘是真实的存储后端（不是空壳实现），它让整条链路在本阶段就能跑通并被验收；
 * Phase 05 增加 MinIO/S3 适配器时，替换的只是一个 Bean，应用层与对外契约都不动。
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

    /** 键的固定前缀。将来接对象存储时它是 bucket 内的路径前缀。 */
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
        String key = KEY_PREFIX + keyHint.substring(0, 2) + "/" + keyHint;
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

    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        Path target;
        try {
            target = resolveInsideRoot(storageKey);
        } catch (IllegalArgumentException ex) {
            // 逃出根目录的键**绝不执行删除**，但也**不抛异常**。
            // 两个决定各有理由：
            //   · 不删：这是唯一正确的选择 —— 一个能穿越的删除键会删掉根目录之外的任意文件。
            //   · 不抛：删除的唯一用途是"让它不存在"（正常删除、以及失败路径上的清理）。
            //     在清理路径上抛异常，会让真正的失败原因被一个次生异常盖掉；
            //     而调用方为了让清理不抛，只会写一个空的 catch，那等于把唯一的线索也扔掉。
            //     记一条 warn，是这里唯一有信息量的动作。
            log.warn("拒绝删除逃出存储根目录的键：key={}", storageKey);
            return;
        }
        deleteQuietly(target);
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
