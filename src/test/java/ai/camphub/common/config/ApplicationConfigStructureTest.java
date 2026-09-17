package ai.camphub.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 主配置文件的结构断言：每一个被 {@code @ConfigurationProperties} 读取的键，
 * 都必须在 {@code application.yml} 里以**正确的层级**存在。
 *
 * <h2>它挡的是一类不报错的缺陷</h2>
 * 构造函数绑定（record 风格）最危险的地方在于：<b>属性缺失不会报错</b>。
 * 如果某个配置块被写在错误的层级 —— 例如把 {@code community:} 写在顶层，
 * 而属性类的前缀是 {@code app.community} —— 那么：
 * <ul>
 *   <li>应用照常启动，健康检查照常通过，接口照常响应；</li>
 *   <li>那个配置块的每一项都取不到值，嵌套对象组件绑成 {@code null}；</li>
 *   <li>直到某次调用才以 {@code NullPointerException} 的形式暴露，
 *       而报错位置（"某个 service 里 properties().feed() 为空"）
 *       与真正的原因（"yml 少缩进了两格"）之间隔着好几层。</li>
 * </ul>
 * 如果那个块全是环境变量占位符（例如 {@code ${APP_TRUST_FORWARDED_HEADERS:false}}），
 * 后果更隐蔽：默认值恰好等于期望值，于是没人发现<b>那个环境变量从来没有生效过</b> ——
 * 直到有人部署在反向代理后面、按文档把它设为 true、却发现按 IP 限流仍然把所有请求
 * 算在同一个来源上。
 *
 * <h2>为什么断言"键存在"而不是断言"值等于多少"</h2>
 * 值会随环境变化（测试 profile 会覆盖一部分），断言值就等于把测试绑死在某个 profile 上。
 * 而"键必须存在于这个位置"是一条与环境无关的结构事实，正是缺陷发生的那一层。
 * 真正需要断言生效值的地方（例如"服务端确实按 50 条截断"）由集成测试覆盖。
 */
class ApplicationConfigStructureTest {

    /**
     * 必须存在于 {@code application.yml} 中的键。
     *
     * <p>这里是**显式清单**而不是"扫描所有 {@code @ConfigurationProperties} 再推导"：
     * 自动推导需要反射读注解，还要处理嵌套 record 的字段名映射，
     * 而它一旦推导错了，就会安静地少查几个键 —— 与它要防的问题同性质。
     * 清单是手写的，因此漏掉一个新配置项时会有一条明确的补全动作。
     *
     * <p>键名与 {@code @ConfigurationProperties} 的 prefix 逐字对应。
     */
    private static final List<String> REQUIRED_KEYS = List.of(
            // app（AppProperties）
            "app.name",
            "app.version",

            // app.security（SecurityProperties）—— 逐个子块都点到，避免整块漏缩进
            "app.security.jwt.secret",
            "app.security.jwt.issuer",
            "app.security.jwt.access-token-ttl",
            "app.security.jwt.refresh-token-ttl",
            "app.security.password.min-length",
            "app.security.login.max-failures",
            "app.security.login.lock-duration",
            "app.security.rate-limit.login.capacity",
            "app.security.rate-limit.register.capacity",
            "app.security.rate-limit.refresh.capacity",

            // app.request（RequestProperties）
            "app.request.trust-forwarded-headers",

            // app.community（CommunityProperties）
            "app.community.post.max-tags",
            "app.community.post.max-body-length",
            "app.community.post.summary-length",
            "app.community.feed.default-page-size",
            "app.community.feed.max-page-size",

            // app.demo-seed（DemoSeedProperties）
            "app.demo-seed.enabled",
            "app.demo-seed.author-count",
            "app.demo-seed.post-count",
            "app.demo-seed.comment-count",
            "app.demo-seed.password");

    /**
     * 断言所有必需键都存在于主配置文件。
     *
     * @throws IOException 配置文件无法读取
     */
    @Test
    @DisplayName("每个属性类的前缀都要在 application.yml 里以正确层级存在")
    void shouldDeclareEveryConfigurationPropertyAtCorrectLevel() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertThat(sources)
                .as("application.yml 应当能被解析为至少一个属性源")
                .isNotEmpty();

        PropertySource<?> root = sources.getFirst();
        List<String> missing = REQUIRED_KEYS.stream()
                .filter(key -> !root.containsProperty(key))
                .toList();

        assertThat(missing)
                .as("""
                        以下键在 application.yml 中不存在。最常见的原因是配置块被写在了错误的层级
                        （例如顶层 community: 而不是 app.community:）—— 这种情况 Spring 不会报错，
                        只会让整个块静默失效。请核对缩进层级与 @ConfigurationProperties 的 prefix。""")
                .isEmpty();
    }
}
