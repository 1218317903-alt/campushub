package ai.camphub.community.api;

import ai.camphub.community.domain.TagAssignment;

/**
 * 帖子上的标签引用。
 *
 * <p>与 {@link TagResponse} 分开，是因为这里<b>没有使用次数</b>：
 * 一条帖子挂着的标签，"全站有多少帖子用了它"与当前上下文无关，
 * 为它多算一次聚合没有任何意义。而若图省事复用 {@link TagResponse}，
 * 就只能填一个 0 或 null —— 那会让"这个 0 到底是没有帖子用、还是没查"永远说不清。
 *
 * @param slug 对外标识
 * @param name 展示名
 */
public record TagRefResponse(String slug, String name) {

    /**
     * 从关联投影构造。
     *
     * @param assignment 关联行
     * @return 响应体
     */
    public static TagRefResponse from(TagAssignment assignment) {
        return new TagRefResponse(assignment.slug(), assignment.name());
    }
}
