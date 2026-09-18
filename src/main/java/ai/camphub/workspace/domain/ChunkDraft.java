package ai.camphub.workspace.domain;

/**
 * 待写入的分块。
 *
 * <h2>为什么在写入时重新算 charCount，而不是让调用方传</h2>
 * 它与 {@code content} 是同一份事实的两种表示。允许它们分别传入，
 * 就意味着允许一个"说 800 字、实际 1200 字"的行存在 ——
 * 而它不会被任何约束拦下（列上没有一致性检查），只会让将来按 charCount
 * 过滤的查询返回与内容不符的结果。
 *
 * <p>因此构造时就算好，从源头消灭这种不一致的可能。代价是每次构造都要
 * 扫一遍字符串（{@link String#length()} 是 O(1)，这一步并不真的遍历）。
 *
 * @param documentId  所属文档自增主键
 * @param workspaceId 所属空间自增主键
 * @param ordinal     块序号，自 0 连续递增
 * @param heading     所属标题路径，可为 null
 * @param content     块内容
 */
public record ChunkDraft(
        long documentId,
        long workspaceId,
        int ordinal,
        String heading,
        String content
) {

    /**
     * 内容字符数。
     *
     * @return 字符数
     */
    public int charCount() {
        return content.length();
    }
}
