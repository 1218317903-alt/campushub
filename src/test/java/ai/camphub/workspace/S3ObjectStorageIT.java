package ai.camphub.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;

/**
 * 对象存储后端的集成测试：对着一个<b>真实运行的 MinIO</b> 验证 S3 适配器。
 *
 * <h2>为什么必须是真容器，而不是一个假的 S3 客户端</h2>
 * 这一层里有三件事只有真实服务能回答：
 * <ol>
 *   <li><b>预签名 URL 到底能不能用。</b>它是一次本地计算，签名对不对要到服务端
 *       才被检验 —— 而签名覆盖了方法、桶、键、过期时间与若干查询参数，
 *       漏掉任何一个都会得到一个"看起来正常、请求时 403"的地址。
 *       假客户端永远会说"可以"，因此它对这一层没有验证能力。</li>
 *   <li><b>寻址方式（path-style / virtual-hosted）。</b>本项目接的是自建端点，
 *       必须走 path-style。这件事在 SDK 上没有一个统一的开关
 *       （{@code S3ClientBuilder} 与 {@code S3Presigner.Builder} 的表达方式不同），
 *       因此只有"真的把请求打到一个自建端点上"才能证明配对了。</li>
 *   <li><b>删除是不是真的删掉了。</b>对象存储的删除是幂等且静默的 ——
 *       权限不足与键不存在在客户端看起来可能一样。只有去桶里找一次才知道。</li>
 * </ol>
 *
 * <h2>它改了存储后端，因此会另起一套上下文</h2>
 * {@link DynamicPropertySource} 把 {@code app.workspace.storage.backend} 切成 {@code s3}，
 * 于是这一个测试类拥有自己的 Spring 上下文与自己的 MySQL 容器。
 * 这正是它该有的样子：其余测试不该因为"多了一个对象存储的用例"而被迫启动 MinIO。
 * 反过来做（让每个碰文档的测试都拖上一台 MinIO）会显著拖慢全量测试，
 * 换来的只是"存储后端这件事在每个测试里都被重复验证了一遍"。
 *
 * <h2>镜像与桶</h2>
 * 镜像默认取本机已有的 {@code minio/minio:latest}（本机 Docker Hub 直连不可达，
 * 见 docs/11-开发环境.md），可用 {@code -Dtestcontainers.minio.image=...} 覆盖。
 * 桶必须显式创建：MinIO 不会替你建，而"桶不存在"的表现是上传时的 404 NoSuchBucket。
 */
@DisplayName("对象存储后端 · 真实 MinIO")
class S3ObjectStorageIT extends WorkspaceTestSupport {

    /** 测试桶名。 */
    private static final String BUCKET = "camphub-test-documents";

    /** MinIO 的访问密钥。与 MySQL 容器一样：一次性容器，不保护任何有价值的东西。 */
    private static final String ACCESS_KEY = "camphub-test-access-key";

    /** 见 {@link #ACCESS_KEY}。 */
    private static final String SECRET_KEY = "camphub-test-secret-key-please-ignore";

    /** 仓库外可用 -Dtestcontainers.minio.image 覆盖，理由与 MySQL 镜像一致。 */
    private static final String MINIO_IMAGE = System.getProperty(
            "testcontainers.minio.image", "minio/minio:latest");

