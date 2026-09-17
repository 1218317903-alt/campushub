package ai.camphub.community.api;

import ai.camphub.community.domain.Tag;

/**
 * 标签响应（带使用次数）。
 *
 * @param slug      对外标识
 * @param name      展示名
 * @param postCount 使用该标签的未删除帖子数
 */
public record TagResponse(String slug, String name, long postCount) {

    /**
     * 从领域模型构造。
     *
     * @param tag 标签
     * @return 响应体
     */
    public static TagResponse from(Tag tag) {
        return new TagResponse(tag.slug(), tag.name(), tag.postCount());
    }
}
