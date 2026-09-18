package ai.camphub.community.api;

import ai.camphub.common.web.PageResponse;
import ai.camphub.community.app.CommentService;
import ai.camphub.community.app.FeedQuery;
import ai.camphub.community.app.PostService;
import ai.camphub.community.app.ReactionService;
import ai.camphub.community.config.CommunityProperties;
import ai.camphub.community.domain.PostSort;
import ai.camphub.identity.domain.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帖子接口：浏览、发布、编辑、删除，以及挂在帖子下的评论与互动。
 *
 * <h2>读公开、写必须登录</h2>
 * 列表与详情是公开的（{@code SecurityConfig} 中按 GET 放行），其余全部需要认证。
 * 这是产品决策而不是技术默认：一个必须先注册才能看到任何内容的社区，对新访客
 * 等于没有内容；反过来，能读到内容不等于能写内容。
 *
 * <p>公开接口仍然接受访问令牌，因此带着登录态浏览时会额外返回
 * {@code liked} / {@code favorited} / {@code ownedByMe} 三个因人而异的字段；
 * 匿名访问时它们恒为 {@code false}，而 {@code author} 与计数照常返回。
 * 换句话说：<b>不登录不会让响应缺字段</b>，只会让那三个布尔量失去意义 ——
 * 让响应形状随登录态变化，是前端最容易被"本地有登录态所以测不出来"坑到的设计。
 *
 * <h2>为什么点赞与取消点赞用 POST + DELETE，而不是 PUT + 一个 {@code active} 参数</h2>
 * 状态由 HTTP 方法表达，而不是放进请求体。好处是这两个请求不需要请求体，
 * 也就不存在"参数该放 body 还是 query"的歧义，而且幂等的语义（重复 DELETE 不报错）
 * 可以直接由方法语义承载。
 *
 * <p>两者都返回 {@code 200} 与操作后的状态，而不是 {@code 204}：
 * 客户端需要知道计数变成了多少才能正确更新界面。返回 204 会迫使前端
 * 自行猜测计数（通常是本地 +1/-1），一旦与真实值不同步就只能靠刷新恢复。
 */
@RestController
@RequestMapping("/api/v1/community/posts")
@Tag(name = "Community · Posts", description = "帖子：浏览、发布、编辑、删除、评论、点赞与收藏")
public class PostController {

    private final PostService postService;
    private final CommentService commentService;
    private final ReactionService reactionService;
    private final CommunityProperties properties;

    /**
     * 构造注入。
     *
     * @param postService     帖子服务
     * @param commentService  评论服务
     * @param reactionService 互动服务
     * @param properties      社区配置（分页默认值）
     */
    public PostController(PostService postService,
                          CommentService commentService,
                          ReactionService reactionService,
                          CommunityProperties properties) {
        this.postService = postService;
        this.commentService = commentService;
        this.reactionService = reactionService;
        this.properties = properties;
    }

