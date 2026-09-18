package ai.camphub.community.api;

import ai.camphub.common.web.PageResponse;
import ai.camphub.community.app.PostService;
import ai.camphub.community.app.TagService;
import ai.camphub.community.config.CommunityProperties;
import ai.camphub.identity.domain.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社区入口：板块、标签，以及"我的收藏"。
 *
 * <h2>为什么把"我的收藏"放在这里</h2>
 * 它的路径是 {@code /api/v1/community/me/favorites}，而不是
 * {@code /api/v1/community/posts/me/favorites}。后者会与
 * {@code /api/v1/community/posts/{publicId}} 形态相同 —— 虽然实际不会冲突
 * （public_id 恒为 22 个字符，永远不可能是 "me"），但让两条语义完全不同的路径
 * 长得一样，会让后来读路由表的人需要先确认这一点才能安心。
 *
 * <p>路径里用 {@code me} 而不是 {@code {userId}}：与 Phase 02 的
 * {@code /api/v1/users/me} 保持同一约束 —— <b>不给出"要操作哪个用户"这个参数</b>，
 * 于是"把别人的 id 填进去"这类越权尝试在接口形状上就不成立。
 */
@RestController
@RequestMapping("/api/v1/community")
@Tag(name = "Community", description = "社区：板块、标签与个人收藏")
public class CommunityController {

    /** 首页标签入口的展示条数。 */
    private static final int TAG_ENTRY_LIMIT = 20;

    private final PostService postService;
    private final TagService tagService;
    private final CommunityProperties properties;

    /**
     * 构造注入。
     *
     * @param postService 帖子服务（提供板块列表与收藏列表）
     * @param tagService  标签服务
     * @param properties  社区配置（分页默认值）
     */
    public CommunityController(PostService postService,
                               TagService tagService,
                               CommunityProperties properties) {
        this.postService = postService;
        this.tagService = tagService;
        this.properties = properties;
    }

    /**
     * 列出全部板块。
     *
     * <p>公开接口：浏览社区不需要登录。这是产品决策 —— 一个需要先注册才能看到内容的
     * 社区，对新访客来说等于没有内容。
     *
     * @return 板块列表
     */
    @GetMapping("/categories")
    @Operation(summary = "列出板块", description = "公开接口。带各板块下未被删除的帖子数。")
    public List<CategoryResponse> categories() {
        return postService.listCategories().stream().map(CategoryResponse::from).toList();
    }

    /**
     * 列出使用最多的标签，用于首页的标签入口。
     *
     * @return 标签列表
     */
    @GetMapping("/tags")
    @Operation(summary = "列出热门标签",
            description = "公开接口。按被引用的帖子数倒序，最多返回 20 个。")
    public List<TagResponse> tags() {
        return tagService.listMostUsed(TAG_ENTRY_LIMIT).stream().map(TagResponse::from).toList();
    }

    /**
     * 分页查询当前用户收藏的帖子。
     *
     * <p>需要登录 —— 收藏是账号级数据。未登录访问会得到 {@code 40100}。
     *
     * @param page      页码，从 1 开始
     * @param size      页大小，省略时用配置的默认值，超过上限按上限截断
     * @param principal 当前用户
     * @return 分页结果
     */
    @GetMapping("/me/favorites")
    @Operation(summary = "我的收藏", description = "按收藏时间倒序。需要登录。")
    public PageResponse<PostCardResponse> myFavorites(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal UserPrincipal principal) {

        int effectiveSize = size == null ? properties.feed().defaultPageSize() : size;
        return PageResponse.from(
                postService.listFavorites(principal.userId(), page, effectiveSize),
                PostCardResponse::from);
    }
}
