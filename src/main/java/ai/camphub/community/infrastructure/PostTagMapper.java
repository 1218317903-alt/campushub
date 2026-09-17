package ai.camphub.community.infrastructure;

import ai.camphub.community.domain.TagAssignment;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code post_tag} 表访问接口。
 *
 * <p>这是 <b>{@code community} 模块内部的关联表</b>，因此没有"模块边界"问题。
 * 但要留意它与 {@code tag} 表的区别：{@code tag} 的读取（列表、按 slug 查）走
 * {@link TagMapper}，而"某帖有哪些标签"走这里 —— 后者永远以帖子为起点。
 */
public interface PostTagMapper {

    /**
     * 批量写入帖子-标签关联。
     *
     * <p>调用方在写入前会先清空该帖的旧关联，因此这里不需要处理重复键。
     *
     * <p>标签 id 在 Java 侧统一用 {@code long} 承载（{@code INT UNSIGNED} 的上限超出
     * {@code int} 的正数范围，而项目其它模块的主键也都是 long）。让标识类型在全项目一致，
     * 比在这里省几个字节重要 —— 混用会在跨模块边界反复产生无意义的窄化转换。
     *
     * @param postId 帖子自增主键
     * @param tagIds 标签自增主键集合（非空）
     * @return 影响行数
     */
    int insertBatch(@Param("postId") long postId, @Param("tagIds") Collection<Long> tagIds);

    /**
     * 清除某帖的全部标签关联。
     *
     * @param postId 帖子自增主键
     * @return 影响行数
     */
    int deleteByPostId(@Param("postId") long postId);

    /**
     * 批量查询一批帖子的标签。
     *
     * <p>一次查询覆盖整页帖子，避免逐帖查询造成的 N+1。
     * <b>调用方必须保证集合非空</b>。
     *
     * @param postIds 帖子自增主键集合（非空）
     * @return 关联行，调用方按 postId 分组
     */
    List<TagAssignment> findByPostIds(@Param("postIds") Collection<Long> postIds);
}
