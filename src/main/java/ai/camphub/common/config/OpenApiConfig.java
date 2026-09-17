package ai.camphub.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 文档配置（springdoc-openapi）。
 *
 * <p>作用：让前后端以同一份契约协作。错误响应结构（{@code ApiError}）也在这里被显式描述，
 * 避免前端靠"试出来"理解错误格式。
 *
 * <p>访问路径：{@code /swagger-ui.html}、OpenAPI JSON 在 {@code /v3/api-docs}。
 * 这两个路径属于开发期工具，生产环境应关闭（在 prod profile 中 {@code springdoc.api-docs.enabled=false}）。
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /**
     * 定义 OpenAPI 文档元信息。
     *
     * @param appProperties 应用配置，保证版本号与运行时一致
     * @return OpenAPI 定义
     */
    @Bean
    public OpenAPI camphubOpenApi(AppProperties appProperties) {
        return new OpenAPI().info(new Info()
                .title("CampusHub AI API")
                .version(appProperties.version())
                .description("""
                        面向高校学生及年轻学习者的 AI 知识协作、内容社区与智能信息发现平台。

                        统一错误响应结构（所有非 2xx 响应）：code / message / traceId / timestamp / path / details。
                        traceId 同时出现在响应头 X-Trace-Id 中，报错时可直接用于定位日志。
                        """)
                .license(new License().name("Private / All rights reserved")));
    }
}
