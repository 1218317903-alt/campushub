package ai.camphub.community.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.common.random.RandomValues;
import ai.camphub.common.web.Page;
import ai.camphub.community.config.CommunityProperties;
import ai.camphub.community.domain.Comment;
import ai.camphub.community.domain.PostDetail;
import ai.camphub.community.domain.ReplyCount;
import ai.camphub.community.infrastructure.CommentMapper;
import ai.camphub.community.infrastructure.PostMapper;
import ai.camphub.identity.app.UserDirectory;
import ai.camphub.identity.domain.UserBrief;
import ai.camphub.platform.audit.app.AuditService;
import ai.camphub.platform.audit.domain.AuditAction;
import ai.camphub.platform.audit.domain.AuditResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评论服务：两级讨论（顶层评论 + 回复）。
 *
 * <h2>层级约束由服务层保证，而不是由数据库</h2>
 * "回复只能挂在顶层评论上"这条规则无法用外键表达（外键只能保证 {@code parent_id}
 * 指向一条存在的评论，无法保证那条评论本身是顶层）。因此它必须在写入路径上被显式检查 ——
 * 若漏掉，就会悄悄产生第三层，而 {@link #listReplies} 只查一层，那些内容会
 * 永远查不出来（内容存在于库里但界面上不存在），属于最难排查的一类问题。
 *
 * <p>检查点有两个：父评论必须存在、且必须属于同一个帖子、且自身必须是顶层。
 * 三条缺一不可 —— 少了"同一个帖子"，就能把回复挂到别的帖子下的评论上。
 */
@Service
public class CommentService {

    /** 审计里记录的对象类型。 */
    private static final String TARGET_TYPE_COMMENT = "COMMENT";

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final UserDirectory userDirectory;
    private final AuditService auditService;
    private final CommunityProperties properties;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param commentMapper  评论数据访问
     * @param postMapper     帖子数据访问（用于校验帖子存在、维护评论计数）
     * @param userDirectory  identity 模块提供的用户展示信息
     * @param auditService   审计
     * @param properties     社区配置（页大小上限）
     * @param clock          时钟
     */
    public CommentService(CommentMapper commentMapper,
                          PostMapper postMapper,
                          UserDirectory userDirectory,
                          AuditService auditService,
                          CommunityProperties properties,
                          Clock clock) {
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
        this.userDirectory = userDirectory;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 分页查询某帖的顶层评论（按时间倒序），并带上各自的回复数。
     *
     * @param postPublicId 帖子对外标识
     * @param page         页码，从 1 开始
     * @param size         页大小
     * @return 分页结果
     * @throws BusinessException 帖子不存在时抛 {@code 40400}
     */
    @Transactional(readOnly = true)
    public Page<CommentView> listTopLevel(String postPublicId, int page, int size) {
        PostDetail post = requirePost(postPublicId);
        int effectiveSize = effectivePageSize(size);
        int effectivePage = Math.max(page, 1);
        int offset = (effectivePage - 1) * effectiveSize;

        List<Comment> comments = commentMapper.listTopLevel(post.id(), effectiveSize, offset);
        int total = commentMapper.countTopLevel(post.id());

        return Page.of(withReplyCounts(comments), effectivePage, effectiveSize, total);
    }

    /**
     * 分页查询某条顶层评论的回复（按时间正序）。
     *
     * @param parentPublicId 顶层评论的对外标识
     * @param page           页码，从 1 开始
     * @param size           页大小
     * @return 分页结果
     * @throws BusinessException 该评论不存在时抛 {@code 40400}
     */
    @Transactional(readOnly = true)
    public Page<CommentView> listReplies(String parentPublicId, int page, int size) {
        Comment parent = commentMapper.findByPublicId(parentPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        int effectiveSize = effectivePageSize(size);
        int effectivePage = Math.max(page, 1);
        int offset = (effectivePage - 1) * effectiveSize;

        List<Comment> replies = commentMapper.listReplies(parent.id(), effectiveSize, offset);
        int total = commentMapper.countReplies(parent.id());

        // 回复本身不再有回复，因此回复数恒为 0，不必再查一次
        return Page.of(assemble(replies, Map.of()), effectivePage, effectiveSize, total);
    }

    /**
     * 发表评论或回复。
     *
     * @param authorUserId   作者自增主键（取自鉴权上下文）
     * @param postPublicId   帖子对外标识
     * @param parentPublicId 被回复的顶层评论对外标识；发表顶层评论时为 null
     * @param body           纯文本正文
     * @return 新评论的视图
     * @throws BusinessException 帖子或父评论不存在（{@code 40400}）、
     *                           父评论不是顶层评论（{@code 40001}）
     */
    @Transactional
    public CommentView create(long authorUserId,
                             String postPublicId,
                             String parentPublicId,
                             String body) {
        PostDetail post = requirePost(postPublicId);
        Long parentId = resolveParentId(post.id(), parentPublicId);

        String publicId = RandomValues.publicId();
        commentMapper.insert(new Comment(0L, publicId, post.id(), parentId,
                authorUserId, body.strip(), clock.instant()));

        // 计数在同一个事务里自增。因为插入必然成功（失败会抛异常回滚），
        // 所以这里不存在"插入没成却把计数加了"的窗口
        postMapper.incrementCommentCount(post.id());

        Comment created = commentMapper.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalStateException("刚插入的评论查询不到，publicId=" + publicId));

        return new CommentView(created, userDirectory.findBrief(authorUserId).orElse(null), 0L);
    }

    /**
     * 删除评论或回复（软删除）。仅作者本人可删除。
     *
     * <p><b>删除一条顶层评论会连带删除它下面的全部回复。</b>这一点在接口文档中
     * 明确告知用户 —— 见 {@code CommentMapper.softDeleteWithReplies} 的取舍说明。
     *
     * @param currentUserId    当前用户自增主键
     * @param commentPublicId  评论对外标识
     * @throws BusinessException 评论不存在或不属于当前用户时统一抛 {@code 40400}
     */
    @Transactional
    public void delete(long currentUserId, String commentPublicId) {
        Comment comment = commentMapper.findByPublicId(commentPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (comment.authorId() != currentUserId) {
            // 与帖子一致：对外表现成"不存在"，避免把评论是否存在变成可探测的信息
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        commentMapper.softDeleteWithReplies(comment.id(), clock.instant());
        // 用重算而不是减一：删顶层评论会连带删掉若干回复，要减多少取决于实际有几条
        postMapper.recountCommentCount(comment.postId());

        auditService.record(AuditAction.CONTENT_COMMENT_DELETE, AuditResult.SUCCESS, currentUserId,
                TARGET_TYPE_COMMENT, commentPublicId, Map.of("postId", comment.postId()));
    }

    /**
     * 解析父评论主键，并校验它确实可以承载一条回复。
     *
     * @param postId         帖子自增主键
     * @param parentPublicId 父评论对外标识，可为 null
     * @return 父评论自增主键；发表顶层评论时为 null
     * @throws BusinessException 父评论不存在或不属于该帖（{@code 40400}）、
     *                           父评论本身是回复（{@code 40001}）
     */
    private Long resolveParentId(long postId, String parentPublicId) {
        if (parentPublicId == null || parentPublicId.isBlank()) {
            return null;
        }
        Comment parent = commentMapper.findByPublicId(parentPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        // "属于同一个帖子"这一条不能省：少了它就能把回复挂到别的帖子的评论下面，
        // 于是那条回复出现在 A 帖的评论区，而它回复的内容在 B 帖
        if (parent.postId() != postId) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!parent.isTopLevel()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "只能回复顶层评论：本平台的讨论为两层结构，回复会统一挂在同一条顶层评论下");
        }
        return parent.id();
    }

    /**
     * 按对外标识取出帖子，不存在则报错。
     *
     * @param postPublicId 帖子对外标识
     * @return 帖子详情
     * @throws BusinessException 不存在时抛 {@code 40400}
     */
    private PostDetail requirePost(String postPublicId) {
        return postMapper.findDetailByPublicId(postPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * 给顶层评论补上各自的回复数。
     *
     * @param comments 顶层评论列表
     * @return 带回复数的视图列表
     */
    private List<CommentView> withReplyCounts(List<Comment> comments) {
        if (comments.isEmpty()) {
            return List.of();
        }
        List<Long> parentIds = comments.stream().map(Comment::id).toList();
        Map<Long, Long> replyCounts = commentMapper.countRepliesByParentIds(parentIds).stream()
                .collect(Collectors.toMap(ReplyCount::parentId, ReplyCount::replyCount));
        return assemble(comments, replyCounts);
    }

    /**
     * 装配评论视图：批量取作者信息，避免逐条查询。
     *
     * @param comments    评论列表
     * @param replyCounts 父评论主键 → 回复数
     * @return 视图列表
     */
    private List<CommentView> assemble(List<Comment> comments, Map<Long, Long> replyCounts) {
        if (comments.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = comments.stream().map(Comment::authorId).collect(Collectors.toSet());
        Map<Long, UserBrief> authors = userDirectory.findBriefs(authorIds);

        return comments.stream()
                .map(comment -> new CommentView(
                        comment,
                        authors.get(comment.authorId()),
                        replyCounts.getOrDefault(comment.id(), 0L)))
                .toList();
    }

    /**
     * 把页大小夹到配置上限之内。
     *
     * @param requested 请求的页大小
     * @return 实际使用的页大小
     */
    private int effectivePageSize(int requested) {
        return Math.min(Math.max(requested, 1), properties.feed().maxPageSize());
    }
}
