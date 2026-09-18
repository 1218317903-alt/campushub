package ai.camphub.workspace.domain;

/**
 * 待插入的空间。字段与 INSERT 的列一一对应，映射层不需要额外组装。
 *
 * <p>与 {@link Workspace} 分开的理由与 community 的 {@code PostDraft} / {@code PostDetail}
 * 同源：读模型里有 {@code createdAt} / {@code updatedAt} 这类由数据库生成的值，
 * 它们不是调用方提供的事实。放进一个叫 Draft 的类型里，
 * 正好让"哪些是服务端/数据库生成的"在类型上可见。
 *
 * <p>这里没有 {@code ownerId} 之外的成员概念：新建空间时拥有者是唯一成员，
 * 且拥有者<b>不写入成员表</b>（见 V5 的偏离说明第 3 条）。
 *
 * @param publicId   对外标识，由 {@code RandomValues.publicId()} 生成
 * @param name       名称
 * @param description 描述，可为 null
 * @param visibility 可见性
 * @param ownerId    拥有者自增主键
 */
public record WorkspaceDraft(
        String publicId,
        String name,
        String description,
        WorkspaceVisibility visibility,
        long ownerId
) {
}
