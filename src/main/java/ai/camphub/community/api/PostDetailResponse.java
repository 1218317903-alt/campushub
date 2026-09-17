package ai.camphub.community.api;

import ai.camphub.community.app.PostDetailView;
import java.time.Instant;
import java.util.List;

/**
 * 帖子详情。
 *
 * @param publicId      对外标识
 * @param title         标题
 * @param summary       摘要
 * @param bodyHtml      服务端渲染并净化后的 HTML，<b>可直接插入页面</b>。
 *                      客户端不需要（也不应该）再做一次 Markdown 渲染 ——
 *                      那等于把"内容净化"这件事的副本散到每个客户端，
 *                      其中任何一个漏做就是一处存储型 XSS
 * @param bodyMd        Markdown 原文。回传它是为了让编辑页不必再取一次数据，
 *                      同时它也是"用户当时究竟写了什么"的原始记录。
 *                      它是同样的内容、只是另一种表示，不构成额外泄漏
 * @param categorySlug  板块对外标识
 * @param categoryName  板块展示名
 * @param author        作者信息；作者已注销时 {@code publicId} 为 null
 * @param tags          标签列表，可能为空
 * @param viewCount     浏览数
 * @param likeCount     点赞数
 * @param favoriteCount 收藏数
 * @param commentCount  评论数（含回复）
 * @param publishedAt   首次发布时间。<b>编辑不会改变它</b>
 * @param updatedAt     最后更新时间。与 {@code publishedAt} 不同即表示被编辑过
 * @param liked         当前用户是否点过赞
 * @param favorited     当前用户是否收藏过
 * @param ownedByMe     是否由当前用户发布（渲染提示，非鉴权依据）
 */
public record PostDetailResponse(
        String publicId,
        String title,
        String summary,
        String bodyHtml,
        String bodyMd,
        String categorySlug,
        String categoryName,
        AuthorResponse author,
        List<TagRefResponse> tags,
        int viewCount,
        int likeCount,
        int favoriteCount,
        int commentCount,
        Instant publishedAt,
        Instant updatedAt,
        boolean liked,
        boolean favorited,
        boolean ownedByMe
) {

    /**
     * 从应用层视图构造。
     *
     * @param view 详情视图
     * @return 响应体
     */
    public static PostDetailResponse from(PostDetailView view) {
        return new PostDetailResponse(
                view.post().publicId(),
                view.post().title(),
                view.post().summary(),
                view.post().bodyHtml(),
                view.post().bodyMd(),
                view.post().categorySlug(),
                view.post().categoryName(),
                AuthorResponse.from(view.author()),
                view.tags().stream().map(TagRefResponse::from).toList(),
                view.post().viewCount(),
                view.post().likeCount(),
                view.post().favoriteCount(),
                view.post().commentCount(),
                view.post().publishedAt(),
                view.post().updatedAt(),
                view.liked(),
                view.favorited(),
                view.ownedByCurrentUser());
    }
}
