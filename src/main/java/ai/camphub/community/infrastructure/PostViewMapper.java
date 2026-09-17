package ai.camphub.community.infrastructure;

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
 *
 * <h2>"今天"由数据库决定，而不是由应用算出来</h2>
 * {@code view_date} 由 SQL 里的 {@code CURRENT_DATE()} 填充。这不是偷懒，
 * 而是为了消掉一处必然会漂移的重复配置：MySQL 会话时区已经由 JDBC 连接串固定为
 * {@code Asia/Shanghai}（{@code connectionTimeZone} + {@code forceConnectionTimeZoneToSession}），
 * 若改由 Java 侧 {@code LocalDate.now(zone)} 计算，就必须在应用配置里再维护一份同样的时区，
 * 而两份配置只要有一次改动没同步，"同一天重复浏览"的判定就会在每天有 8 小时出错 ——
 * 表现为计数偏大，且没有任何报错。
 */
public interface PostViewMapper {

    /**
     * 记录一次浏览（当天同一用户重复浏览不会产生新行）。
     *
     * @param postId 帖子自增主键
     * @param userId 用户自增主键。<b>只统计登录用户</b>，匿名访问不记录 —— 理由见 V3 迁移注释
     * @return 1 表示今天首次浏览该帖（调用方据此把 {@code view_count} +1）；
     *         0 表示今天已经浏览过，计数不变
     */
    int insertIfAbsent(@Param("postId") long postId, @Param("userId") long userId);
}
