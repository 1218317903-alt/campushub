package ai.camphub.workspace.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 空间模块配置（前缀 {@code app.workspace}）。
 *
 * <h2>配置绑不上时不会报错，这是本类最需要防的一件事</h2>
 * 与 {@code CommunityProperties} 完全相同的风险：record 的构造函数绑定在属性缺失时
 * <b>不报错</b>，嵌套组件会变成 {@code null}；若 {@code application.yml} 把前缀写成
 * 顶层 {@code workspace:}，整块配置会被静默忽略，而应用仍然正常启动。
 * 因此 {@code ApplicationConfigStructureTest} 会断言这些键在配置文件里真实存在。
 *
 * <h2>Phase 05 把存储配置从 documents 里拆出来</h2>
 * 拆分前 {@code documents} 同时装着"上传约束"与"字节放哪"。接入对象存储之后，
 * 后端选择（本地 / S3）、端点、凭据、桶名这一组配置与上传约束没有任何关系 ——
 * 把它们留在同一个 record 里，会让"换存储后端"看起来像在改上传规则。
 *
 * @param limits    空间基础字段的输入约束
 * @param members   成员与邀请约束
 * @param notes     笔记输入约束
 * @param documents 文档上传与下载约束
 * @param storage   字节存储后端配置
 * @param parsing   解析与分块配置
 * @param worker    异步任务 worker 配置
 * @param feed      列表分页约束
 */
