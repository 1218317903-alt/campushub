package ai.camphub.workspace.config;

import ai.camphub.workspace.app.AuthorizationService;
import ai.camphub.workspace.app.DocumentParser;
import ai.camphub.workspace.app.DownloadLinkService;
import ai.camphub.workspace.app.DownloadTokenService;
import ai.camphub.workspace.app.ObjectStorage;
import ai.camphub.workspace.infrastructure.parser.MarkdownDocumentParser;
import ai.camphub.workspace.infrastructure.parser.PdfDocumentParser;
import ai.camphub.workspace.infrastructure.parser.PlainTextDocumentParser;
import ai.camphub.workspace.infrastructure.scope.WorkspaceScopeInterceptor;
import ai.camphub.workspace.infrastructure.storage.LocalFileObjectStorage;
import ai.camphub.workspace.infrastructure.storage.S3ObjectStorage;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.S3Presigner.Builder;

/**
 * 空间模块的装配点。
 *
 * <h2>为什么这里有三件看起来不相干的东西</h2>
 * 它们有一个共同点：<b>都是"把某段逻辑接到某个扩展点上"的决定</b>，
 * 而不是业务规则。业务规则在 {@code app} 层，适配实现分别在
 * {@code infrastructure.scope}、{@code infrastructure.storage} 与
 * {@code infrastructure.parser}，装配集中在这里，
 * 使得"这个模块接入了哪些机制"一眼可见。
 *
 * <h2>Phase 05 之后这里多了一类东西：可替换的适配器</h2>
 * 存储后端有两个真实实现、解析器有三个，它们之间没有"哪个是主"的关系 ——
 * 选谁由配置决定。把选择写在这里（而不是在实现里用 {@code if} 判断自己该不该生效），
 * 让"当前跑的是哪一套"成为一个能在一屏内回答的问题。
 */
@Configuration
@EnableScheduling
public class WorkspaceConfig {

    /** 使用 S3 兼容后端的配置值。 */
    private static final String BACKEND_S3 = "s3";

    // ------------------------------------------------------------------------
    // 关于 @EnableScheduling 放在这里
    // ------------------------------------------------------------------------
    // 它是一个**全局**开关（让整个上下文里的 @Scheduled 生效），而当前唯一的
    // 定时任务就是本模块的文档 worker（DocumentTaskWorker）。把它放在"需要它的
    // 那个模块"旁边，是为了让"为什么应用里会有定时任务"不必跨文件追问。
    //
    // 它的失效模式值得单独记一句：没有这个注解时 @Scheduled 方法**照常存在、
    // 照常能被直接调用、也照常能通过单元测试**，只是永远不会被框架触发。
    // 这与"把 MyBatis 拦截器做成一个没被登记的 Bean"是同一类问题 ——
    // 编译期与单测都发现不了，只有集成测试里"任务始终停在 PENDING"能暴露它。
    // DocumentPipelineIT 正是为此存在的。
    //
    // 若将来别的模块也引入定时任务，把本注解挪到应用主类会让归属更清楚 ——
    // 到那时它才真正属于"全局基础设施"而不是"这个模块的需要"。
    // ------------------------------------------------------------------------

    /**
     * 把第三层防线注册进 MyBatis。
     *
     * <h2>为什么用 ConfigurationCustomizer 而不是把拦截器标成组件</h2>
     * MyBatis 的拦截器必须被 {@code Configuration.addInterceptor} 显式登记才会生效 ——
     * 一个没有被登记的 {@code Interceptor} Bean 是一个<b>看起来在工作、实际不存在</b>的组件：
     * 它会被注入、会被测试构造出来、方法也都能调，只是永远不会被框架调用。
     * 用 {@code ConfigurationCustomizer} 表达"登记"这个动作，让漏登记不可能发生。
     *
     * <p>{@code ObjectProvider} 是刻意的：拦截器在构造时不能拿到
     * {@link AuthorizationService}，否则会形成
     * "拦截器 → 授权服务 → Mapper → SqlSessionFactory → 拦截器" 的构造环。
     * 它在真正拦截时才通过 provider 取用，那时容器已经装配完毕。
     *
     * @param authorizationService 授权服务的懒提供者
     * @return 配置定制器
     */
    @Bean
    public ConfigurationCustomizer workspaceScopeConfigurationCustomizer(
            ObjectProvider<AuthorizationService> authorizationService) {
        WorkspaceScopeInterceptor interceptor = new WorkspaceScopeInterceptor(authorizationService);
        return configuration -> configuration.addInterceptor(interceptor);
    }

