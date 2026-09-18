package ai.camphub.workspace.domain;

/**
 * 待写入的笔记。创建与编辑共用 —— 两者要写的列相同，因此不需要第二个类型。
 *
 * <p>{@code summary} 与 {@code bodyHtml} 都是<b>服务端从 {@code bodyMd} 派生</b>的，
 * 不是调用方提供的事实。把它们放进 Draft 里，让"派生值由服务端负责"这件事在类型上可见。
 *
 * @param publicId    对外标识
 * @param workspaceId 所属空间自增主键
 * @param authorId    创建者自增主键。<b>编辑时不得修改</b>：它是删除权限判定的依据，
 *                    被改写等于把别人的笔记"过户"到自己名下
 * @param updatedBy   本次写入的编辑者自增主键
 * @param title       标题
 * @param summary     摘要，由 {@code MarkdownRenderer#summarize} 派生
 * @param bodyMd      Markdown 原文
 * @param bodyHtml    渲染并净化后的 HTML，由 {@code MarkdownRenderer#render} 派生
 */
public record NoteDraft(
        String publicId,
        long workspaceId,
        long authorId,
        long updatedBy,
        String title,
        String summary,
        String bodyMd,
        String bodyHtml
) {
}
