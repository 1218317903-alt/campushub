package ai.camphub.community.config;

import ai.camphub.community.domain.MarkdownRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 社区模块的装配。
 *
 * <h2>为什么这里只有一个 Bean，而不是把 {@code MarkdownRenderer} 直接标注为组件</h2>
 * 因为 {@link MarkdownRenderer} 位于 {@code domain} 包，而领域层必须保持对框架零依赖
 * （由 ArchUnit 的 {@code domainMustStayFrameworkFree} 断言在构建期强制）。
 * 领域类负责表达规则，容器负责把规则装配起来 —— 这条分工让领域类可以直接被
 * {@code new} 出来做单元测试，不需要启动任何 Spring 上下文。
 *
 * <p>把装配显式写在这里，也让"渲染器是单例、Parser 与净化策略只构建一次"这件事
 * 成为代码里看得见的事实，而不是一个需要读者去推断的默认行为。
 */
@Configuration
public class CommunityConfig {

    /**
     * Markdown 渲染器。无状态且线程安全，因此注册为单例复用。
     *
     * @return 渲染器
     */
    @Bean
    public MarkdownRenderer markdownRenderer() {
        return new MarkdownRenderer();
    }
}
