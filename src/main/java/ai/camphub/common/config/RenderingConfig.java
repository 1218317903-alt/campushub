package ai.camphub.common.config;

import ai.camphub.common.rendering.MarkdownRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内容渲染边界的装配点。
 *
 * <h2>为什么这里只有一个 Bean，而不是把 {@link MarkdownRenderer} 标成组件</h2>
 * 因为它必须可以在不启动 Spring 的情况下被直接 {@code new} 出来做单元测试
 * （净化白名单的断言不该依赖容器），而 {@code @Component} 会把它和框架绑在一起。
 * 装配写在配置类里，让"谁需要它、在哪里提供"只有一处答案。
 *
 * <h2>为什么这个 Bean 不在任何业务模块里</h2>
 * 见 {@link MarkdownRenderer} 的类注释：它是跨模块的内容边界。
 * 若它由某个业务模块提供，其他模块为了拿到它就必须依赖那个模块，
 * 而项目已规划的"空间内容发布到社区"会形成反向依赖，两者同时存在即是循环。
 *
 * <p>容器里只会有一个 {@code MarkdownRenderer} Bean。多一个实例就多一份白名单，
 * 而两份白名单不可能长期一致 —— 差异会出现在"某个标签在一处被放行、另一处没有"，
 * 并且以 XSS 的形式暴露出来。因此这里没有提供任何参数化构造重载。
 */
@Configuration
public class RenderingConfig {

    /**
     * Markdown 渲染与净化器。
     *
     * <p>单例复用：{@code Parser} / {@code HtmlRenderer} / {@code PolicyFactory}
     * 都是无状态且线程安全的，而构建它们有可观的固定开销，
     * 每次调用新建会让发布内容这条写路径白付一遍代价。
     *
     * @return 渲染器
     */
    @Bean
    public MarkdownRenderer markdownRenderer() {
        return new MarkdownRenderer();
    }
}
