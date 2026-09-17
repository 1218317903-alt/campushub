package ai.camphub.system.api;

import java.time.Instant;

/**
 * 系统信息响应体。
 *
 * <p><b>为什么成功响应不套 {code, data, message} 信封</b>：
 * 错误响应需要额外的元信息（错误码、traceId、字段明细），所以错误有结构；
 * 而成功响应本身的 body 就是结果，再套一层只会让前端多一次解包、让 OpenAPI 文档多一层缩进。
 * 需要链路信息时，所有响应（含成功）的响应头里都有 {@code X-Trace-Id} —— 用正确的载体承载正确的信息。
 *
 * @param application    应用名
 * @param version        应用版本（与 pom 版本同源）
 * @param profiles       生效的 Spring Profile
 * @param javaVersion    运行时 Java 版本
 * @param serverTime     服务端当前时间（UTC）
 * @param schemaBaseline 数据库 schema 基线版本（来自 app_metadata 表，用于确认迁移已生效）
 */
public record SystemInfoResponse(
        String application,
        String version,
        String profiles,
        String javaVersion,
        Instant serverTime,
        String schemaBaseline
) {
}
