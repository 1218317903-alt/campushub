package ai.camphub.community.domain;

import java.time.Instant;

/**
 * 帖子列表项（不含正文）。
 *
 * <h2>为什么它与 {@link PostDetail} 是两个类型，而不是一个带可空正文的类型</h2>
 * 列表页要读的列与详情页完全不同：列表只读摘要，正文是 {@code MEDIUMTEXT} ——
 * 一页 20 条帖子如果把正文字段一起查出来，等于为了渲染卡片搬了几百 KB 的数据。
 * 用"一个类型 + 正文可为 null"来表达会引入一个必须靠约定维持的不变量
 * （"列表里的实例正文一定是 null"），而领域模型应该让非法状态无法表示。
 *
 * <p>与数据库行一一对应，由 MyBatis 的构造器映射直接填充。分类的 slug 与名称来自
 * join，不在 post 表里 —— 这样列表渲染不需要为每条帖子再查一次分类。
 *
 * @param id            自增主键，仅内部使用（互动接口用它做外键）
 * @param publicId      对外标识，接口路径中使用它而不是 id
 * @param title         标题
 * @param summary       摘要，发布/编辑时从正文派生
 * @param categorySlug  板块对外标识
 * @param categoryName  板块展示名
 * @param authorId      作者自增主键。作者展示信息（昵称、头像）由调用方通过
 *                      identity 模块提供的批量接口换取，community 不读 user 表
 * @param viewCount     浏览数（仅统计登录用户且按天去重）
 * @param likeCount     点赞数
 * @param favoriteCount 收藏数
 * @param commentCount  评论数（含回复）
 * @param publishedAt   发布时间
 */
public record PostSummary(
        long id,
        String publicId,
        String title,
        String summary,
        String categorySlug,
        String categoryName,
        long authorId,
        int viewCount,
        int likeCount,
        int favoriteCount,
        int commentCount,
        Instant publishedAt
) {
}
