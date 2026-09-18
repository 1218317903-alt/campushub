package ai.camphub.community.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.common.random.RandomValues;
import ai.camphub.common.web.Page;
import ai.camphub.community.config.CommunityProperties;
import ai.camphub.community.domain.Category;
import ai.camphub.community.domain.MarkdownRenderer;
import ai.camphub.community.domain.PostDetail;
import ai.camphub.community.domain.PostDraft;
import ai.camphub.community.domain.PostSummary;
import ai.camphub.community.domain.ReactionType;
import ai.camphub.community.domain.TagAssignment;
import ai.camphub.community.infrastructure.CategoryMapper;
import ai.camphub.community.infrastructure.PostMapper;
import ai.camphub.community.infrastructure.PostReactionMapper;
import ai.camphub.community.infrastructure.PostTagMapper;
import ai.camphub.community.infrastructure.PostViewMapper;
import ai.camphub.identity.app.UserDirectory;
import ai.camphub.identity.domain.UserBrief;
import ai.camphub.platform.audit.app.AuditService;
import ai.camphub.platform.audit.domain.AuditAction;
import ai.camphub.platform.audit.domain.AuditResult;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 帖子服务：发布、编辑、删除、详情、列表。
 *
 * <h2>归属校验一律以"当前用户"为基准，且失败时返回 404</h2>
 * 编辑与删除都在服务层重新查一次帖子的 {@code author_id} 并与当前用户比对，
 * 而不是信任请求里带过来的任何标识。<b>失败时返回 404 而不是 403</b>：
 * 403 等于告诉调用方"这条帖子存在，只是不归你"—— 那是一个
 * "这个 id 有没有对应的内容"的探测器。对外表现成"不存在"，才不泄漏资源的存在性。
 *
 * <p>这与 Phase 02 里"下线他人会话返回 404"是同一条规则，不是两套做法。
 *
 * <h2>详情读取为什么不是只读事务</h2>
 * 详情读取会顺带记录一次浏览（{@code post_view_daily} 去重 + 计数），
 * 因此它写库。把它放在同一个事务里而不是另开一个事务，是为了避免
 * "为了记一次浏览而挂起当前事务、再从连接池取第二条连接" ——
 * 详情是最高频的读路径，连接切换的成本会直接落在它身上。
 *
 * <p>代价是浏览记账若失败会连带影响这次读取。因此记账这一段被包在
 * {@code try/catch} 里：计数是次要信息，不该让用户看不到内容。
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    /** 审计里记录的对象类型。用常量而不是散落的字面量，避免出现 "POST" / "post" 两种写法。 */
    private static final String TARGET_TYPE_POST = "POST";

    private final PostMapper postMapper;
    private final PostTagMapper postTagMapper;
    private final CategoryMapper categoryMapper;
    private final PostReactionMapper reactionMapper;
    private final PostViewMapper viewMapper;
    private final TagService tagService;
    private final UserDirectory userDirectory;
    private final MarkdownRenderer markdownRenderer;
    private final AuditService auditService;
    private final CommunityProperties properties;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param postMapper       帖子数据访问
     * @param postTagMapper    帖子-标签关联数据访问
     * @param categoryMapper   板块数据访问
     * @param reactionMapper   互动明细数据访问
     * @param viewMapper       浏览明细数据访问
     * @param tagService       标签解析
     * @param userDirectory    identity 模块提供的用户展示信息（不读 user 表）
     * @param markdownRenderer Markdown 渲染与净化
     * @param auditService     审计
     * @param properties       社区配置
     * @param clock            时钟（注入而非直接 Instant.now()，便于测试控制时间）
     */
    public PostService(PostMapper postMapper,
                       PostTagMapper postTagMapper,
                       CategoryMapper categoryMapper,
                       PostReactionMapper reactionMapper,
                       PostViewMapper viewMapper,
                       TagService tagService,
                       UserDirectory userDirectory,
                       MarkdownRenderer markdownRenderer,
                       AuditService auditService,
                       CommunityProperties properties,
                       Clock clock) {
        this.postMapper = postMapper;
        this.postTagMapper = postTagMapper;
        this.categoryMapper = categoryMapper;
        this.reactionMapper = reactionMapper;
        this.viewMapper = viewMapper;
        this.tagService = tagService;
        this.userDirectory = userDirectory;
        this.markdownRenderer = markdownRenderer;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 列出全部板块（带帖子数）。
     *
     * @return 板块列表
     */
    @Transactional(readOnly = true)
    public List<Category> listCategories() {
        return categoryMapper.findAllWithPostCount();
    }

    /**
     * 发布帖子。
     *
     * @param authorUserId 作者自增主键（取自鉴权上下文，不接受外部传入）
     * @param command      发布内容
     * @return 新帖的详情视图
     * @throws BusinessException 板块不存在（{@code 40030}）、正文超长或标签过多（{@code 40031}）、
     *                           标签名不合法（{@code 40032}）
     */
    @Transactional
    public PostDetailView create(long authorUserId, PostCommand command) {
        String bodyMd = command.bodyMd();
        requireBodyWithinLimit(bodyMd);
        int categoryId = requireCategoryId(command.categorySlug());
        String title = command.title().strip();

        String publicId = RandomValues.publicId();
        postMapper.insert(new PostDraft(
                publicId,
                authorUserId,
                categoryId,
                title,
                markdownRenderer.summarize(bodyMd, properties.post().summaryLength()),
                bodyMd,
                markdownRenderer.render(bodyMd),
                clock.instant()));

        PostDetail created = requirePost(publicId);
        replaceTags(created.id(), command.tagNames());

        auditService.record(AuditAction.CONTENT_POST_CREATE, AuditResult.SUCCESS, authorUserId,
                TARGET_TYPE_POST, publicId, Map.of("category", command.categorySlug()));

        return buildDetailView(created, authorUserId);
    }

    /**
     * 编辑帖子。仅作者本人可编辑。
     *
     * @param currentUserId 当前用户自增主键
     * @param publicId      帖子对外标识
     * @param command       新的内容
     * @return 更新后的详情视图
     * @throws BusinessException 帖子不存在或不属于当前用户时统一抛 {@code 40400}
     */
    @Transactional
    public PostDetailView update(long currentUserId, String publicId, PostCommand command) {
        PostDetail existing = requirePost(publicId);
        requireOwnedBy(existing.authorId(), currentUserId);

        String bodyMd = command.bodyMd();
        requireBodyWithinLimit(bodyMd);
        int categoryId = requireCategoryId(command.categorySlug());

        int affected = postMapper.updateContent(
                existing.id(),
                categoryId,
                command.title().strip(),
                markdownRenderer.summarize(bodyMd, properties.post().summaryLength()),
                bodyMd,
                markdownRenderer.render(bodyMd));
        if (affected == 0) {
            // 查到之后、更新之前被作者自己的另一个请求删掉了。
            // 归一到"不存在"，与前面的归属校验保持同一套对外表现
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        replaceTags(existing.id(), command.tagNames());

        auditService.record(AuditAction.CONTENT_POST_UPDATE, AuditResult.SUCCESS, currentUserId,
                TARGET_TYPE_POST, publicId, null);

        return buildDetailView(requirePost(publicId), currentUserId);
    }

    /**
     * 删除帖子（软删除）。仅作者本人可删除。
     *
     * <p>刻意<b>不</b>删除 {@code post_tag} 关联行：帖子只是被标记删除，
     * 关联关系仍然成立。标签的使用次数统计已经排除了被删除的帖子
     * （见 {@code TagMapper.xml} 的计数口径），因此保留关联不会让计数变脏。
     *
     * @param currentUserId 当前用户自增主键
     * @param publicId      帖子对外标识
     * @throws BusinessException 帖子不存在或不属于当前用户时统一抛 {@code 40400}
     */
    @Transactional
    public void delete(long currentUserId, String publicId) {
        PostDetail existing = requirePost(publicId);
        requireOwnedBy(existing.authorId(), currentUserId);

        postMapper.softDelete(existing.id(), clock.instant());

        auditService.record(AuditAction.CONTENT_POST_DELETE, AuditResult.SUCCESS, currentUserId,
                TARGET_TYPE_POST, publicId, null);
    }

    /**
     * 查询帖子详情，并记录一次浏览。
     *
     * @param publicId      帖子对外标识
     * @param currentUserId 当前用户自增主键；未登录时为 null（此时不记录浏览）
     * @return 详情视图
     * @throws BusinessException 帖子不存在时抛 {@code 40400}
     */
    @Transactional
    public PostDetailView detail(String publicId, Long currentUserId) {
        PostDetail detail = requirePost(publicId);

        if (currentUserId != null && recordViewQuietly(detail.id(), currentUserId)) {
            // 数据库里的计数已经 +1，但手上这个对象是自增之前读出来的。
            // 不修正的话，用户会看到"我刚看完，数字没变"
            detail = detail.withViewCount(detail.viewCount() + 1);
        }

        return buildDetailView(detail, currentUserId);
    }

    /**
     * 分页查询帖子列表。
     *
     * @param query         查询条件
     * @param currentUserId 当前用户自增主键，未登录时为 null
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public Page<PostCardView> list(FeedQuery query, Long currentUserId) {
        int size = effectivePageSize(query.size());
        List<PostSummary> posts = postMapper.findSummaries(
                query.categorySlug(), query.tagSlug(), query.sort(), size, query.offset(size));
        int total = postMapper.countByFilter(query.categorySlug(), query.tagSlug());

        return Page.of(assembleCards(posts, currentUserId), query.page(), size, total);
    }

    /**
     * 分页查询某用户收藏的帖子。
     *
     * @param userId 用户自增主键（即当前用户）
     * @param page   页码，从 1 开始
     * @param size   页大小
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public Page<PostCardView> listFavorites(long userId, int page, int size) {
        int effectiveSize = effectivePageSize(size);
        int effectivePage = Math.max(page, 1);
        long offset = (long) (effectivePage - 1) * effectiveSize;

        List<PostSummary> posts = postMapper.findFavoriteSummaries(userId, effectiveSize, offset);
        int total = postMapper.countFavorites(userId);

        return Page.of(assembleCards(posts, userId), effectivePage, effectiveSize, total);
    }

    /**
     * 按对外标识取出帖子，不存在则按统一契约报错。
     *
     * <p>所有写操作的第一步都是它，因此"帖子不存在"与"帖子不属于你"两种情况的
     * 对外表现天然一致 —— 后者在 {@link #requireOwnedBy} 中也被归一到同一个错误码。
     *
     * @param publicId 对外标识
     * @return 帖子详情
     * @throws BusinessException 不存在时抛 {@code 40400}
     */
    private PostDetail requirePost(String publicId) {
        return postMapper.findDetailByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * 断言帖子属于当前用户。
     *
     * @param authorId      帖子的作者自增主键
     * @param currentUserId 当前用户自增主键
     * @throws BusinessException 不属于当前用户时抛 {@code 40400}（刻意不是 403，见类注释）
     */
    private void requireOwnedBy(long authorId, long currentUserId) {
        if (authorId != currentUserId) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    /**
     * 把板块对外标识解析成内部主键。
     *
     * @param slug 板块对外标识
     * @return 板块自增主键
     * @throws BusinessException 板块不存在时抛 {@code 40030}
     */
    private int requireCategoryId(String slug) {
        return categoryMapper.findBySlug(slug)
                .map(category -> (int) category.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
    }

    /**
     * 校验正文长度。
     *
     * <p>按码点计算而不是 {@code String.length()}（UTF-16 码元数）：对中文以外的字符
     * 后者会数出约两倍，表现成"我明明没写那么长，却提示超长"。
     *
     * @param bodyMd Markdown 正文
     * @throws BusinessException 超长时抛 {@code 40031}
     */
    private void requireBodyWithinLimit(String bodyMd) {
        int max = properties.post().maxBodyLength();
        int actual = bodyMd.codePointCount(0, bodyMd.length());
        if (actual > max) {
            throw new BusinessException(ErrorCode.INVALID_POST_CONTENT,
                    "正文最多 " + max + " 字，当前 " + actual + " 字");
        }
    }

    /**
     * 重建帖子的标签关联（先清后插）。
     *
     * <p>用"清空 + 重插"而不是逐条增删差集：标签在一个帖子上的数量很小（个位数），
     * 全量重建的代码只有两行且不可能算错差集，而差集算法的边界情况
     * （重复、大小写、被删标签）每一个都要单独想一遍。
     *
     * @param postId   帖子自增主键
     * @param tagNames 标签名列表，可为 null 或空
     */
    private void replaceTags(long postId, List<String> tagNames) {
        postTagMapper.deleteByPostId(postId);
        List<Long> tagIds = tagService.resolveOrCreate(tagNames);
        if (!tagIds.isEmpty()) {
            postTagMapper.insertBatch(postId, tagIds);
        }
    }

    /**
     * 记录一次浏览，失败只记日志。
     *
     * <p>浏览计数是次要信息，而详情读取是最高频的路径。让一次记账失败
     * （例如连接抖动）变成用户看不到内容，是把主次关系搞反了。
     *
     * @param postId 帖子自增主键
     * @param userId 用户自增主键
     * @return 今天首次浏览该帖时返回 true
     */
    private boolean recordViewQuietly(long postId, long userId) {
        try {
            if (viewMapper.insertIfAbsent(postId, userId) == 1) {
                postMapper.incrementViewCount(postId);
                return true;
            }
            return false;
        } catch (DataAccessException e) {
            log.warn("浏览计数失败，不影响读取 postId={} userId={}", postId, userId, e);
            return false;
        }
    }

    /**
     * 把页大小夹到配置上限之内。
     *
     * @param requested 客户端请求的页大小
     * @return 实际使用的页大小
     */
    private int effectivePageSize(int requested) {
        int max = properties.feed().maxPageSize();
        return Math.min(Math.max(requested, 1), max);
    }

    /**
     * 把一页帖子补全成可直接渲染的卡片。
     *
     * <p>这里承担的是"防止 N+1"的职责：无论一页有多少条，补齐作者与互动状态
     * 都只各发生一次（互动状态两次：点赞与收藏各一次批量查询）。
     *
     * @param posts         帖子列表项
     * @param currentUserId 当前用户自增主键，未登录时为 null
     * @return 卡片列表
     */
    private List<PostCardView> assembleCards(List<PostSummary> posts, Long currentUserId) {
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> postIds = posts.stream().map(PostSummary::id).toList();

        Map<Long, List<TagAssignment>> tagsByPost = postTagMapper.findByPostIds(postIds).stream()
                .collect(Collectors.groupingBy(TagAssignment::postId,
                        LinkedHashMap::new, Collectors.toList()));

        Set<Long> authorIds = posts.stream().map(PostSummary::authorId).collect(Collectors.toSet());
        Map<Long, UserBrief> authors = userDirectory.findBriefs(authorIds);

        Set<Long> likedPostIds = currentUserId == null
                ? Set.of()
                : Set.copyOf(reactionMapper.findReactedPostIds(currentUserId, postIds, ReactionType.LIKE));
        Set<Long> favoritedPostIds = currentUserId == null
                ? Set.of()
                : Set.copyOf(reactionMapper.findReactedPostIds(currentUserId, postIds, ReactionType.FAVORITE));

        return posts.stream()
                .map(post -> new PostCardView(
                        post,
                        tagsByPost.getOrDefault(post.id(), List.of()),
                        authors.get(post.authorId()),
                        likedPostIds.contains(post.id()),
                        favoritedPostIds.contains(post.id()),
                        currentUserId != null && currentUserId == post.authorId()))
                .toList();
    }

    /**
     * 组装单条帖子的详情视图。
     *
     * @param detail        帖子详情
     * @param currentUserId 当前用户自增主键，未登录时为 null
     * @return 详情视图
     */
    private PostDetailView buildDetailView(PostDetail detail, Long currentUserId) {
        List<TagAssignment> tags = postTagMapper.findByPostIds(List.of(detail.id()));
        Optional<UserBrief> author = userDirectory.findBrief(detail.authorId());

        boolean liked = false;
        boolean favorited = false;
        if (currentUserId != null) {
            List<Long> single = List.of(detail.id());
            liked = !reactionMapper.findReactedPostIds(currentUserId, single, ReactionType.LIKE).isEmpty();
            favorited = !reactionMapper.findReactedPostIds(currentUserId, single, ReactionType.FAVORITE).isEmpty();
        }

        return new PostDetailView(
                detail,
                tags,
                author.orElse(null),
                liked,
                favorited,
                currentUserId != null && currentUserId == detail.authorId());
    }
}
