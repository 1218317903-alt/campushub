package ai.camphub.community.infrastructure;

import ai.camphub.community.domain.PostDetail;
import ai.camphub.community.domain.PostDraft;
import ai.camphub.community.domain.PostSort;
import ai.camphub.community.domain.PostSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code post} 表访问接口。SQL 定义在 {@code resources/mapper/community/PostMapper.xml}。
 *
 * <h2>关于"插入后不返回自增主键"</h2>
 * 与 {@code UserMapper} 的取舍一致：{@code useGeneratedKeys} 要求把主键写回一个
 * <b>可变</b>参数对象，而领域模型是不可变 record。与其为了拿 id 造一套可变 DTO，
 * 不如插入后按 {@code public_id} 再查一次 —— {@code public_id} 由服务端生成、本来就已知，
 * 且它是唯一索引，这一查是索引命中等价的开销。
 *
 * <h2>关于计数字段的自增</h2>
 * 全部用 {@code SET x = x + 1} 形式的原地自增，而不是"读出来改再写回"：
 * 后者在并发下会丢更新，而计数丢掉之后没有任何东西能发现它少了。
 * 浏览数与互动数的<b>权威来源</b>分别是 {@code post_view_daily} 与 {@code post_reaction}
 * 明细表（它们有唯一键约束），计数列只是为了让列表页不必聚合明细。
 * 也就是说：计数错了可以靠明细重算纠正，反过来则不成立 ——
 * 这也是为什么明细写入与计数自增必须在同一个事务里。
 *
 * <h2>为什么这里没有 join user 表的方法</h2>
 * 列表项需要显示作者昵称，而 {@code PostSummary} 自带 {@code authorId} ——
 * 调用方对整页去重后交给 {@code UserDirectory} 批量换昵称即可。
 * 在 SQL 里 {@code JOIN user} 会直接违反"模块间禁止跨模块访问数据表"
 * （docs/00-工程规约.md 架构强制约定 1），并且会让本模块的查询与 user 表的
 * 结构绑死。少一次 join 换来的是边界可维持。
 */
public interface PostMapper {

    /**
     * 插入帖子。
     *
     * @param post 待发布的帖子（{@code id} 由数据库生成）
     * @return 影响行数
     */
    int insert(@Param("post") PostDraft post);

    /**
     * 按对外标识查询详情（含正文）。
     *
     * @param publicId 对外标识
     * @return 存在且未被删除时返回
     */
    Optional<PostDetail> findDetailByPublicId(@Param("publicId") String publicId);

    /**
     * 按自增主键查询详情（含正文）。
     *
     * <p>互动、评论等场景已经通过对外标识拿到过帖子的内部 id，用主键查更直接。
     *
     * @param id 自增主键
     * @return 存在且未被删除时返回
     */
    Optional<PostDetail> findDetailById(@Param("id") long id);

    /**
     * 分页查询列表项（不含正文）。
     *
     * @param categorySlug 板块筛选，可为 null
     * @param tagSlug      标签筛选，可为 null
     * @param sort         排序方式，决定 {@code ORDER BY} 走哪个索引
     * @param limit        页大小
     * @param offset       偏移量
     * @return 帖子列表项
     */
    List<PostSummary> findSummaries(@Param("categorySlug") String categorySlug,
                                    @Param("tagSlug") String tagSlug,
                                    @Param("sort") PostSort sort,
                                    @Param("limit") int limit,
                                    @Param("offset") long offset);

    /**
     * 统计符合筛选条件的帖子总数，用于计算分页。
     *
     * <p>参数与 {@link #findSummaries} 必须保持一致 —— 两处的筛选条件写在同一个
     * {@code <sql>} 片段里复用，避免"列表按 A 筛、总数按 B 算"导致分页页数不对。
     *
     * @param categorySlug 板块筛选，可为 null
     * @param tagSlug      标签筛选，可为 null
     * @return 总数
     */
    int countByFilter(@Param("categorySlug") String categorySlug,
                      @Param("tagSlug") String tagSlug);

    /**
     * 分页查询某用户收藏的帖子。
     *
     * <p>排序依据是<b>收藏时间</b>而不是发布时间：用户想找的是"我最近收的那个"，
     * 而不是"最近发的那篇"。
     *
     * @param userId 用户自增主键
     * @param limit  页大小
     * @param offset 偏移量
     * @return 帖子列表项
     */
    List<PostSummary> findFavoriteSummaries(@Param("userId") long userId,
                                            @Param("limit") int limit,
                                            @Param("offset") long offset);

    /**
     * 统计某用户收藏的帖子数。
     *
     * @param userId 用户自增主键
     * @return 总数
     */
    int countFavorites(@Param("userId") long userId);

    /**
     * 更新帖子的可编辑字段。
     *
     * <p>刻意<b>不</b>更新 {@code published_at}、作者与计数：
     * 编辑不应该改变内容的"出现时间"，也不应该改变归属。
     *
     * @param postId     帖子自增主键
     * @param categoryId 板块主键
     * @param title      标题
     * @param summary    摘要
     * @param bodyMd     Markdown 原文
     * @param bodyHtml   净化后的 HTML
     * @return 影响行数；帖子已被删除时为 0
     */
    int updateContent(@Param("postId") long postId,
                      @Param("categoryId") int categoryId,
                      @Param("title") String title,
                      @Param("summary") String summary,
                      @Param("bodyMd") String bodyMd,
                      @Param("bodyHtml") String bodyHtml);

    /**
     * 软删除帖子。
     *
     * @param postId 帖子自增主键
     * @param now    删除时间
     * @return 影响行数；已经是删除状态时为 0（幂等）
     */
    int softDelete(@Param("postId") long postId, @Param("now") Instant now);

    /**
     * 浏览数 +1。
     *
     * @param postId 帖子自增主键
     * @return 影响行数
     */
    int incrementViewCount(@Param("postId") long postId);

    /**
     * 点赞数 +1。
     *
     * @param postId 帖子自增主键
     * @return 影响行数
     */
    int incrementLikeCount(@Param("postId") long postId);

    /**
     * 点赞数 -1。
     *
     * <p>用 {@code GREATEST(x - 1, 0)} 而不是直接 {@code x - 1}：
     * {@code INT UNSIGNED} 列减到负数在 MySQL 严格模式下会直接报错，
     * 而一个已经跑偏的计数不应该让"取消点赞"这个用户操作失败。
     *
     * @param postId 帖子自增主键
     * @return 影响行数
     */
    int decrementLikeCount(@Param("postId") long postId);

    /**
     * 收藏数 +1。
     *
     * @param postId 帖子自增主键
     * @return 影响行数
     */
    int incrementFavoriteCount(@Param("postId") long postId);

    /**
     * 收藏数 -1。
     *
     * @param postId 帖子自增主键
     * @return 影响行数
     */
    int decrementFavoriteCount(@Param("postId") long postId);

    /**
     * 评论数 +1。
     *
     * @param postId 帖子自增主键
     * @return 影响行数
     */
    int incrementCommentCount(@Param("postId") long postId);

    /**
     * 按明细重算评论数。
     *
     * <p>删除评论时用重算而不是减一：删除一条顶层评论会连带删除它的若干条回复，
     * 需要减掉的数量取决于实际有几条回复，而"查询回复数再减"和"直接重算"
     * 的成本相当，后者还能顺带纠正此前的任何偏差。
     *
     * @param postId 帖子自增主键
     * @return 影响行数
     */
    int recountCommentCount(@Param("postId") long postId);
}
