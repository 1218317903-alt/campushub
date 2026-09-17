package ai.camphub.community.app;

/**
 * 一次互动操作之后的当前状态。
 *
 * <h2>为什么要回传计数，而不是让客户端自己 +1</h2>
 * 客户端乐观更新时会把数字先加上去，但它的本地值可能本来就是旧的
 * （另一个人在别处点过赞）。回传服务端的权威计数让界面能立刻纠正，
 * 而不必重新拉一次帖子详情 —— 一次互动本来就只需要一次请求。
 *
 * @param active 操作之后当前用户是否处于该状态（点赞 / 收藏）
 * @param count  操作之后该帖的该类型计数
 */
public record ReactionResult(boolean active, int count) {
}
