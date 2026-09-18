package ai.camphub.workspace.domain;

/**
 * 空间内可被单独寻址的资源种类。
 *
 * <h2>它的唯一用途是让 404 语义可说清</h2>
 * 第二层防线在"调用者看不到这个空间"时返回 {@code 404} 而不是 {@code 403}
 * （见 {@code docs/03-domain-permission.md §10.3} 与 {@code docs/resource-authorization.md}）。
 * 但"看不到空间"与"看不到空间里的这条文档"是两件事，日志与审计里必须能区分 ——
 * 否则排查"某人说他的文档不见了"时，无法判断是空间不可见还是资源本身不存在。
 *
 * <p>把它做成枚举而不是字符串，是为了让审计与日志里的取值受编译期约束：
 * 字符串形式的资源类型在两年后会变成 {@code "doc"} / {@code "document"} / {@code "Document"}
 * 三种写法并存，而它们无法被任何断言覆盖。
 */
public enum WorkspaceResourceType {

    /** 空间本身。 */
    WORKSPACE,

    /** 空间内笔记。 */
    NOTE,

    /** 空间内文档。 */
    DOCUMENT
}
