package ai.camphub.community.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.community.domain.PostDetail;
import ai.camphub.community.domain.ReactionType;
import ai.camphub.community.infrastructure.PostMapper;
import ai.camphub.community.infrastructure.PostReactionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 互动服务：点赞与收藏。
 *
 * <h2>幂等是这条路径的核心要求</h2>
 * 用户连点两下、网络重试、前端乐观更新后又收到一次点击 —— 这些都是常态。
 * 因此两个方向都必须是幂等的：
 * <ul>
 *   <li>对已经点过赞的帖子再点赞 → 不报错、不重复计数，返回"已点赞"</li>
 *   <li>对没有点赞的帖子取消点赞 → 不报错，返回"未点赞"</li>
 * </ul>
 * 幂等由数据库的唯一键 {@code (user_id, post_id, type)} 保证：服务层直接尝试插入，
 * 撞键即代表"已经是这个状态"。这比"先查一次再决定"更可靠 ——
 * 后者在并发下两个请求都会查到"没有"，然后都去插入，其中一个必然失败并把它
 * 变成一次用户可见的错误。
 *
 * <h2>明细与计数在同一事务内</h2>
 * 因此不存在"点赞记录写进去了但计数没加"的中间状态。
 * 代价是热点帖上同一行的锁竞争 —— 这正是 Phase 09 要测量并优化的东西
 * （见 docs/07-community-ops.md 的场景 B3），而不是现在凭猜测就引入异步落库。
 */
@Service
public class ReactionService {

    private final PostMapper postMapper;
    private final PostReactionMapper reactionMapper;

    /**
     * 构造注入。
     *
     * @param postMapper     帖子数据访问
     * @param reactionMapper 互动明细数据访问
     */
    public ReactionService(PostMapper postMapper, PostReactionMapper reactionMapper) {
        this.postMapper = postMapper;
        this.reactionMapper = reactionMapper;
    }

    /**
     * 点赞。
     *
     * @param userId       当前用户自增主键
     * @param postPublicId 帖子对外标识
     * @return 操作后的状态与计数
     * @throws BusinessException 帖子不存在时抛 {@code 40400}
     */
    @Transactional
    public ReactionResult like(long userId, String postPublicId) {
        return add(userId, postPublicId, ReactionType.LIKE);
    }

    /**
     * 取消点赞。
     *
     * @param userId       当前用户自增主键
     * @param postPublicId 帖子对外标识
     * @return 操作后的状态与计数
     * @throws BusinessException 帖子不存在时抛 {@code 40400}
     */
    @Transactional
    public ReactionResult unlike(long userId, String postPublicId) {
        return remove(userId, postPublicId, ReactionType.LIKE);
    }

    /**
     * 收藏。
     *
     * @param userId       当前用户自增主键
     * @param postPublicId 帖子对外标识
     * @return 操作后的状态与计数
     * @throws BusinessException 帖子不存在时抛 {@code 40400}
     */
    @Transactional
    public ReactionResult favorite(long userId, String postPublicId) {
        return add(userId, postPublicId, ReactionType.FAVORITE);
    }

    /**
     * 取消收藏。
     *
     * @param userId       当前用户自增主键
     * @param postPublicId 帖子对外标识
     * @return 操作后的状态与计数
     * @throws BusinessException 帖子不存在时抛 {@code 40400}
     */
    @Transactional
    public ReactionResult unfavorite(long userId, String postPublicId) {
        return remove(userId, postPublicId, ReactionType.FAVORITE);
    }

    /**
     * 建立互动（幂等）。
     *
     * @param userId       用户自增主键
     * @param postPublicId 帖子对外标识
     * @param type         互动类型
     * @return 操作后的状态与计数
     */
    private ReactionResult add(long userId, String postPublicId, ReactionType type) {
        PostDetail post = requirePost(postPublicId);

        boolean changed;
        try {
            reactionMapper.insert(userId, post.id(), type);
            changed = true;
        } catch (DuplicateKeyException alreadyReacted) {
            // 已经是这个状态。这不是错误，而是幂等语义的正常分支。
            // MySQL 的重复键错误不会使当前事务失效（与 PostgreSQL 不同），
            // 因此可以在这里吞掉它并继续提交事务。
            changed = false;
        }

        if (changed) {
            adjustCount(post.id(), type, 1);
        }
        // 计数以本次读取到的值为基准加减：同一时刻别人也在点赞时，返回的数字可能
        // 略低于真实值。这是可接受的 —— 它是展示用的数字，下一次刷新就会校正，
        // 而为了绝对精确去再查一次库，等于给最热的写路径再加一次往返。
        return new ReactionResult(true, countOf(post, type) + (changed ? 1 : 0));
    }

    /**
     * 取消互动（幂等）。
     *
     * @param userId       用户自增主键
     * @param postPublicId 帖子对外标识
     * @param type         互动类型
     * @return 操作后的状态与计数
     */
    private ReactionResult remove(long userId, String postPublicId, ReactionType type) {
        PostDetail post = requirePost(postPublicId);

        boolean changed = reactionMapper.delete(userId, post.id(), type) > 0;
        if (changed) {
            adjustCount(post.id(), type, -1);
        }
        return new ReactionResult(false, Math.max(countOf(post, type) - (changed ? 1 : 0), 0));
    }

    /**
     * 按类型调整计数列。
     *
     * <p>写成两个方法分别处理两种类型，而不是拼一个列名字符串传进去：
     * 列名无法用 SQL 参数占位符传递，拼接就意味着那一处是注入面。
     * 这里多写几行，换来的是没有任何一处 SQL 是拼出来的。
     *
     * @param postId 帖子自增主键
     * @param type   互动类型
     * @param delta  +1 或 -1
     */
    private void adjustCount(long postId, ReactionType type, int delta) {
        if (type == ReactionType.LIKE) {
            if (delta > 0) {
                postMapper.incrementLikeCount(postId);
            } else {
                postMapper.decrementLikeCount(postId);
            }
        } else {
            if (delta > 0) {
                postMapper.incrementFavoriteCount(postId);
            } else {
                postMapper.decrementFavoriteCount(postId);
            }
        }
    }

    /**
     * 取出本次读取到的计数。
     *
     * @param post 帖子详情
     * @param type 互动类型
     * @return 计数
     */
    private int countOf(PostDetail post, ReactionType type) {
        return type == ReactionType.LIKE ? post.likeCount() : post.favoriteCount();
    }

    /**
     * 按对外标识取出帖子，不存在则报错。
     *
     * <p>先做这一步是必要的：没有它，给一条不存在的帖子点赞会直接撞外键，
     * 用户得到的是一次 500，而不是一个说明白了的"内容不存在"。
     *
     * @param postPublicId 帖子对外标识
     * @return 帖子详情
     * @throws BusinessException 不存在时抛 {@code 40400}
     */
    private PostDetail requirePost(String postPublicId) {
        return postMapper.findDetailByPublicId(postPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
