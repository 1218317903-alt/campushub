package ai.camphub.workspace.api;

import ai.camphub.common.web.PageResponse;
import ai.camphub.identity.domain.UserPrincipal;
import ai.camphub.workspace.app.NoteService;
import ai.camphub.workspace.config.WorkspaceProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 空间内协作笔记接口。
 *
 * <h2>路径里为什么同时有空间标识与笔记标识</h2>
 * 笔记的 {@code publicId} 本身是全局唯一的，技术上一条
 * {@code /api/v1/notes/{publicId}} 就够了。这里不那样做，有两个理由：
 * <ul>
 *   <li><b>权限的上下文是空间。</b>判定"这个人能不能改这条笔记"要先知道
 *       "他在哪个空间里是什么身份"。空间标识在路径上，这次判定就是自明的；
 *       若藏在数据库里，读代码的人必须先去查笔记表才能确定判定用的是哪个空间。</li>
 *   <li><b>拿错空间会立刻 404。</b>查询条件写成 {@code (workspace_id, public_id)}，
 *       于是"这条笔记属于路径里的那个空间吗"在 SQL 层面就不可能答错。
 *       若只按 {@code public_id} 查，调用方必须再补一次比对 ——
 *       而任何"必须先比对再使用"的返回值都存在忘记比对的可能。</li>
 * </ul>
 *
 * <h2>编辑开放、删除收紧</h2>
 * 任何成员都能编辑笔记（协作内容），而删除区分归属：普通成员只能删自己创建的，
 * 拥有者与管理员可以删任何一条。这条规则不写在这里，也不写在 Service 的 if 里，
 * 而是集中在 {@code WorkspaceAction} 的矩阵上 —— 那才是可以被逐格断言的地方。
 * 接口层只负责把"这个人是谁、要动哪条资源"传下去。
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspacePublicId}/notes")
@Tag(name = "Workspace · Notes", description = "空间内协作笔记")
public class NoteController {

    private final NoteService noteService;
    private final WorkspaceProperties properties;

    /**
     * 构造注入。
     *
     * @param noteService 笔记服务
     * @param properties  空间模块配置（分页默认值）
     */
    public NoteController(NoteService noteService, WorkspaceProperties properties) {
        this.noteService = noteService;
        this.properties = properties;
    }

    /**
     * 分页查询空间内笔记。
     *
     * @param workspacePublicId 空间对外标识
     * @param page              页码，从 1 开始
     * @param size              页大小，省略时用配置的默认值
     * @param principal         当前用户
     * @return 分页结果，按最后编辑时间倒序
     */
    @GetMapping
    @PreAuthorize("hasAuthority('note:read')")
    @Operation(summary = "笔记列表",
            description = "按最后编辑时间倒序。列表项只带 summary，不带正文 —— "
                    + "正文是 MEDIUMTEXT，一页 20 条会把几百 KB 内容搬进内存再丢掉。")
    public PageResponse<NoteCardResponse> list(
            @PathVariable("workspacePublicId") String workspacePublicId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return PageResponse.from(
                noteService.list(principal.userId(), workspacePublicId, page, effectiveSize(size)),
                NoteCardResponse::from);
    }

    /**
     * 创建笔记。
     *
     * @param workspacePublicId 空间对外标识
     * @param principal         当前用户
     * @param request           笔记内容
     * @return 新建笔记详情
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('note:create')")
    @Operation(summary = "创建笔记",
            description = "任何成员都可以创建。正文按 Markdown 在服务端渲染并净化一次，"
                    + "bodyMd 与 bodyHtml 一起落库（ADR 0005）—— 前端不应再次渲染 Markdown。")
    public NoteDetailResponse create(@PathVariable("workspacePublicId") String workspacePublicId,
                                     @AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @RequestBody NoteRequest request) {
        return NoteDetailResponse.from(noteService.create(
                principal.userId(), workspacePublicId, request.title(), request.bodyMd()));
    }

    /**
     * 查询笔记详情。
     *
     * @param workspacePublicId 空间对外标识
     * @param notePublicId      笔记对外标识
     * @param principal         当前用户
     * @return 详情
     */
    @GetMapping("/{notePublicId}")
    @PreAuthorize("hasAuthority('note:read')")
    @Operation(summary = "笔记详情",
            description = "同时返回 bodyMd（供编辑器精确往返）与 bodyHtml（展示直接插入）。")
    public NoteDetailResponse detail(@PathVariable("workspacePublicId") String workspacePublicId,
                                     @PathVariable("notePublicId") String notePublicId,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return NoteDetailResponse.from(
                noteService.detail(principal.userId(), workspacePublicId, notePublicId));
    }

    /**
     * 编辑笔记。任何成员都可以。
     *
     * @param workspacePublicId 空间对外标识
     * @param notePublicId      笔记对外标识
     * @param principal         当前用户
     * @param request           新内容
     * @return 更新后的详情
     */
    @PutMapping("/{notePublicId}")
    @PreAuthorize("hasAuthority('note:update')")
    @Operation(summary = "编辑笔记",
            description = "任何成员都可以编辑（协作内容）。author_id 不会被改动 —— "
                    + "更新语句里根本没有这一列，因此不存在'把别人的笔记过户到自己名下'的可能。")
    public NoteDetailResponse update(@PathVariable("workspacePublicId") String workspacePublicId,
                                     @PathVariable("notePublicId") String notePublicId,
                                     @AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @RequestBody NoteRequest request) {
        return NoteDetailResponse.from(noteService.update(principal.userId(), workspacePublicId,
                notePublicId, request.title(), request.bodyMd()));
    }

    /**
     * 删除笔记（软删除）。
     *
     * @param workspacePublicId 空间对外标识
     * @param notePublicId      笔记对外标识
     * @param principal         当前用户
     */
    @DeleteMapping("/{notePublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('note:delete')")
    @Operation(summary = "删除笔记",
            description = "普通成员只能删自己创建的；拥有者与管理员可以删任何一条。"
                    + "无权限时返回 403（空间可见的前提下），空间不可见时 404。")
    public void delete(@PathVariable("workspacePublicId") String workspacePublicId,
                       @PathVariable("notePublicId") String notePublicId,
                       @AuthenticationPrincipal UserPrincipal principal) {
        noteService.delete(principal.userId(), workspacePublicId, notePublicId);
    }

    /**
     * 解析实际生效的页大小。
     *
     * @param size 客户端请求的页大小，可为 null
     * @return 传给服务层的页大小
     */
    private int effectiveSize(Integer size) {
        return size == null ? properties.feed().defaultPageSize() : size;
    }
}