    /** 预签名直链用独立客户端取：它模拟的是"陌生人拿着地址"，不带任何应用凭据。 */
    private static final HttpClient PLAIN_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse(MINIO_IMAGE))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            // 健康检查就绪之后才算启动完成：MinIO 在完成初始化之前会拒绝服务，
            // 而"端口通了"比"服务能用了"早得多。
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
            .withStartupTimeout(Duration.ofMinutes(2))
            .withCommand("server", "/data");

    static {
        // 在静态块里启动，而不是靠 @Container：
        // @DynamicPropertySource 在 Spring 上下文创建之前执行，它必须能读到端口。
        // 用 @Container 时容器的启动时机取决于测试扩展的执行顺序，
        // 那是一个"换个运行器就可能变"的依赖。
        MINIO.start();
        createBucket();
    }

    /**
     * 把存储后端指向 MinIO。
     *
     * @param registry 属性注册表
     */
    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.workspace.storage.backend", () -> "s3");
        registry.add("app.workspace.storage.s3.bucket", () -> BUCKET);
        registry.add("app.workspace.storage.s3.endpoint", S3ObjectStorageIT::endpoint);
        registry.add("app.workspace.storage.s3.region", () -> "us-east-1");
        registry.add("app.workspace.storage.s3.access-key", () -> ACCESS_KEY);
        registry.add("app.workspace.storage.s3.secret-key", () -> SECRET_KEY);
        // 自建端点必须走 path-style：MinIO 不提供 <bucket>.host 形式的域名解析。
        registry.add("app.workspace.storage.s3.path-style", () -> "true");
    }

    // ------------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("上传的字节确实落在桶里，而不是留在本机磁盘上")
    void uploadLandsInTheBucket() throws IOException, InterruptedException {
        TestAccount owner = registerAccount(nextIp());
        String token = owner.tokens().access();
        String workspace = createWorkspace(token);
        byte[] content = "这段字节应当出现在对象存储里".getBytes(StandardCharsets.UTF_8);
        String docId = uploadDocumentOk(token, workspace, "对象存储.txt", "text/plain", content);

        String key = storageKeyOf(docId);
        assertThat(key).as("键由存储适配器生成，对外不暴露").startsWith("documents/");

        try (S3Client client = s3()) {
            byte[] stored = client.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asByteArray();
            assertThat(stored)
                    .as("字节必须逐字节一致 —— 两种后端共用同一个 ObjectStorage 端口契约")
                    .isEqualTo(content);
        }
    }

    @Test
    @DisplayName("下载链接是一条真的预签名直链：不带凭据就能取到内容，且带上原始文件名")
    void downloadLinkIsAPresignedDirectUrl() throws IOException, InterruptedException {
        TestAccount owner = registerAccount(nextIp());
        String token = owner.tokens().access();
        String workspace = createWorkspace(token);
        byte[] content = "预签名直链应当把这段内容原样返回".getBytes(StandardCharsets.UTF_8);
        String docId = uploadDocumentOk(token, workspace, "预算表.txt", "text/plain", content);

        HttpResponse<String> issued = sendJson("POST",
                "/api/v1/workspaces/" + workspace + "/documents/" + docId + "/download-link",
                "{}", token);
        assertThat(issued.statusCode()).isEqualTo(200);

        JsonNode link = json(issued);
        assertThat(link.path("direct").asBoolean(false))
                .as("对象存储能给出直链，字节不经过应用 —— 这正是接 S3 的意义")
                .isTrue();

        String url = link.path("url").asString("");
        assertThat(url).startsWith(endpoint());
        assertThat(url)
                .as("签名参数在查询串里；少了它服务端无法验证这次访问是被授权的")
                .contains("X-Amz-Signature");

        HttpResponse<byte[]> fetched = PLAIN_CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(content);

        String disposition = fetched.headers().firstValue("Content-Disposition").orElse("");
        assertThat(disposition)
                .as("文件名由链接签名里带出；少了它用户看到的是那串随机存储键")
                .contains("attachment");
        assertThat(URLDecoder.decode(disposition, StandardCharsets.UTF_8))
                .as("含非 ASCII 的原始文件名要能被还原（走 RFC 5987）")
                .contains("预算表.txt");
    }

    @Test
    @DisplayName("删掉文档之后，桶里也不再有那个对象")
    void deleteRemovesTheObjectFromTheBucket() throws IOException, InterruptedException {
        TestAccount owner = registerAccount(nextIp());
        String token = owner.tokens().access();
        String workspace = createWorkspace(token);
        String docId = uploadDocumentOk(token, workspace, "待删除.txt", "text/plain",
                "待删除的内容".getBytes(StandardCharsets.UTF_8));
        String key = storageKeyOf(docId);

        try (S3Client client = s3()) {
            assertThat(exists(client, key)).as("前置条件：上传之后对象应当在").isTrue();
        }

        assertThat(deleteWithToken(
                "/api/v1/workspaces/" + workspace + "/documents/" + docId, token).statusCode())
                .isEqualTo(204);

        try (S3Client client = s3()) {
            assertThat(exists(client, key))
                    .as("删除请求里的'立刻清理'是加速路径；它必须真的把对象删掉，"
                            + "而不是只把库里的键置空")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("解析流水线在对象存储后端上同样跑通：worker 读的是桶里的字节")
    void pipelineParsesFromObjectStorage() throws IOException, InterruptedException {
        TestAccount owner = registerAccount(nextIp());
        String token = owner.tokens().access();
        String workspace = createWorkspace(token);
        String markdown = "# 讲义\n\n这一行用来确认解析器的输入确实来自对象存储。\n\n## 小节\n\n正文。\n";
        String docId = uploadDocumentOk(token, workspace, "讲义.md", "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8));

        assertThat(driveQueue()).isGreaterThanOrEqualTo(1);

        JsonNode document = json(getWithToken(
                "/api/v1/workspaces/" + workspace + "/documents/" + docId, token));
        assertThat(document.path("parseStatus").asString(""))
                .as("worker 通过同一个 ObjectStorage 端口读字节，因此换后端不该影响解析")
                .isEqualTo("READY");
        assertThat(document.path("chunkCount").asInt(0)).isPositive();
        assertThat(chunkRowCount(docId))
                .as("分块与文档状态必须一致，否则那个 READY 是假的")
                .isEqualTo(document.path("chunkCount").asInt(-1));
    }

    @Test
    @DisplayName("延长有效期或改换对象的直链都会被拒签")
    void tamperedPresignedUrlIsRejected() throws IOException, InterruptedException {
        TestAccount owner = registerAccount(nextIp());
        String token = owner.tokens().access();
        String workspace = createWorkspace(token);
        byte[] content = "不会外泄的内容".getBytes(StandardCharsets.UTF_8);
        String docId = uploadDocumentOk(token, workspace, "不会外泄.txt", "text/plain", content);

        String url = json(sendJson("POST",
                "/api/v1/workspaces/" + workspace + "/documents/" + docId + "/download-link",
                "{}", token)).path("url").asString("");

        // 攻击一：延长有效期。取 7 天而不是"一年" ——
        // SigV4 规定 X-Amz-Expires 不得超过一周，超限时服务端在任何签名计算
        // **之前**就以 400 拒绝。那样测到的是"参数校验"，而不是这里要验证的
        // "签名覆盖了有效期"这条属性；而后者才是真正保护这条链接的东西。
        String extended = url.replaceAll("X-Amz-Expires=\\d+", "X-Amz-Expires=604800");
        assertThat(extended).as("前置条件：URL 里应当有过期参数").isNotEqualTo(url);

        // 攻击二：把链接指向另一个对象。键也在签名覆盖范围内。
        String retargeted = url.replaceFirst("/documents/", "/documents/x");
        assertThat(retargeted).as("前置条件：URL 里应当有对象键").isNotEqualTo(url);

        for (String tampered : new String[] {extended, retargeted}) {
            HttpResponse<byte[]> response = PLAIN_CLIENT.send(
                    HttpRequest.newBuilder(URI.create(tampered))
                            .timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.statusCode())
                    .as("签名覆盖方法、桶、键与有效期，因此改动任何一项都必然验签失败：%s", tampered)
                    .isEqualTo(403);
            assertThat(new String(response.body(), StandardCharsets.UTF_8))
                    .as("被拒的请求绝不能带上内容")
                    .doesNotContain("不会外泄的内容");
        }
    }

    @Test
    @DisplayName("阴性对照：一个不存在的键必须被判为不存在")
    void absenceIsDetected() {
        try (S3Client client = s3()) {
            assertThat(exists(client, "documents/zz/definitely-absent-" + System.nanoTime()))
                    .as("否则'删除之后对象不在'那条断言会永远通过，而它什么都没验证")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------------
    // 与 MinIO / SDK 的直连工具
    // ------------------------------------------------------------------------

    /**
     * 建桶。
     *
     * <p>MinIO 不会自动建桶，而"桶不存在"在客户端表现为上传时的 404 NoSuchBucket ——
     * 一个与真正原因相距很远的症状。因此建桶失败必须在这里就炸掉。
     */
    private static void createBucket() {
        try (S3Client client = s3()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 409) {
                // 409 是"已存在"（容器被复用时会出现）；其余失败一律抛出。
                throw new IllegalStateException("创建测试桶失败：" + BUCKET, ex);
            }
        }
    }

    /**
     * 构造一个指向测试容器、走 path-style 的 S3 客户端。
     *
     * @return 客户端，用完即关
     */
    private static S3Client s3() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build();
    }

    /**
     * 对象是否存在于桶里。
     *
     * <p>用 {@code headObject} 而不是 {@code getObject}：前者不搬运内容，
     * 而这里只关心"它在不在"。
     *
     * @param client 客户端
     * @param key    存储键
     * @return 存在时为 true
     */
    private static boolean exists(S3Client client, String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * 测试容器的端点地址。
     *
     * @return 形如 {@code http://localhost:32768}
     */
    private static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