    /**
     * 对象存储适配器。应用层依赖的是 {@link ObjectStorage} 端口，因此
     * "字节到底放在哪"这个决定只发生在这一个方法里。
     *
     * <h2>为什么默认是本地磁盘</h2>
     * 两条路径都是真实可用的实现（对象存储那条有独立的容器化集成测试覆盖），
     * 默认选本地是为了让"克隆下来就能跑"成立：不必先起一个对象存储服务。
     *
     * <p>这里用 {@code if} 而不是 {@code @ConditionalOnProperty}：两个实现都是本模块的
     * 基础设施代码，选择逻辑放在一处比拆成两个条件注解更容易核对 ——
     * 条件注解写反时（例如两个都匹配）Spring 会抛"期望一个 Bean 却有多个"，
     * 那个报错信息不会告诉你配置项应该写什么。
     *
     * @param properties 空间模块配置
     * @return 存储实现
     */
    @Bean
    public ObjectStorage objectStorage(WorkspaceProperties properties) {
        WorkspaceProperties.Storage storage = properties.storage();
        if (BACKEND_S3.equals(storage.backend().strip().toLowerCase(Locale.ROOT))) {
            return s3ObjectStorage(storage.s3());
        }
        return new LocalFileObjectStorage(Path.of(storage.localDir()));
    }

    /**
     * 短期下载链接的构造器。
     *
     * @param objectStorage  对象存储端口（S3 后端能给出预签名直链，本地后端给不出）
     * @param downloadTokens 令牌签发与校验
     * @param properties     空间模块配置
     * @param clock          时钟
     * @return 链接构造器
     */
    @Bean
    public DownloadLinkService downloadLinkService(ObjectStorage objectStorage,
                                                   DownloadTokenService downloadTokens,
                                                   WorkspaceProperties properties,
                                                   Clock clock) {
        return new DownloadLinkService(objectStorage, downloadTokens, properties, clock);
    }

    // ------------------------------------------------------------------------
    // 解析器
    // ------------------------------------------------------------------------
    // 三个解析器都声明为 DocumentParser 类型的 Bean，DocumentParsingService
    // 通过 List<DocumentParser> 拿到它们。**没有用组件扫描**：
    // 解析器是"对某几种内容类型负责"的实现，而注册表本身是安全边界的一部分
    // （解析器能读懂的格式必须都能被上传，见 DocumentParsingService 的类注释）。
    // 把清单显式写出来，让"系统能读懂哪些格式"成为一个可读的列表，
    // 而不是"扫描到哪些就是哪些"。
    // ------------------------------------------------------------------------

    /**
     * 纯文本解析器。
     *
     * @return 解析器
     */
    @Bean
    public DocumentParser plainTextDocumentParser() {
        return new PlainTextDocumentParser();
    }

    /**
     * Markdown 解析器。
     *
     * @return 解析器
     */
    @Bean
    public DocumentParser markdownDocumentParser() {
        return new MarkdownDocumentParser();
    }

    /**
     * PDF 解析器。三个上限全部来自配置。
     *
     * @param properties 空间模块配置
     * @return 解析器
     */
    @Bean
    public DocumentParser pdfDocumentParser(WorkspaceProperties properties) {
        WorkspaceProperties.Parsing parsing = properties.parsing();
        return new PdfDocumentParser(parsing.memoryBytes(), parsing.maxPages(), parsing.maxTextChars());
    }

