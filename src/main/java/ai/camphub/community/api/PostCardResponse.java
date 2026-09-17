package ai.camphub.community.api;

import ai.camphub.community.app.PostCardView;
import java.time.Instant;
import java.util.List;

/**
 * 帖子列表卡片。
 *
 * <p><b>不含正文</b>：列表页渲染卡片只需要摘要。把正文也带上会让一页响应体
 * 从几 KB 变成几百 KB，而客户端一条都用不到。
 *
 * @param publicId      对外标识，详情页与所有写操作都按它寻址
 * @param title         标题
 * @param summary       摘要（服务端从正文派生）
 * @param categorySlug  板块对外标识
 * @param categoryName  板块展示名
 * @param author        作者信息；作者已注销时 {@code publicId} 为 null
 * @param tags          标签列表，可能为空
 * @param viewCount     浏览数（仅统计登录用户且按天去重）
 * @param likeCount     点赞数
 * @param favoriteCount 收藏数
 * @param commentCount  评论数（含回复）
 * @param publishedAt   发布时间
 * @param liked         当前用户是否点过赞；未登录时为 false
 * @param favorited     当前用户是否收藏过；未登录时为 false
 * @param ownedByMe     是否由当前用户发布，仅供前端决定是否显示操作入口。
 *                      <b>不是鉴权依据</b> —— 服务端每次写操作都会重新校验归属
 */
public record PostCardResponse(
        String publicId,
        String title,
        String summary,
        String categorySlug,
        String categoryName,
        AuthorResponse author,
        List<TagRefResponse> tags,
        int viewCount,
        int likeCount,
        int favoriteCount,
        int commentCount,
        Instant publishedAt,
        boolean liked,
        boolean favorited,
        boolean ownedByMe
) {

    /**
     * 从应用层视图构造。
     *
     * @param view 列表卡片视图
     * @return 响应体
     */
    public static PostCardResponse from(PostCardView view) {
        return new PostCardResponse(
                view.post().publicId(),
                view.post().title(),
                view.post().summary(),
                view.post().categorySlug(),
                view.post().categoryName(),
                AuthorResponse.from(view.author()),
                view.tags().stream().map(TagRefResponse::from).toList(),
                view.post().viewCount(),
                view.post().likeCount(),
                view.post().favoriteCount(),
                view.post().commentCount(),
                view.post().publishedAt(),
                view.liked(),
                view.favorited(),
                view.ownedByCurrentUser());
    }
}
