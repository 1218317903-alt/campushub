package ai.camphub.community.api;

import ai.camphub.community.app.ReactionResult;

/**
 * 互动状态响应（点赞 / 收藏）。
 *
 * <p>四个互动接口（点赞、取消点赞、收藏、取消收藏）返回同一个形状，
 * 因此前端可以用一段逻辑处理全部四种结果：按 {@code active} 切换按钮样式，
 * 用 {@code count} 覆盖计数。若四个接口各自返回不同结构，
 * 前端就必须写四段几乎一样的更新代码。
 *
 * @param active 操作之后当前用户是否处于该状态
 * @param count  操作之后该帖的该类型计数
 */
public record ReactionResponse(boolean active, int count) {

    /**
     * 从应用层结果构造。
     *
     * @param result 互动结果
     * @return 响应体
     */
    public static ReactionResponse from(ReactionResult result) {
        return new ReactionResponse(result.active(), result.count());
    }
}
