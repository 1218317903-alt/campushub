package ai.camphub.community.app;

import ai.camphub.community.domain.Comment;
import ai.camphub.identity.domain.UserBrief;

/**
 * 评论区里的一条评论（顶层评论或回复）。
 *
 * @param comment    评论本体
 * @param author     作者展示信息，账号被注销时为 null
 * @param replyCount 该评论收到的回复数。回复本身恒为 0 —— 讨论只有两层，
 *                   一条回复下面不会再有回复（见 {@code V3__community.sql} 的说明）。
 *                   这个数字是列表查询时一次性算出来的，而不是给 comment 表加一个冗余计数列
 */
public record CommentView(
        Comment comment,
        UserBrief author,
        long replyCount
) {
}
