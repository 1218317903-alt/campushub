package ai.camphub.community.domain;

/**
 * 帖子与标签的关联（查询投影）。
 *
 * <p>它的存在是为了让"取一批帖子的标签"成为<b>一次</b>查询。列表页每页 20 条帖子，
 * 若逐条去取标签就是 20 次往返（典型的 N+1），在 Phase 03 的基线压测里会立刻显形。
 * 这里用 {@code WHERE post_id IN (...)} 一次取回全部关联行，再由应用层按 postId 分组。
 *
 * <p>刻意不用 {@code GROUP_CONCAT} 在一次查询里把标签拼成字符串：
 * {@code group_concat_max_len} 有默认上限（1024 字节），超长会被<b>静默截断</b> ——
 * 表现为"有些帖子的标签莫名其妙少了几个"，属于极难定位的一类缺陷。
 * 多一次往返换掉这类隐性截断是划算的。
 *
 * @param postId 帖子自增主键
 * @param tagId  标签自增主键
 * @param slug   标签对外标识
 * @param name   标签展示名
 */
public record TagAssignment(
        long postId,
        long tagId,
        String slug,
        String name
) {
}
