package ai.camphub.workspace.domain;

/**
 * 待登记的文档元数据。
 *
 * <h2>为什么没有 parseStatus</h2>
 * 状态在插入时永远是 {@link DocumentParseStatus#PENDING}（数据库列的默认值），
 * 而状态迁移属于解析流水线（Phase 05）。把它放进插入参数，
 * 等于给调用方一个"直接造出一条 READY 文档"的入口 ——
 * 那条文档没有任何可检索的内容，却声称可以检索。
 *
 * <h2>为什么 storageKey 是可空的</h2>
 * 列的取值允许 NULL，是为了让数据层能容纳"字节接入之前登记的元数据"这类历史行
 * （迁移过来的行、或被外部清理过的行）。<b>但本阶段的上传路径一定会传一个键</b>：
 * 字节先落存储、再登记元数据，因此走到插入这一步时键已经存在。
 * 类型上保留可空，是让"没有字节"这个中间态显式存在，
 * 而不是靠调用方每次都记得传一个占位串。
 *
 * @param publicId    对外标识
 * @param workspaceId 所属空间自增主键
 * @param uploaderId  上传者自增主键
 * @param name        原始文件名，仅展示用
 * @param mimeType    内容类型
 * @param sizeBytes   字节数
 * @param storageKey  对象存储键，由 {@code ObjectStorage#store} 返回
 * @param sha256      内容哈希，可为 null
 */
public record DocumentDraft(
        String publicId,
        long workspaceId,
        long uploaderId,
        String name,
        String mimeType,
        long sizeBytes,
        String storageKey,
        String sha256
) {
}
