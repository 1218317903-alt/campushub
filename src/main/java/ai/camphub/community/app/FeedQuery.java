package ai.camphub.community.app;

import ai.camphub.community.domain.PostSort;

/**
 * 帖子列表的查询条件。
 *
 * <p>刻意做成一个记录而不是把参数摊在方法签名上：参数一多，
 * 调用点就变成一串位置相关的值（{@code list(null, "java", null, LATEST, 1, 20)}），
 * 读的人必须回去数第几个参数是什么，写的人则可能在两个同类型参数之间写反顺序 ——
 * 而这类错误编译器不会报，只会表现为"筛选条件不生效"。
 *
 * <p>刻意<b>没有</b>作者筛选参数。做"某个人的帖子列表"需要先把对外的 public_id
 * 解析成内部主键（那是 identity 模块的职责），而本阶段的界面上没有个人主页 ——
 * 加一个暂时没有任何调用方的参数，只会让查询条件多一个没人验证过的分支。
 * 需要它的时候再连同解析能力一起加。
 *
 * @param categorySlug 板块筛选，可为 null 表示不限
 * @param tagSlug      标签筛选，可为 null 表示不限
 * @param sort         排序方式，不会为 null
 * @param page         页码，从 1 开始，不会小于 1
 * @param size         页大小，不会小于 1
 */
public record FeedQuery(
        String categorySlug,
        String tagSlug,
        PostSort sort,
        int page,
        int size
) {

    /**
     * 构造查询条件，并对分页与筛选参数做归一化。
     *
     * <p>修正而不是报错，是为了让"前端传了 page=0"这种显而易见的笔误不至于变成
     * 一个用户可见的失败。页大小超过上限时由服务层按上限截断，同样不报错 ——
     * 客户端要 200 条、拿到 50 条，仍然是一个可用的响应。
     *
     * @param categorySlug 板块筛选，空白视为未指定
     * @param tagSlug      标签筛选，空白视为未指定
     * @param sort         排序方式；为 null 时用 {@link PostSort#LATEST}
     * @param page         页码
     * @param size         页大小
     * @return 归一化后的查询条件
     */
    public static FeedQuery of(String categorySlug,
                              String tagSlug,
                              PostSort sort,
                              int page,
                              int size) {
        return new FeedQuery(
                blankToNull(categorySlug),
                blankToNull(tagSlug),
                sort == null ? PostSort.LATEST : sort,
                Math.max(page, 1),
                Math.max(size, 1));
    }

    /**
     * 计算 SQL 的 OFFSET。
     *
     * @param effectiveSize 服务层截断后的实际页大小
     * @return 偏移量
     */
    public long offset(int effectiveSize) {
        return (long) (page - 1) * effectiveSize;
    }

    /**
     * 把空白字符串当作"未指定"。
     *
     * <p>必要性来自真实的调用形态：前端的筛选框为空时会发出
     * {@code ?category=&tag=}，此时参数值是空串而不是缺失。若不归一化，
     * 查询会变成 {@code WHERE c.slug = ''}，结果是空列表 ——
     * 表现为"什么都没选却什么都查不到"，而排查它需要一路看到 SQL 才明白。
     *
     * @param value 原始值
     * @return null 或去空白后的值
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