@ConfigurationProperties(prefix = "app.workspace")
public record WorkspaceProperties(
        Limits limits,
        Members members,
        Notes notes,
        Documents documents,
        Storage storage,
        Parsing parsing,
        Worker worker,
        Feed feed
) {

    /**
     * 空间基础字段约束。
     *
     * <p>上限与 {@code workspace.name VARCHAR(80)} / {@code description VARCHAR(500)}
     * 对齐 —— 调大到超过列宽会让插入报错，两处必须一起改。
     *
     * @param maxNameLength        空间名称最大字符数
     * @param maxDescriptionLength 空间描述最大字符数
     */
    public record Limits(int maxNameLength, int maxDescriptionLength) {
    }

    /**
     * 成员与邀请约束。
     *
     * @param maxMembers       单个空间的最大成员数（不含拥有者）。
     *                         <b>这是资源保护上限</b>：成员列表、成员校验都会被它约束住大小
     * @param inviteValidHours 邀请码有效期（小时）。取值理由见 {@code docs/resource-authorization.md}：
     *                         太短会让"发出去还没来得及点"变成常态，太长等于给一个长期有效的入门口令
     */
    public record Members(int maxMembers, int inviteValidHours) {
    }

    /**
     * 笔记输入约束。
     *
     * @param maxTitleLength 标题最大字符数，与 {@code note.title VARCHAR(200)} 对齐
     * @param maxBodyLength  正文最大字符数，与 {@code app.community.post.max-body-length} 取同一个值。
     *                       <b>它是资源保护上限而非体验问题</b>：正文每次写入都要被渲染成 HTML
     *                       并跑一遍白名单净化，代价与输入长度成正比
     * @param summaryLength  列表摘要长度，与 {@code note.summary VARCHAR(300)} 对齐
     */
    public record Notes(int maxTitleLength, int maxBodyLength, int summaryLength) {
    }

    /**
     * 文档上传与下载约束。
     *
     * @param maxSizeBytes  单个文件最大字节数。<b>与 {@code spring.servlet.multipart.max-file-size}
     *                      是两个不同的闸门</b>：multipart 那一层是容器级的硬上限（超了直接
     *                      在进入控制器之前就被拒），这里的值刻意更小，让"文件太大"由应用自己
     *                      判断并返回统一错误信封，而不是让容器抛出一个形状不同的异常
     * @param allowedTypes  允许声明的 MIME 类型白名单。用白名单而不是黑名单
     * @param downloadInline 下载时是否允许浏览器内联打开。<b>默认 false，且不建议改动</b>：
     *                      内联打开意味着用户上传的内容会在本站域的源下被浏览器解析渲染，
     *                      这正是"上传一个 HTML 就得到一个 XSS"的成因
     * @param publicBaseUrl 对外可访问的基地址，用于拼接短期下载链接。
     *                      <b>留空时生成相对路径 URL</b>（{@code /api/v1/...}），
     *                      对同源前端已经够用；配成绝对地址是为了让链接能被贴到别处使用。
     *                      它必须是部署者知道的事实，因此不能由应用猜测（猜错会得到一个
     *                      指向 localhost 的、在用户浏览器里打不开的链接）
     * @param downloadTokenTtlSeconds 短期下载令牌的有效期（秒）。
     *                      它同时是"链接泄漏后的暴露窗口"，因此默认取一个很短的值
     * @param downloadTokenSecret 短期下载令牌的签名密钥。留空时在启动时随机生成，
     *                      并在日志里警告一次 —— 那意味着重启会让所有在途链接失效。
     *                      生产环境必须显式配置
     */
    public record Documents(long maxSizeBytes,
                            List<String> allowedTypes,
                            boolean downloadInline,
                            String publicBaseUrl,
                            int downloadTokenTtlSeconds,
                            String downloadTokenSecret) {
    }

    /**
     * 字节存储后端配置。
     *
     * <h2>为什么默认是 local</h2>
     * 两条路径都是真实可用的实现（对象存储那条有独立的容器化集成测试覆盖），
     * 默认选本地是为了让"克隆下来就能跑"成立：不必先起一个对象存储服务。
     * 这也是 {@code docs/11-开发环境.md} 里记录的开发环境约束在配置上的落点。
     *
     * @param backend  后端类型：{@code local} 或 {@code s3}
     * @param localDir 本地后端的存储根目录，仅在 {@code backend=local} 时使用
     * @param s3       S3 兼容后端配置，仅在 {@code backend=s3} 时使用
     */
    public record Storage(String backend, String localDir, S3 s3) {
    }

    /**
     * S3 兼容后端配置。
     *
     * <h2>为什么包含 endpoint 与 path-style</h2>
     * 这两个是"同一份代码能同时对接 MinIO 与 AWS S3"的全部差异：
     * MinIO 需要一个自建端点、且必须用 path-style（bucket 作为路径第一段）；
     * AWS S3 用默认端点与 virtual-hosted style。因此适配器里不存在任何
     * "如果是 MinIO 就怎样"的分支 —— 差异被这两个配置项吃掉了。
     *
     * @param bucket    桶名。不自动建桶：建桶是运维动作，且桶的区域/策略
     *                  属于部署决策，让应用在启动时"顺手建一个"会把这两件事隐掉
     * @param endpoint  自建端点，如 {@code http://127.0.0.1:9000}。留空时用 AWS 默认端点
     * @param region    区域。S3 协议要求它存在，即使自建端点并不真的按区域路由
     * @param accessKey 访问密钥；留空时回退到 SDK 的默认凭据链
     * @param secretKey 访问密钥对应的秘密
     * @param pathStyle 是否使用 path-style 寻址。<b>MinIO 必须为 true</b>
     */
    public record S3(String bucket,
                     String endpoint,
                     String region,
                     String accessKey,
                     String secretKey,
                     boolean pathStyle) {
    }

    /**
     * 解析与分块配置。
     *
     * @param chunkMaxChars 分块字符数上界。它决定一个块能装多少内容：
     *                      太小会让一个完整段落被腰斩，太大则让"命中在文档中的位置"
     *                      这句话变得没有精度。取值理由见 {@code docs/document-pipeline.md}
     * @param maxPages      PDF 最大页数。超过即解析失败，而不是退化成"抽全文、丢页码"
     * @param maxTextChars  单个文档允许抽出的最大字符数。超过即失败，
     *                      不做静默截断（截断会让"全文可检索"这个承诺变得不真实）
     * @param memoryBytes  解析期允许驻留堆内的字节数上限，超出部分由 PDFBox 落到临时文件。
     *                     它是对"压缩炸弹"这类输入的直接防护
     */
    public record Parsing(int chunkMaxChars, int maxPages, int maxTextChars, long memoryBytes) {
    }

    /**
     * 异步任务 worker 配置。
     *
     * @param enabled        是否启用轮询 worker。<b>测试里把它关掉</b>是有意义的：
     *                       需要确定性地驱动一次解析时，依赖定时器会让测试变得不稳定
     * @param batchSize      单次轮询领取的任务数。它与 {@code pollIntervalMs} 一起
     *                       决定吞吐与空转频率，是 Phase 09 压测要调的旋钮
     * @param pollIntervalMs 轮询间隔（毫秒）。<b>它同时是"任务从入队到开始处理"的最小延迟</b> ——
     *                       这个数字是数据库队列相对 MQ 的主要代价，必须能被明确说出
     * @param leaseSeconds   任务租约时长。过短会让慢任务被误判为崩溃而重复执行，
     *                       过长会让真正的崩溃恢复变慢
     * @param maxAttempts    默认重试次数上限
     * @param backoffSeconds 指数退避的基数（秒）。第 n 次失败后等待
     *                       {@code backoffSeconds * 2^(n-1)}
     * @param parseTimeoutSeconds 单次解析的时间上限。它防的是"某个输入让解析器
     *                       进入近乎无限的计算"，而这在 PDF 里是真实存在的
     */
    public record Worker(boolean enabled,
                         int batchSize,
                         long pollIntervalMs,
                         int leaseSeconds,
                         int maxAttempts,
                         int backoffSeconds,
                         int parseTimeoutSeconds) {
    }

    /**
     * 列表分页约束。
     *
     * @param defaultPageSize 未指定时使用的页大小
     * @param maxPageSize     服务端强制的上限；超过上限按上限截断而不是报错
     */
    public record Feed(int defaultPageSize, int maxPageSize) {
    }
}
