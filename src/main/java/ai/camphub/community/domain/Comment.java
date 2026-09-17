package ai.camphub.community.domain;

import java.time.Instant;

/**
 * 评论或回复。
 *
 * <h2>两层结构怎么表达</h2>
 * {@code parentId} 为 {@code null} 表示这是一条<b>顶层评论</b>；
 * 非空表示它是对某条顶层评论的<b>回复</b>。
 * 服务层保证 {@code parentId} 只能指向同一帖子下的顶层评论 ——
 * 也就是说"回复的回复"会被挂到同一个父节点上，形成两层。
 *
 * <p>"两层"是产品决策而非技术限制，理由：讨论层级越深，越多人看不懂上下文在指谁，
 * 而绝大多数实际讨论本来就只有一两轮往返。把层级压平能让每条回复都有明确的读者对象。
 *
 * <h2>为什么 {@code parentId} 是包装类型 {@link Long}</h2>
 * 因为"没有父节点"是一个有意义的状态，必须能表达。原始类型 {@code long} 无法表达
 * "不存在"，用 0 代替则需要额外约定"0 表示顶层"—— 那是一条只存在于注释里的规则，
 * 迟早会被某个漏判的地方破坏。
 *
 * @param id        自增主键，仅内部使用
 * @param publicId  对外标识，删除接口路径中使用它
 * @param postId    所属帖子自增主键
 * @param parentId  父评论主键；null 表示顶层评论
 * @param authorId  作者自增主键
 * @param body      纯文本正文（保留换行）。评论不解析 Markdown，理由见 V3 迁移注释
 * @param createdAt 发布时间
 */
public record Comment(
        long id,
        String publicId,
        long postId,
        Long parentId,
        long authorId,
        String body,
        Instant createdAt
) {

    /**
     * 是否为顶层评论。
     *
     * @return 无父节点时返回 true
     */
    public boolean isTopLevel() {
        return parentId == null;
    }
}
