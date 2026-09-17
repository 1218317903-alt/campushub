package ai.camphub.community.domain;

import java.time.Instant;

/**
 * 帖子详情（含正文）。
 *
 * <p>字段与 {@link PostSummary} 有意重复：两者各自对应一条查询、一套列集合，
 * 由 MyBatis 的构造器映射直接填充。若为了消除重复而做成嵌套或继承，
 * 就需要在映射层额外写一次组装代码 —— 用映射层的复杂度换领域模型的对称性，
 * 不划算。重复的是字段声明，不是逻辑，因此不存在"改一处漏一处"的风险。
 *
 * @param id            自增主键
 * @param publicId      对外标识
 * @param title         标题
 * @param summary       摘要
 * @param bodyMd        Markdown 原文（编辑时的事实来源）
 * @param bodyHtml      服务端渲染并净化后的 HTML
 * @param categorySlug  板块对外标识
 * @param categoryName  板块展示名
 * @param authorId      作者自增主键
 * @param viewCount     浏览数
 * @param likeCount     点赞数
 * @param favoriteCount 收藏数
 * @param commentCount  评论数（含回复）
 * @param publishedAt   发布时间
 * @param updatedAt     最后更新时间（是否被编辑过由它与 publishedAt 是否相等体现）
 */
public record PostDetail(
        long id,
        String publicId,
        String title,
        String summary,
        String bodyMd,
        String bodyHtml,
        String categorySlug,
        String categoryName,
        long authorId,
        int viewCount,
        int likeCount,
        int favoriteCount,
        int commentCount,
        Instant publishedAt,
        Instant updatedAt
) {
}
