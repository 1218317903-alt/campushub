package ai.camphub.community.api;

import ai.camphub.community.domain.Category;

/**
 * 板块响应。
 *
 * @param slug        对外标识，前端筛选与路由使用它（不暴露内部主键）
 * @param name        展示名
 * @param description 一句话说明，可为 null
 * @param postCount   该板块下未被删除的帖子数。用来让用户在选择板块前就知道
 *                    "那里有多少内容"，避免点进一个空板块
 */
public record CategoryResponse(
        String slug,
        String name,
        String description,
        long postCount
) {

    /**
     * 从领域模型构造。
     *
     * @param category 板块
     * @return 响应体
     */
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.slug(), category.name(), category.description(), category.postCount());
    }
}
