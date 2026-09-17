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
 * @param categorySlug 板块筛选，可为 null 表示不限
 * @param tagSlug      标签筛选，可为 null 表示不限
 * @param authorId     作者筛选（用户内部主键），可为 null 表示不限。
 *                     对外的 public_id 由调用方负责解析成内部主键，这一层不认识其它标识
 * @param sort         排序方式，不会为 null
 * @param page         页码，从 1 开始，不会小于 1
 * @param size         页大小，不会小于 1
 */
public record FeedQuery(
        String categorySlug,
        String tagSlug,
        Long authorId,
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
     * @param authorId     作者内部主键
     * @param sort         排序方式；为 null 时用 {@link PostSort#LATEST}
     * @param page         页码
     * @param size         页大小
     * @return 归一化后的查询条件
     */
    public static FeedQuery of(String categorySlug,
                              String tagSlug,
                              Long authorId,
                              PostSort sort,
                              int page,
                              int size) {
        return new FeedQuery(
                blankToNull(categorySlug),
                blankToNull(tagSlug),
                authorId,
                sort == null ? PostSort.LATEST : sort,
                Math.max(page, 1),
                Math.max(size, 1));
    }

    /**
     * 计算 SQL 的 OFFSET。
     *
     * @return 偏移量
     */
    public int offset() {
        return (page - 1) * size;
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
