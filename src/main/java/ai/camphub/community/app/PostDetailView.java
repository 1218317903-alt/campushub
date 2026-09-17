package ai.camphub.community.app;

import ai.camphub.community.domain.PostDetail;
import ai.camphub.community.domain.TagAssignment;
import ai.camphub.identity.domain.UserBrief;
import java.util.List;

/**
 * 帖子详情页所需的完整信息。
 *
 * <p>与 {@link PostCardView} 是同一个思路（把需要批量补齐的信息在应用层装配好），
 * 差别在于它带正文，因此只在单条查询时使用。
 *
 * @param post               帖子详情（含 Markdown 原文与净化后的 HTML）
 * @param tags               该帖标签
 * @param author             作者展示信息，账号被注销时为 null
 * @param liked              当前用户是否点过赞
 * @param favorited          当前用户是否收藏过
 * @param ownedByCurrentUser 是否由当前用户发布（渲染提示，非鉴权依据）
 */
public record PostDetailView(
        PostDetail post,
        List<TagAssignment> tags,
        UserBrief author,
        boolean liked,
        boolean favorited,
        boolean ownedByCurrentUser
) {
}
