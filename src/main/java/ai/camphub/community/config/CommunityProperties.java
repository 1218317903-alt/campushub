package ai.camphub.community.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 社区模块配置（前缀 {@code app.community}）。
 *
 * <h2>配置没绑上时不会报错，这是本类最需要防的一件事</h2>
 * 构造函数绑定（record）的特点是：<b>属性缺失时不会报错，只是绑不上</b> ——
 * 嵌套的对象组件会变成 {@code null}，直到某次调用才以 NPE 的形式暴露出来。
 * 更糟的是，若 {@code application.yml} 把路径写成了顶层 {@code community:}
 * 而不是 {@code app.community:}，整个配置块都会被静默忽略，
 * 而应用启动、健康检查、其它接口全部正常。
 *
 * <p>因此它由 {@code CommunityPropertiesIT} 断言"生效值确实来自配置文件" ——
 * 这类缺陷只能靠断言真实生效值的测试来挡，代码评审看不出来。
 *
 * <h2>为什么上限写在这里而不是散在代码里</h2>
 * 下面几个数字（标签最多几个、正文最长多少、一页最多多少条）都是<b>资源保护参数</b>：
 * 它们决定了单个请求能让服务端做多少工作。压测时一定会调整它们，而"改配置"和
 * "改代码重新构建"是两种成本完全不同的操作。集中成类型化配置后，既能被环境变量覆盖，
 * 也能在评审时一眼看全 —— 和 {@code SecurityProperties} 的取舍一致。
 *
 * @param post 帖子相关的输入约束
 * @param feed 列表分页约束
 */
@ConfigurationProperties(prefix = "app.community")
public record CommunityProperties(
        Post post,
        Feed feed
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
}
