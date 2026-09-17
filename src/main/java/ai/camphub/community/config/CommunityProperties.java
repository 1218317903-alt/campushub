package ai.camphub.community.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 社区模块配置（前缀 {@code app.community}）。
 *
 * <h2>为什么上限写在这里而不是散在代码里</h2>
 * 下面几个数字（标签最多几个、正文最长多少、一页最多多少条）都是<b>资源保护参数</b>：
 * 它们决定了单个请求能让服务端做多少工作。压测时一定会调整它们，而"改配置"和
 * "改代码重新构建"是两种成本完全不同的操作。集中成类型化配置后，既能被环境变量覆盖，
 * 也能在评审时一眼看全 —— 和 {@code SecurityProperties} 的取舍一致。
 *
 * @param post     帖子相关的输入约束
 * @param feed     列表分页约束
 * @param demoSeed 合成演示数据生成器（Synthetic Demo Seed）的开关与规模
 */
@ConfigurationProperties(prefix = "app.community")
public record CommunityProperties(
        Post post,
        Feed feed,
        DemoSeed demoSeed
) {

    /**
     * 帖子输入约束。
     *
     * @param maxTags        单帖最多标签数。取 5 的理由是标签页的信噪比：
     *                       标签越多，每个标签页的内容越杂，"按标签浏览"就越失去意义。
     *                       这也是绝大多数内容社区的取值区间
     * @param maxBodyLength  正文最大字符数（按 Java 字符计）。<b>这是资源保护上限，不只是体验问题</b>：
     *                       正文每次发布都要被渲染成 HTML 并跑一遍白名单净化，
     *                       而这两步的代价与输入长度成正比。不设上限意味着单个请求
     *                       可以驱动任意大的 CPU 与内存开销。{@code MEDIUMTEXT} 能装 16MB，
     *                       但"列宽允许"不等于"应该接受"
     * @param summaryLength  列表卡片摘要长度，与 {@code post.summary VARCHAR(300)} 对齐。
     *                       调大到超过列宽会导致插入报错，因此两处必须一起改
     */
    public record Post(int maxTags, int maxBodyLength, int summaryLength) {
    }

    /**
     * 列表分页约束。
     *
     * @param defaultPageSize 未指定时使用的页大小
     * @param maxPageSize     <b>服务端强制的页大小上限</b>。没有这一条，客户端可以请求
     *                       一页 100 万条 —— 那既是一次自我发起的拒绝服务，也是一条
     *                       被忽略的深分页性能问题。超过上限时服务端按上限截断，
     *                       而不是报错：客户端要 200 条、拿到 50 条，仍然是可用的响应
     */
    public record Feed(int defaultPageSize, int maxPageSize) {
    }

    /**
     * 合成演示数据生成器。
     *
     * <p><b>默认关闭</b>。它只在需要"第一次启动就能看到完整内容"的环境里显式打开
     * （{@code application-local.yml} 打开，测试 profile 不打开）。默认关闭的理由：
     * 一个会往业务表里写数据的组件，如果在生产环境被误开启，产生的是一批看起来像真实
     * 用户的假数据 —— 清理它们的代价远高于打开开关省下的那点事。
     *
     * <p>规模可配是为了两件事：一是让首次启动足够快（几十条就够看清界面），
     * 二是让基线压测可以按需造出有统计意义的量级。两者的合理值差了几个数量级，
     * 写死任何一个都不合适。
     *
     * @param enabled      是否启用
     * @param authorCount  生成的演示作者数
     * @param postCount    生成的帖子数
     * @param commentCount 生成的评论数（顶层 + 回复的合计）
     */
    public record DemoSeed(boolean enabled, int authorCount, int postCount, int commentCount) {
    }
}
