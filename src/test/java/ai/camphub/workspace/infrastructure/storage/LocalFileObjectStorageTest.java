package ai.camphub.workspace.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地磁盘存储适配器的测试。
 *
 * <h2>这个类里最值得测的不是"能存能取"</h2>
 * "能存能取"在第一条断言里就结束了。真正的风险集中在三处，而它们都不会在
 * 正常使用中暴露：
 * <ol>
 *   <li><b>路径穿越</b>：存储键最终会被拼成文件路径。请求参数里一旦出现
 *       {@code ../../} 而实现又直接拼接，写操作就能落到存储根目录之外 ——
 *       覆盖任意可写文件。因此这里逐条断言各类穿越构造都被拒绝，
 *       <b>并且断言根目录之外没有产生任何文件</b>（拒绝得晚一步就已经写完了）。</li>
 *   <li><b>大小不符</b>：客户端声明 100 字节却只发了 10 字节。若不比对，
 *       库里会留下一行"声称 100 字节"的记录，而文件只有 10 字节 ——
 *       用户在下载之后才发现内容被截断，而那时已无法知道是谁的问题。</li>
 *   <li><b>原子性</b>：写入中途失败不能留下一个看起来正常的半成品。
 *       这里断言失败后目录里不残留正式文件。</li>
 * </ol>
 */
class LocalFileObjectStorageTest {

    /** 每个用例一个干净的临时目录，互不干扰。 */
    @TempDir
    Path tempDir;

    /**
     * 正常路径：存、取、删。
     */
    @Test
    @DisplayName("存取删的完整往返")
    void storesReadsAndDeletes() throws IOException {
        LocalFileObjectStorage storage = new LocalFileObjectStorage(tempDir);
        byte[] payload = "hello camphub".getBytes(StandardCharsets.UTF_8);

        String key = storage.store("AbCdEf1234567890abcd", new ByteArrayInputStream(payload), payload.length);

        // 键由实现决定：调用方给的是前缀，实现决定分目录方式
        assertThat(key).startsWith("documents/");
        assertThat(key).endsWith("AbCdEf1234567890abcd");
        try (InputStream in = storage.open(key)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }

        storage.delete(key);
        // 删除是幂等的：第二次调用不应抛异常
        storage.delete(key);

        Throwable thrown = catchThrowable(() -> storage.open(key));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    /**
     * 路径穿越：格式校验与归一化校验两道。
     *
     * <p>断言"根目录之外没有文件"比断言"抛了异常"更重要 ——
     * 一个先写入再校验的实现同样会抛异常，但文件已经在那儿了。
     */
    @Test
    @DisplayName("含路径分隔符或点号的键一律被拒，且不会在根目录之外留下文件")
    void rejectsTraversalKeys() throws IOException {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root);
        LocalFileObjectStorage storage = new LocalFileObjectStorage(root);

        List<String> hostile = new ArrayList<>(List.of(
                "../escaped",
                "..",
                "./local",
                "a/b",
                "a\\b",
                "..%2Fescaped",
                "",
                " ",                    // 空格：不在 [A-Za-z0-9_-] 内
                "键",                    // 非 ASCII：明显不该出现在存储键里
                "x".repeat(65)));       // 超过 64 位上限

        for (String keyHint : hostile) {
            byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> storage.store(keyHint, new ByteArrayInputStream(payload), payload.length))
                    .as("键前缀 %s 应被拒绝", keyHint)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // 根目录之外（tempDir 下、root 之上）不得出现任何文件
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.map(Path::getFileName).map(Path::toString).toList())
                    .as("除存储根目录外，临时目录下不应出现任何文件或目录")
                    .containsExactly("root");
        }
    }

    /**
     * 读取时的归一化校验同样生效：即使一个非法的键被直接交给 {@code open}，
     * 也不能读到根目录之外的文件。
     */
    @Test
    @DisplayName("读取非法键时拒绝，不会读到根目录之外")
    void refusesToReadOutsideRoot() throws IOException {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root);
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "should not be readable through storage");

        LocalFileObjectStorage storage = new LocalFileObjectStorage(root);

        assertThatThrownBy(() -> storage.open("../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        // 删除同理：一个能穿越的删除键会删掉根目录之外的任意文件
        storage.delete("../secret.txt");
        assertThat(secret).exists();
    }

    /**
     * 大小不符：内容不完整比没有内容更危险，因为它会被当成成品使用。
     */
    @Test
    @DisplayName("实际字节数与声明不符时整次写入失败，且不留下正式文件")
    void rejectsSizeMismatch() throws IOException {
        Path root = tempDir.resolve("root");
        LocalFileObjectStorage storage = new LocalFileObjectStorage(root);
        byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);

        Throwable thrown = catchThrowable(
                () -> storage.store("MismatchKey0000000000", new ByteArrayInputStream(payload), 99));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).errorCode())
                .isEqualTo(ErrorCode.INVALID_WORKSPACE_CONTENT);

        // 既没有正式文件，也没有残留的临时文件
        if (Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                assertThat(walk.filter(Files::isRegularFile).toList())
                        .as("失败的上传不应留下任何文件（包括 .part 临时文件）")
                        .isEmpty();
            }
        }
    }

    /**
     * 读取一个"元数据说存在、字节实际不在"的键。
     */
    @Test
    @DisplayName("内容缺失时抛 503 而不是 404 —— 资源存在、权限也有，只是承载它的内容取不到")
    void missingContentYieldsDependencyUnavailable() {
        LocalFileObjectStorage storage = new LocalFileObjectStorage(tempDir);

        Throwable thrown = catchThrowable(() -> storage.open("documents/ab/absentKey00000000000"));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    /**
     * 删除一个空键：调用方在做幂等清理时不应该需要先判空。
     */
    @Test
    @DisplayName("删除空键静默返回")
    void deleteOfBlankKeyIsNoop() {
        LocalFileObjectStorage storage = new LocalFileObjectStorage(tempDir);

        storage.delete(null);
        storage.delete("");
        storage.delete("   ");
    }

    /**
     * 存储根目录不存在时会被创建。
     */
    @Test
    @DisplayName("首次写入时自动创建根目录与分目录")
    void createsDirectoriesOnDemand() throws IOException {
        Path root = tempDir.resolve("nested/deeper/root");
        LocalFileObjectStorage storage = new LocalFileObjectStorage(root);
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);

        String key = storage.store("Zz0000000000000000000", new ByteArrayInputStream(payload), payload.length);

        assertThat(root.resolve(key)).exists();
    }
}
