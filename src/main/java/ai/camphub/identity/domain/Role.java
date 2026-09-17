package ai.camphub.identity.domain;

/**
 * 平台级角色。
 *
 * <p><b>范围限定</b>：这里只承载<b>平台级</b>角色（如 {@code USER}）。
 * {@code WORKSPACE_MEMBER} / {@code WORKSPACE_ADMIN} 这类<b>上下文角色</b>不属于本模型 ——
 * 它们是 {@code (user, workspace)} 上的关系属性，落在 workspace 域的成员表里。
 * 把上下文角色做成全局角色，会直接产生"在 A 空间是管理员，于是能管 B 空间"的越权，
 * 这是同类系统里最常见的一类设计错误（见 docs/03-domain-permission.md §10.1）。
 *
 * @param id   主键
 * @param code 角色码，代码中按它引用（不依赖自增 id）
 * @param name 展示名
 */
public record Role(short id, String code, String name) {
}
