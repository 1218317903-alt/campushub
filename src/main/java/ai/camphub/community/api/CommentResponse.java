package ai.camphub.community.api;

import ai.camphub.community.app.CommentView;
import java.time.Instant;

/**
 * 评论响应。
 *
 * @param publicId   对外标识，删除与展开回复都按它寻址
 * @param author     作者信息；作者已注销时 {@code publicId} 为 null
 * @param body       纯文本正文。<b>不是 HTML</b>：评论不做 Markdown 渲染，
 *                   前端应以文本节点渲染（不是 {@code v-html}），
 *                   从根上不存在 XSS 的可能。换行由 CSS 的 {@code white-space} 处理
 * @param replyCount 该评论收到的回复数。回复本身恒为 0
 * @param createdAt  发布时间
 * @param ownedByMe  是否由当前用户发布（渲染提示，非鉴权依据）
 */
public record CommentResponse(
        String publicId,
        AuthorResponse author,
        String body,
        long replyCount,
        Instant createdAt,
        boolean ownedByMe
) {

    /**
     * 从应用层视图构造。
     *
     * @param view          评论视图
     * @param currentUserId 当前用户自增主键；未登录时为 null
     * @return 响应体
     */
    public static CommentResponse from(CommentView view, Long currentUserId) {
        return new CommentResponse(
                view.comment().publicId(),
                AuthorResponse.from(view.author()),
                view.comment().body(),
                view.replyCount(),
                view.comment().createdAt(),
                currentUserId != null && currentUserId == view.comment().authorId());
    }
}
