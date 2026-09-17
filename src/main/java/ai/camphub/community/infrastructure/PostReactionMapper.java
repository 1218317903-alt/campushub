package ai.camphub.community.infrastructure;

import ai.camphub.community.domain.ReactionType;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code post_reaction} 表访问接口（点赞 / 收藏的明细）。
 *
 * <h2>幂等由数据库的唯一键保证，不由"先查再写"保证</h2>
 * 表的主键是 {@code (user_id, post_id, type)}。因此"同一个人对同一条帖子同一类型
 * 只能有一条记录"这条业务约束是数据库强制的，不依赖服务层的判断。
 * 服务层因此可以放心地"直接插入、把重复键当成功"：
 * <ul>
 *   <li>"先查再写"在并发下必然漏 —— 两个请求都查到"不存在"，然后都插入，
 *       一个成功一个失败，而失败的那个会变成一次用户可见的错误。</li>
 *   <li>直接插入 + 捕获重复键则天然幂等：第二次点击就是一次被识别为
 *       "已经是这个状态"的重复键冲突，直接返回成功。</li>
 * </ul>
 *
 * <p>这里刻意<b>不</b>用 {@code INSERT IGNORE}：它会连外键错误、数据截断错误一起吞掉，
 * 于是"插入了一条指向不存在帖子的互动"这种真实缺陷会静默消失。
 * 只捕获重复键这一个异常，其余异常照常抛出 —— 这正是"精确捕获"的价值。
 */
public interface PostReactionMapper {

    /**
     * 插入一条互动明细。
     *
     * <p>重复键会抛 {@code DuplicateKeyException}，由调用方按"已处于该状态"处理。
     *
     * @param userId 用户自增主键
     * @param postId 帖子自增主键
     * @param type   互动类型
     * @return 影响行数（正常为 1）
     */
    int insert(@Param("userId") long userId,
               @Param("postId") long postId,
               @Param("type") ReactionType type);

    /**
     * 删除一条互动明细。
     *
     * @param userId 用户自增主键
     * @param postId 帖子自增主键
     * @param type   互动类型
     * @return 影响行数；本来就是未点赞状态时为 0（幂等）
     */
    int delete(@Param("userId") long userId,
               @Param("postId") long postId,
               @Param("type") ReactionType type);

    /**
     * 查询当前用户在一批帖子中，哪些已经产生了指定类型的互动。
     *
     * <p>列表页需要给每条帖子标出"我是否点过赞 / 收过藏"，因此要按整页帖子批量查，
     * 而不是逐条判断。返回的是<b>帖子 id 集合</b>而不是明细行：调用方只需要知道
     * "在不在里面"，返回完整行只会多构造一堆用不上的对象。
     *
     * <p><b>调用方必须保证集合非空</b>。
     *
     * @param userId  用户自增主键
     * @param postIds 帖子自增主键集合（非空）
     * @param type    互动类型
     * @return 命中的帖子 id 列表
     */
    List<Long> findReactedPostIds(@Param("userId") long userId,
                                 @Param("postIds") Collection<Long> postIds,
                                 @Param("type") ReactionType type);
}