    // ------------------------------------------------------------------------
    // S3 兼容后端
    // ------------------------------------------------------------------------

    /**
     * 构造 S3 兼容的存储适配器。
     *
     * <h2>客户端的每一个配置项都是"同一份代码能同时对接 MinIO 与 AWS S3"的差异</h2>
     * 自建端点 + path-style 寻址构成 MinIO；默认端点 + virtual-hosted style 构成 AWS S3。
     * 因此适配器里<b>不存在</b>"如果是 MinIO 就怎样"的分支。
     *
     * <h2>端点留空即表示"用存储服务方的默认端点"</h2>
     * {@code endpointOverride} 只在配置了端点时才调用。这一条让"部署到 AWS"
     * 退化成"把 endpoint 留空"，而不是"改代码加一个布尔开关" ——
     * 后者会出现"endpoint 配了但开关没开"这种两个配置项互相打架的状态。
     *
     * <h2>presigner 必须单独配 path-style，这是实测出来的</h2>
     * {@code S3ClientBuilder} 有 {@code forcePathStyle(Boolean)} 这个便捷方法，
     * 但 {@code S3Presigner.Builder} <b>没有</b>（javap 核对过 2.46.7 的公开方法表）。
     * 它只能通过 {@code serviceConfiguration(S3Configuration)} 表达同一件事。
     * 漏掉这一条时，直链会在 MinIO 上被解析成 {@code bucket.127.0.0.1} 这样的主机名 ——
     * 一个在本地必然解析失败、而在 AWS 上完全正常的配置错误。
     *
     * <h2>HTTP 客户端为什么显式指定</h2>
     * pom 里排除了 {@code apache5-client} 与 {@code netty-nio-client}，
     * 只留 {@code url-connection-client}：本项目的对象读写没有并发流式需求，
     * 而 Netty 会把整个事件循环栈带进依赖树。显式写出它，是为了让"用的是哪一个"
     * 不依赖于 SDK 的自动发现顺序。
     *
     * @param s3 S3 配置
     * @return 存储实现
     */
    private static ObjectStorage s3ObjectStorage(WorkspaceProperties.S3 s3) {
        Region region = Region.of(s3.region());
        AwsCredentialsProvider credentials = credentialsOf(s3);
        boolean customEndpoint = s3.endpoint() != null && !s3.endpoint().isBlank();

        // 客户端与 presigner 的寻址方式必须一致：客户端用 path-style 写进去的对象，
        // 如果直链用 virtual-hosted 生成，签名是对的但地址是错的。
        S3Configuration serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(s3.pathStyle())
                .build();

        S3ClientBuilder clientBuilder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .forcePathStyle(s3.pathStyle())
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (customEndpoint) {
            clientBuilder.endpointOverride(URI.create(s3.endpoint()));
        }

        Builder presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfiguration);
        if (customEndpoint) {
            presignerBuilder.endpointOverride(URI.create(s3.endpoint()));
        }

        return new S3ObjectStorage(clientBuilder.build(), presignerBuilder.build(), s3.bucket());
    }

    /**
     * 解析凭据。
     *
     * <p>显式配置了访问密钥时用静态凭据；否则回退到 SDK 的默认凭据链
     * （环境变量 → 共享配置文件 → 实例角色）。回退而不是报错是刻意的：
     * 在 AWS 上运行时，最正确的做法恰恰是不配置密钥而使用实例角色。
     *
     * @param s3 S3 配置
     * @return 凭据提供者
     */
    private static AwsCredentialsProvider credentialsOf(WorkspaceProperties.S3 s3) {
        if (s3.accessKey() == null || s3.accessKey().isBlank()) {
            return DefaultCredentialsProvider.create();
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s3.accessKey(), s3.secretKey()));
    }
}
