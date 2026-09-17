package ai.camphub.community.infrastructure;

import ai.camphub.community.domain.Comment;
import ai.camphub.community.domain.ReplyCount;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code comment} 表访问接口。SQL 定义在 {@code resources/mapper/community/CommentMapper.xml}。
 *
 * <h2>为什么插入方法接受领域记录本身，而不是另建一个 Draft 类型</h2>
 * 评论只有六个字段，其中 {@code id} 与 {@code created_at} 由数据库负责 ——
 * 这与 {@code UserMapper.insert} 的既有约定完全一致（那里也是插入时忽略 id 与时间列）。
 * 帖子那边之所以另建 {@code PostDraft}，是因为它要写入的是 {@code category_id}
 * 而读出来的是 {@code category_slug}，字段集合真的不同。这里没有这种差异，
 * 多一个字段完全相同的类型只会增加"两个类型要保持同步"的负担。
 */
public interface CommentMapper {

    /**
     * 插入评论或回复。
     *
     * @param comment 评论（{@code id} 与 {@code createdAt} 被忽略，由数据库生成）
     * @return 影响行数
     */
    int insert(@Param("comment") Comment comment);

    /**
     * 按对外标识查询（用于删除时的归属校验）。
     *
     * @param publicId 对外标识
     * @return 存在时返回；已被删除时返回空
     */
    Optional<Comment> findByPublicId(@Param("publicId") String publicId);

    /**
     * 分页查询顶层评论，按发布时间<b>倒序</b>。
     *
     * <p>倒序的理由：评论区里最新的讨论更值得先看到，而回复内部才按时间正序
     * （见 {@link #listReplies}）—— 回复是对某条评论的追问，顺着读才通顺。
     *
     * @param postId 帖子自增主键
     * @param limit  页大小
     * @param offset 偏移量
     * @return 顶层评论
     */
    List<Comment> listTopLevel(@Param("postId") long postId,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    /**
     * 统计顶层评论数。
     *
     * @param postId 帖子自增主键
     * @return 总数
     */
    int countTopLevel(@Param("postId") long postId);

    /**
     * 批量统计若干顶层评论各自的回复数。
     *
     * <p>让"一页评论各自的回复数"成为一次查询，而不是每行一次 COUNT。
     * <b>调用方必须保证集合非空</b>。
     *
     * @param parentIds 顶层评论主键集合（非空）
     * @return 回复数列表（回复数为 0 的父评论不会出现在结果中，调用方按缺省 0 处理）
     */
    List<ReplyCount> countRepliesByParentIds(@Param("parentIds") Collection<Long> parentIds);

    /**
     * 分页查询某条顶层评论的回复，按发布时间<b>正序</b>。
     *
     * @param parentId 顶层评论主键
     * @param limit    页大小
     * @param offset   偏移量
     * @return 回复列表
     */
    List<Comment> listReplies(@Param("parentId") long parentId,
                             @Param("limit") int limit,
                             @Param("offset") int offset);

    /**
     * 统计某条顶层评论的回复数。
     *
     * @param parentId 顶层评论主键
     * @return 总数
     */
    int countReplies(@Param("parentId") long parentId);

    /**
     * 软删除一条评论<b>及其全部回复</b>。
     *
     * <h2>为什么连带删除回复</h2>
     * 若只删父评论而留下回复，界面就必须表达"一条不存在的评论下面挂着几条回复"这种
     * 状态 —— 要么显示"该评论已删除"占位（读者看不懂在回什么），要么把回复挂到不存在的
     * 父节点上（结构直接坏掉）。连带删除是结构上最干净的选择。
     *
     * <p>代价要说清楚：<b>作者删除自己的顶层评论时，会同时移除别人在该评论下的回复</b>。
     * 这是已知的产品语义，写进接口文档告知用户。若将来认为不可接受，
     * 正确的改法是"改为仅标记作者删除、保留楼下回复"，而不是在这里做局部妥协。
     *
     * <p>一条 UPDATE 同时覆盖两种情况（删顶层 / 删回复）：
     * 删回复时没有子节点，{@code parent_id = ?} 匹配不到任何行，不影响结果。
     *
     * @param commentId 评论自增主键
     * @param now       删除时间
     * @return 影响行数（含被连带删除的回复）
     */
    int softDeleteWithReplies(@Param("commentId") long commentId, @Param("now") Instant now);
}
