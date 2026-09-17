package ai.camphub.community.infrastructure;

import ai.camphub.community.domain.Category;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code category} 表访问接口。SQL 定义在 {@code resources/mapper/community/CategoryMapper.xml}。
 *
 * <p>板块是低频变更、只读为主的字典数据，因此只有查询能力 ——
 * 增减板块属于运营动作，本阶段没有对应接口。
 */
public interface CategoryMapper {

    /**
     * 列出全部板块，并带上各自的帖子数。
     *
     * <p>用一次带 {@code LEFT JOIN + COUNT} 的查询算出计数，而不是为每个板块单独查一次。
     * 板块只有几十个，这条查询即使全表聚合也毫无压力。
     *
     * @return 按 {@code sort_order} 升序的板块列表
     */
    List<Category> findAllWithPostCount();

    /**
     * 按对外标识查询单个板块（同样带帖子数）。
     *
     * <p>发布帖子时需要把客户端传来的 slug 解析成外键 id。这里刻意让它返回完整的
     * {@link Category} 而不是一个裸 id：{@code postCount} 这条计数在同一条 SQL 里
     * 本来就要算，返回半填充的记录反而会让"这个字段有时是 0 有时是真的"成为陷阱。
     *
     * @param slug 板块对外标识
     * @return 存在时返回
     */
    Optional<Category> findBySlug(@Param("slug") String slug);
}
