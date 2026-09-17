package ai.camphub.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP 层通用配置（前缀 {@code app.request}）。
 *
 * <p><b>为什么单独成类，而不是并进 {@code app.security}</b>：
 * 来源 IP 既被审计日志（platform 模块）使用，也被限流（identity 模块）使用。
 * 若把它放在 identity 的配置里，platform 就得反向依赖 identity，
 * 两个模块立刻形成循环依赖 —— 而模块间循环依赖是 ArchUnit 明文拦截的构建失败条件。
 * 下沉到 {@code common}（共享内核）后，两边都只依赖 common，方向单一。
 *
 * @param trustForwardedHeaders 是否信任 {@code X-Forwarded-For} / {@code X-Real-IP} 请求头。
 *                              <b>默认 false</b>：这两个头由客户端完全控制，
 *                              在没有可信反向代理时信任它们会让"按 IP 限流"可被逐请求绕过，
 *                              并往审计日志里写入伪造来源。
 */
@ConfigurationProperties(prefix = "app.request")
public record RequestProperties(boolean trustForwardedHeaders) {
}
