package ai.camphub.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用级配置（前缀 {@code app}）。
 *
 * <p><b>为什么用类型化配置而不是到处 {@code @Value}</b>：配置项的集合、类型、默认值集中在一处，
 * 写错时启动即失败；且 IDE 可跳转、可补全。{@code @Value("${app.verison}")} 这种拼写错误
 * 只会在运行时以 null 的形式暴露，属于可避免的故障。
 *
 * <p>配置来源优先级（Spring Boot 标准）：环境变量 &gt; {@code application-{profile}.yml} &gt; {@code application.yml}。
 * 因此线上无需改代码，只要注入环境变量即可覆盖。
 *
 * @param name    应用名，出现在日志与 {@code /api/v1/system/info}
 * @param version 应用版本号，与 {@code pom.xml} 的版本保持同源
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String name, String version) {
}
