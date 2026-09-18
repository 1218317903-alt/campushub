package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 笔记详情（读模型）。
 *
 * <p>{@code bodyMd} 与 {@code bodyHtml} 一起返回：前者供编辑器绑定（编辑必须是精确往返），
 * 后者供展示直接插入。两者都由写入时的那一次渲染产出，见 ADR 0005。
 *
 * @param id          自增主键，模块内部使用
 * @param publicId    对外标识
 * @param workspaceId 所属空间自增主键。删除与二次鉴权需要它
 * @param title       标题
 * @param summary     纯文本摘要
 * @param bodyMd      Markdown 原文
 * @param bodyHtml    渲染并净化后的 HTML
 * @param authorId    创建者自增主键
 * @param updatedBy   最后编辑者自增主键
 * @param createdAt   创建时间
 * @param updatedAt   最后编辑时间
 */
public record NoteDetail(
        long id,
        String publicId,
        long workspaceId,
        String title,
        String summary,
        String bodyMd,
        String bodyHtml,
        long authorId,
        long updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
