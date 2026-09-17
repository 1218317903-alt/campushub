package ai.camphub.community.domain;

/**
 * 某条顶层评论的回复数（查询投影）。
 *
 * <p>存在的唯一目的是让"一页顶层评论各自的回复数"成为<b>一次</b>查询：
 * 逐条 COUNT 就是 N+1，而给 comment 表加一个 {@code reply_count} 冗余列则意味着
 * 每次回复、每次删除都要记得维护它 —— 一条只为了省一次 GROUP BY 而引入的长期一致性负担。
 *
 * @param parentId   顶层评论主键
 * @param replyCount 其下未被删除的回复数
 */
public record ReplyCount(long parentId, long replyCount) {
}
