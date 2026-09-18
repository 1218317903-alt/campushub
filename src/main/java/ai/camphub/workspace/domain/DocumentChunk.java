package ai.camphub.workspace.domain;

/**
 * 文档的一个解析分块（读模型）。
 *
 * <h2>为什么它带着 workspaceId</h2>
 * 这个字段在 {@code document} 表上已经有了（分块属于文档，文档属于空间），
 * 这里是一份刻意的冗余。相关的取舍写在 {@code V6__document_pipeline.sql} 与
 * {@code docs/document-pipeline.md}：文档在生命周期内不可能跨空间移动，
 * 因此这份冗余不会失同步；换来的是第三层防线（数据范围过滤）可以直接作用在
 * 分块表上，而不必依赖"记得 JOIN 一次 document"。
 *
 * @param id          自增主键
 * @param documentId  所属文档自增主键
 * @param workspaceId 所属空间自增主键
 * @param ordinal     块序号，自 0 连续递增
 * @param heading     所属标题路径，可为 null
 * @param content     块内容
 * @param charCount   字符数
 */
public record DocumentChunk(
        long id,
        long documentId,
        long workspaceId,
        int ordinal,
        String heading,
        String content,
        int charCount
) {
}
