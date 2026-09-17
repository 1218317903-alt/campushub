package ai.camphub.community.domain;

/**
 * 列表排序方式。
 *
 * <h2>为什么是枚举而不是一个自由字符串</h2>
 * 排序字段最终要拼进 {@code ORDER BY}，而 SQL 的参数占位符 {@code #{}} 不能用于列名 ——
 * 意味着无论如何都有一段 SQL 是拼接出来的。把可选值收敛成枚举，就让"拼接的内容"
 * 只可能来自这个文件里写死的两个分支，用户输入在到达 SQL 之前就已经被限制住了。
 *
 * <p>{@code LATEST} 是默认值：内容社区的首要诉求是"有什么新东西"，
 * 而热度排序在没有真实的互动量之前也不具备参考意义。
 */
public enum PostSort {

    /**
     * 最新：按发布时间倒序。
     *
     * <p>对应索引 {@code idx_post_published} / {@code idx_post_category_published}，
     * 可以直接沿索引有序扫描取前 N 条。
     */
    LATEST,

    /**
     * 最热：按点赞数倒序，点赞数相同时新的在前。
     *
     * <p>用已落库的 {@code like_count} 而不是引入 {@code hot_score}：
     * 后者的公式（互动加权 + 时间衰减）需要定时重算，属于 Phase 09 的优化项。
     * 现在"按点赞数排序"是一个语义清楚、任何人都能解释的数字。
     */
    HOT
}
