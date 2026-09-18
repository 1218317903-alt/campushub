package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 笔记列表项（读模型）。
 *
 * <h2>它刻意不含 body_md / body_html</h2>
 * 与社区列表同一条纪律（ADR 0005）：它们是 {@code MEDIUMTEXT}，
 * 一页 20 条会把几百 KB 的正文搬进内存再丢掉。列表要展示的是 {@code summary}，
 * 而 {@code summary} 在写入时就从正文派生好了。
 *
 * @param id        自增主键，模块内部使用
 * @param publicId  对外标识
 * @param title     标题
 * @param summary   纯文本摘要
 * @param authorId  创建者自增主键。列表需要展示"谁写的"，由 {@code UserDirectory} 批量补齐
 * @param updatedBy 最后编辑者自增主键
 * @param createdAt 创建时间
 * @param updatedAt 最后编辑时间
 */
public record NoteSummary(
        long id,
        String publicId,
        String title,
        String summary,
        long authorId,
        long updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
