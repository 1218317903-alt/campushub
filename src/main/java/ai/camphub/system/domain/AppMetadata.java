package ai.camphub.system.domain;

/**
 * 应用元数据（运维用的键值对）。
 *
 * <p>用途：记录与「部署实例」相关的、不属于任何业务域的技术性事实，例如
 * schema 基线版本、首次初始化时间。它回答的是"这套环境现在处在什么状态"，
 * 而不是业务问题。
 *
 * <p><b>为什么不直接读 Flyway 自己的 {@code flyway_schema_history}</b>：
 * 那是 Flyway 的内部表，其结构由第三方库决定，升级 Flyway 时可能变化。
 * 依赖它会把我们的代码与第三方实现细节绑死。用一个自己的表来承载
 * "对外承诺的 schema 版本"，语义清晰且不受依赖升级影响。
 *
 * @param metaKey   键
 * @param metaValue 值
 */
public record AppMetadata(String metaKey, String metaValue) {
}
