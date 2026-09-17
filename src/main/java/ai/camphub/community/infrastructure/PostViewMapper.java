package ai.camphub.community.infrastructure;

import java.time.LocalDate;
import org.apache.ibatis.annotations.Param;

/**
 * {@code post_view_daily} 表访问接口（浏览去重明细）。
 *
 * <h2>这里反而适合用 {@code INSERT IGNORE}</h2>
 * 与 {@link PostReactionMapper} 的取舍相反，原因是"重复"在两条路径上的性质不同：
 * <ul>
 *   <li>点赞重复是<b>用户操作的结果</b>（连点两下），需要被明确区分出来，
 *       因为它决定计数要不要 +1。</li>
 *   <li>浏览重复是<b>预期中的常态</b>（同一个人同一天第二次打开这条帖子），
 *       它不表达任何异常。用 {@code INSERT IGNORE} + 影响行数判断"这次是不是今天第一次"，
 *       比捕获异常更贴合语义，也避免了在最高频的读路径上抛异常。</li>
 * </ul>
 * 外键错误的担忧在这里不成立：调用方一定是先成功读到了帖子，才可能记录浏览。
 */
public interface PostViewMapper {

    /**
     * 记录一次浏览（当天同一用户重复浏览不会产生新行）。
     *
     * @param postId   帖子自增主键
     * @param viewDate 日期（按 {@code Asia/Shanghai} 切分，与 JDBC 连接串的会话时区一致）
     * @param userId   用户自增主键。<b>只统计登录用户</b>，匿名访问不记录 —— 理由见 V3 迁移注释
     * @return 1 表示今天首次浏览该帖（调用方据此把 {@code view_count} +1）；
     *         0 表示今天已经浏览过，计数不变
     */
    int insertIfAbsent(@Param("postId") long postId,
                       @Param("viewDate") LocalDate viewDate,
                       @Param("userId") long userId);
}
