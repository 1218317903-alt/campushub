package ai.camphub.community.infrastructure;

import ai.camphub.community.domain.Tag;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code tag} 表访问接口。SQL 定义在 {@code resources/mapper/community/TagMapper.xml}。
 *
 * <p>与板块不同，标签允许用户在发布帖子时创建（社区常规做法：想打什么标签就打什么，
 * 而不是从固定清单里挑）。因此这里有插入能力，且"查找或创建"必须能应对并发。
 */
public interface TagMapper {

    /**
     * 列出使用最多的标签。
     *
     * <p>按"未被删除帖子的使用次数"倒序。没有帖子使用的标签排在最后 ——
     * 它们仍然存在（历史帖子可能引用过），但不应占据首页的标签入口。
     *
     * @param limit 返回条数上限
     * @return 标签列表
     */
    List<Tag> findMostUsed(@Param("limit") int limit);

    /**
     * 按对外标识查询单个标签（带使用次数）。
     *
     * @param slug 标签对外标识
     * @return 存在时返回
     */
    Optional<Tag> findBySlug(@Param("slug") String slug);

    /**
     * 批量按对外标识查询。
     *
     * <p>发布帖子时用于一次性把用户填的标签名解析成 id，避免逐个查询。
     * <b>调用方必须保证集合非空</b>（空集合会生成非法的空 {@code IN}）。
     *
     * @param slugs 标签对外标识集合（非空）
     * @return 命中的标签列表
     */
    List<Tag> findBySlugs(@Param("slugs") Collection<String> slugs);

    /**
     * 插入标签。
     *
     * <p>不做"先查再插"的幂等处理：并发下两个请求可能同时判断为"不存在"，
     * 然后一个成功、一个撞上 {@code uk_tag_slug} 唯一键。<b>撞键是这里的正常路径之一</b>，
     * 由调用方捕获后重新查询即可 —— 这比在应用层加锁简单，也比"先查再插"更可靠，
     * 因为唯一键是数据库级别的保证，不依赖两个请求的执行时序。
     *
     * @param slug 规范化后的对外标识
     * @param name 展示名（保留用户输入的大小写）
     * @return 影响行数
     */
    int insert(@Param("slug") String slug, @Param("name") String name);
}
