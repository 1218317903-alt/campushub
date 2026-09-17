package ai.camphub.community.api;

import ai.camphub.community.app.CommentService;
import ai.camphub.community.config.CommunityProperties;
import ai.camphub.identity.domain.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论接口：回复列表与删除。
 *
 * <h2>为什么评论的读取与发表不在这里</h2>
 * 它们都挂在帖子下（{@code /posts/{publicId}/comments}），因为那两个操作的语境
 * 是"在这个帖子下"—— 帖子标识是它们的必要输入，而不是可选项。
 * 本控制器只承载"以评论本身为入口"的两个操作：读它的回复、删它自己。
 * 换句话说，路径的切分依据是<b>操作的对象</b>，不是"评论相关就都放一起"。
 */
@RestController
@RequestMapping("/api/v1/community/comments")
@Tag(name = "Community · Comments", description = "评论：回复列表与删除")
public class CommentController {

    private final CommentService commentService;
    private final CommunityProperties properties;

    /**
     * 构造注入。
     *
     * @param commentService 评论服务
     * @param properties     社区配置（分页默认值）
     */
    public CommentController(CommentService commentService, CommunityProperties properties) {
        this.commentService = commentService;
        this.properties = properties;
    }

    /**
     * 分页查询某条顶层评论的回复（按时间正序）。
     *
     * @param publicId  顶层评论的对外标识
     * @param page      页码，从 1 开始
     * @param size      页大小，省略时用配置的默认值
     * @param principal 当前用户；匿名访问时为 null
     * @return 分页结果
     */
    @GetMapping("/{publicId}/replies")
    @Operation(summary = "评论的回复列表",
            description = "公开接口。按时间正序 —— 讨论的阅读顺序是从上往下，与顶层评论的倒序相反。")
    public PageResponse<CommentResponse> replies(
            @PathVariable("publicId") String publicId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal UserPrincipal principal) {

        int effectiveSize = size == null ? properties.feed().defaultPageSize() : size;
        Long currentUserId = CurrentUser.idOf(principal);
        return PageResponse.from(
                commentService.listReplies(publicId, page, effectiveSize),
                view -> CommentResponse.from(view, currentUserId));
    }

    /**
     * 删除评论或回复（软删除）。仅作者本人可删除。
     *
     * <p><b>删除顶层评论会连带删除它下面的全部回复</b>，这一点在接口文档里明确写出：
     * 让作者在删之前知道后果，好过删完再解释为什么别人的回复也没了。
     *
     * @param publicId  评论对外标识
     * @param principal 当前用户
     */
    @DeleteMapping("/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除评论",
            description = "仅作者本人可删除，非作者返回 404。删除顶层评论会连带删除其下全部回复。")
    public void delete(@PathVariable("publicId") String publicId,
                       @AuthenticationPrincipal UserPrincipal principal) {
        commentService.delete(principal.userId(), publicId);
    }
}