    /**
     * 分页查询帖子列表。
     *
     * <p>公开接口。筛选条件为空或空串时视为不限 —— 前端筛选框未选时会发出
     * {@code ?category=&tag=}，若不归一化就会变成"什么都没选却什么都查不到"。
     *
     * @param category  板块对外标识，省略表示不限
     * @param tag       标签对外标识，省略表示不限
     * @param sort      排序方式，省略按最新；取值不区分大小写（{@code latest} / {@code hot}）
     * @param page      页码，从 1 开始
     * @param size      页大小，省略时用配置的默认值
     * @param principal 当前用户；匿名访问时为 null
     * @return 分页结果
     */
    @GetMapping
    @Operation(summary = "帖子列表",
            description = "公开接口。支持按板块与标签筛选、按最新或最热排序。带登录态访问时额外返回"
                    + " liked / favorited / ownedByMe。")
    public PageResponse<PostCardResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) PostSort sort,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal UserPrincipal principal) {

        FeedQuery query = FeedQuery.of(category, tag, sort, page, effectiveSize(size));
        return PageResponse.from(postService.list(query, CurrentUser.idOf(principal)), PostCardResponse::from);
    }

    /**
     * 发布帖子。
     *
     * @param principal 当前用户
     * @param request   帖子内容
     * @return 新帖详情
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "发布帖子",
            description = "需要登录。正文按 Markdown 渲染并在服务端净化；标签不存在的会被自动创建。")
    public PostDetailResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @RequestBody PostRequest request) {
        return PostDetailResponse.from(postService.create(principal.userId(), request.toCommand()));
    }

    /**
     * 查询帖子详情。
     *
     * <p>公开接口。带登录态访问会记录一次浏览（同一用户同一天只计一次），
     * 匿名访问不计入浏览数 —— 统计口径是"登录用户的浏览量"。
     *
     * @param publicId  帖子对外标识
     * @param principal 当前用户；匿名访问时为 null
     * @return 详情
     */
    @GetMapping("/{publicId}")
    @Operation(summary = "帖子详情",
            description = "公开接口。bodyHtml 已是服务端渲染并净化后的结果，前端不应再次渲染 Markdown。")
    public PostDetailResponse detail(@PathVariable("publicId") String publicId,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return PostDetailResponse.from(postService.detail(publicId, CurrentUser.idOf(principal)));
    }

    /**
     * 编辑帖子。仅作者本人可编辑。
     *
     * @param publicId  帖子对外标识
     * @param principal 当前用户
     * @param request   新的内容
     * @return 更新后的详情
     */
    @PutMapping("/{publicId}")
    @Operation(summary = "编辑帖子",
            description = "仅作者本人可编辑。非作者访问返回 404 而不是 403 —— 后者会暴露该帖子是否存在。")
    public PostDetailResponse update(@PathVariable("publicId") String publicId,
                                     @AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @RequestBody PostRequest request) {
        return PostDetailResponse.from(
                postService.update(principal.userId(), publicId, request.toCommand()));
    }

    /**
     * 删除帖子（软删除）。仅作者本人可删除。
     *
     * @param publicId  帖子对外标识
     * @param principal 当前用户
     */
    @DeleteMapping("/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除帖子", description = "仅作者本人可删除。软删除，内容不再对外可见。")
    public void delete(@PathVariable("publicId") String publicId,
                       @AuthenticationPrincipal UserPrincipal principal) {
        postService.delete(principal.userId(), publicId);
    }

    /**
     * 分页查询帖子的顶层评论（按时间倒序）。
     *
     * @param publicId  帖子对外标识
     * @param page      页码，从 1 开始
     * @param size      页大小，省略时用配置的默认值
     * @param principal 当前用户；匿名访问时为 null
     * @return 分页结果
     */
    @GetMapping("/{publicId}/comments")
    @Operation(summary = "帖子的一级评论",
            description = "公开接口。只返回顶层评论，每条带 replyCount；回复需要另调"
                    + " GET /api/v1/community/comments/{publicId}/replies。")
    public PageResponse<CommentResponse> comments(
            @PathVariable("publicId") String publicId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal UserPrincipal principal) {

        Long currentUserId = CurrentUser.idOf(principal);
        return PageResponse.from(
                commentService.listTopLevel(publicId, page, effectiveSize(size)),
                view -> CommentResponse.from(view, currentUserId));
    }

    /**
     * 发表评论或回复。
     *
     * @param publicId  帖子对外标识
     * @param principal 当前用户
     * @param request   评论内容；{@code parentId} 非空时表示回复该顶层评论
     * @return 新评论
     */
    @PostMapping("/{publicId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "发表评论或回复",
            description = "需要登录。parentId 为空时发表顶层评论；非空时必须指向本帖的顶层评论，"
                    + "否则返回 40001 —— 本平台的讨论结构为两层，回复会统一挂在同一条顶层评论下。")
    public CommentResponse comment(@PathVariable("publicId") String publicId,
                                   @AuthenticationPrincipal UserPrincipal principal,
                                   @Valid @RequestBody CommentRequest request) {
        return CommentResponse.from(
                commentService.create(principal.userId(), publicId, request.parentId(), request.body()),
                principal.userId());
    }

    /**
     * 点赞。
     *
     * @param publicId  帖子对外标识
     * @param principal 当前用户
     * @return 操作后的状态与计数
     */
    @PostMapping("/{publicId}/like")
    @Operation(summary = "点赞", description = "幂等：重复点赞不报错、不重复计数。")
    public ReactionResponse like(@PathVariable("publicId") String publicId,
                                 @AuthenticationPrincipal UserPrincipal principal) {
        return ReactionResponse.from(reactionService.like(principal.userId(), publicId));
    }

    /**
     * 取消点赞。
     *
     * @param publicId  帖子对外标识
     * @param principal 当前用户
     * @return 操作后的状态与计数
     */
    @DeleteMapping("/{publicId}/like")
    @Operation(summary = "取消点赞", description = "幂等：对未点赞的帖子取消点赞不报错。")
    public ReactionResponse unlike(@PathVariable("publicId") String publicId,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return ReactionResponse.from(reactionService.unlike(principal.userId(), publicId));
    }

    /**
     * 收藏。
     *
     * @param publicId  帖子对外标识
     * @param principal 当前用户
     * @return 操作后的状态与计数
     */
    @PostMapping("/{publicId}/favorite")
    @Operation(summary = "收藏", description = "幂等。收藏列表见 GET /api/v1/community/me/favorites。")
    public ReactionResponse favorite(@PathVariable("publicId") String publicId,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return ReactionResponse.from(reactionService.favorite(principal.userId(), publicId));
    }

    /**
     * 取消收藏。
     *
     * @param publicId  帖子对外标识
     * @param principal 当前用户
     * @return 操作后的状态与计数
     */
    @DeleteMapping("/{publicId}/favorite")
    @Operation(summary = "取消收藏", description = "幂等：未收藏时取消不报错。")
    public ReactionResponse unfavorite(@PathVariable("publicId") String publicId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return ReactionResponse.from(reactionService.unfavorite(principal.userId(), publicId));
    }

    /**
     * 解析实际生效的页大小。
     *
     * <p>只在这里做"省略则用默认值"，而不是给 {@code @RequestParam} 写
     * {@code defaultValue = "20"}：默认值应当只有一个来源（配置文件），
     * 写进注解就意味着调整默认值要改代码。
     *
     * <p>超过上限的截断发生在服务层（它读同一份配置），因此这里不做上界判断 ——
     * 两处都判一次，早晚会有一处被改而另一处没改。
     *
     * @param size 客户端请求的页大小，可为 null
     * @return 传给服务层的页大小
     */
    private int effectiveSize(Integer size) {
        return size == null ? properties.feed().defaultPageSize() : size;
    }
}
