package ai.camphub.workspace.api;

import ai.camphub.common.web.PageResponse;
import ai.camphub.identity.domain.UserPrincipal;
import ai.camphub.workspace.app.WorkspaceService;
import ai.camphub.workspace.config.WorkspaceProperties;
import ai.camphub.workspace.domain.WorkspaceMemberRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
 * 空间接口：空间本身、成员与邀请。
 *
 * <h2>与社区接口最大的不同：这里没有公开端点</h2>
 * 社区的内容是公开的，因此它的读接口不带身份也能访问。空间是私有域 ——
 * 列表、详情、成员、邀请全部要求已认证，且每一次访问都要经过
 * {@code AuthorizationService} 的资源级判定。安全过滤链上没有为
 * {@code /api/v1/workspaces/**} 写任何放行规则，因此它们落在 {@code anyRequest().authenticated()} 上 ——
 * <b>默认拒绝</b>，这正是私有域想要的默认方向。
 *
 * <h2>三层防线在这份文件里的落点</h2>
 * <ol>
 *   <li><b>第一层</b>：每个方法上的 {@code @PreAuthorize} —— 只判<b>身份能力</b>
 *       （这个账号有没有"创建空间"这类能力），不做任何资源查询。失败一律 403。</li>
 *   <li><b>第二层</b>：{@code WorkspaceService} 内部对每一个空间的判定 ——
 *       不可见时 404，可见但无权限时 403。</li>
 *   <li><b>第三层</b>：数据访问层自动追加空间过滤（在 Service 之下，这个文件里看不见）。</li>
 * </ol>
 * 第一层刻意不碰资源，理由写在 {@code AuthorizationService} 的类注释里：
 * 若它也在 404 语义上下判断，会出现"两层都在答同一个问题、但给出不同状态码"的情况，
 * 而排查时无法从响应判断是谁拒绝的。
 *
 * <h2>为什么这里没有"退出空间"的权限点</h2>
 * {@code DELETE .../members/me} 只要求已认证。退出处置的是<b>自己已经获得的成员关系</b>，
 * 不是对别人的管理权 —— 它在权限点这一层没有对应能力。若把它挂在
 * {@code workspace:member:remove} 上（那样代码更省事），将来任何一个
 * "不能移除成员但应当能自己退出"的角色都会走不通，而修它只能再加一个权限点。
 */
@RestController
@RequestMapping("/api/v1/workspaces")
@Tag(name = "Workspace", description = "协作空间：空间、成员与邀请")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceProperties properties;

    /**
     * 构造注入。
     *
     * @param workspaceService 空间服务
     * @param properties       空间模块配置（分页默认值）
     */
    public WorkspaceController(WorkspaceService workspaceService, WorkspaceProperties properties) {
        this.workspaceService = workspaceService;
        this.properties = properties;
    }

    // ------------------------------------------------------------------------
    // 空间
    // ------------------------------------------------------------------------

    /**
     * 分页查询我参与的空间（我拥有的 + 我被邀请加入的）。
     *
     * @param page      页码，从 1 开始
     * @param size      页大小，省略时用配置的默认值
     * @param principal 当前用户
     * @return 分页结果，每项带我在该空间内的身份
     */
    @GetMapping
    @PreAuthorize("hasAuthority('workspace:read')")
    @Operation(summary = "我的空间", description = "返回我拥有的与我作为成员加入的空间，每项带 myRole。")
    public PageResponse<WorkspaceResponse> list(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return PageResponse.from(
                workspaceService.listMine(principal.userId(), page, effectiveSize(size)),
                WorkspaceResponse::from);
    }

    /**
     * 创建空间。创建者即拥有者。
     *
     * @param principal 当前用户
     * @param request   空间信息
     * @return 新空间（myRole 恒为 OWNER）
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('workspace:create')")
    @Operation(summary = "创建空间",
            description = "需要登录。创建者成为拥有者。visibility 省略时为 PRIVATE —— "
                    + "私有内容绝不自动公开，这条硬约束在类型层面就默认落在最保守的一侧。")
    public WorkspaceResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                    @Valid @RequestBody WorkspaceRequest request) {
        return WorkspaceResponse.from(workspaceService.create(
                principal.userId(), request.name(), request.description(), request.toVisibility()));
    }

    /**
     * 查询空间详情。
     *
     * @param workspacePublicId 空间对外标识
     * @param principal         当前用户
     * @return 空间详情，带我在其中的身份
     */
    @GetMapping("/{workspacePublicId}")
    @PreAuthorize("hasAuthority('workspace:read')")
    @Operation(summary = "空间详情",
            description = "看不到的空间一律返回 404（不返回 403）—— 403 会承认这个空间存在。")
    public WorkspaceResponse detail(@PathVariable("workspacePublicId") String workspacePublicId,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        return WorkspaceResponse.from(workspaceService.detail(principal.userId(), workspacePublicId));
    }

    /**
     * 修改空间设置。仅拥有者。
     *
     * @param workspacePublicId 空间对外标识
     * @param principal         当前用户
     * @param request           新设置
     * @return 更新后的空间
     */
    @PutMapping("/{workspacePublicId}")
    @PreAuthorize("hasAuthority('workspace:update')")
    @Operation(summary = "修改空间设置",
            description = "仅拥有者。管理员能改成员，不能改可见性 —— 改可见性等于改'谁能看到这个空间'。")
    public WorkspaceResponse update(@PathVariable("workspacePublicId") String workspacePublicId,
                                    @AuthenticationPrincipal UserPrincipal principal,
                                    @Valid @RequestBody WorkspaceRequest request) {
        return WorkspaceResponse.from(workspaceService.update(principal.userId(), workspacePublicId,
                request.name(), request.description(), request.toVisibility()));
    }

    /**
     * 删除空间（软删除）。仅拥有者。
     *
     * @param workspacePublicId 空间对外标识
     * @param principal         当前用户
     */
    @DeleteMapping("/{workspacePublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('workspace:delete')")
    @Operation(summary = "删除空间",
            description = "仅拥有者。软删除：成员行、笔记与文档都还在库里，只是空间不再可见。")
    public void delete(@PathVariable("workspacePublicId") String workspacePublicId,
                       @AuthenticationPrincipal UserPrincipal principal) {
        workspaceService.delete(principal.userId(), workspacePublicId);
    }

    // ------------------------------------------------------------------------
    // 成员
    // ------------------------------------------------------------------------

    /**
     * 列出空间成员。拥有者作为第一行合成返回。
     *
     * @param workspacePublicId 空间对外标识
     * @param principal         当前用户
     * @return 成员列表，拥有者排在最前
     */
    @GetMapping("/{workspacePublicId}/members")
    @PreAuthorize("hasAuthority('workspace:read')")
    @Operation(summary = "成员列表",
            description = "拥有者在库里不是成员行，这里把它合成进列表并排在第一行 —— "
                    + "否则界面上会出现'这个空间没有管理员'的错觉。")
    public List<MemberResponse> members(@PathVariable("workspacePublicId") String workspacePublicId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return workspaceService.members(principal.userId(), workspacePublicId).stream()
                .map(MemberResponse::from)
                .toList();
    }

    /**
     * 自己退出空间。
     *
     * <p>路径里用 {@code me} 而不是一个用户标识：与 Phase 02 的 {@code /api/v1/users/me}
     * 同一约束 —— <b>不给出"要操作哪个用户"这个参数</b>，于是"把别人的 id 填进去"
     * 这类越权尝试在接口形状上就不成立。
     *
     * @param workspacePublicId 空间对外标识
     * @param principal         当前用户
     */
    @DeleteMapping("/{workspacePublicId}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "退出空间",
            description = "无需额外权限点：处置的是自己已获得的成员关系。"
                    + "拥有者不能退出（40023）—— 允许的话空间会变成没有人能管理、也无人能删除的孤儿。")
    public void leave(@PathVariable("workspacePublicId") String workspacePublicId,
                      @AuthenticationPrincipal UserPrincipal principal) {
        workspaceService.leave(principal.userId(), workspacePublicId);
    }

    /**
     * 移除成员。拥有者与管理员。
     *
     * @param workspacePublicId  空间对外标识
     * @param targetUserPublicId 目标用户对外标识
     * @param principal          当前用户
     */
    @DeleteMapping("/{workspacePublicId}/members/{targetUserPublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('workspace:member:remove')")
    @Operation(summary = "移除成员",
            description = "拥有者与管理员。目标是拥有者时返回 404 —— 它不在成员表里，"
                    + "从这张表的角度看确实不是成员。")
    public void removeMember(@PathVariable("workspacePublicId") String workspacePublicId,
                             @PathVariable("targetUserPublicId") String targetUserPublicId,
                             @AuthenticationPrincipal UserPrincipal principal) {
        workspaceService.removeMember(principal.userId(), workspacePublicId, targetUserPublicId);
    }

    /**
     * 修改成员角色。拥有者与管理员。
     *
     * @param workspacePublicId  空间对外标识
     * @param targetUserPublicId 目标用户对外标识
     * @param principal          当前用户
     * @param request            新角色
     */
    @PutMapping("/{workspacePublicId}/members/{targetUserPublicId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('workspace:member:role:update')")
    @Operation(summary = "修改成员角色",
            description = "拥有者与管理员。目标是拥有者时返回 404 —— 它的角色由 owner_id 决定，不在成员表里。")
    public void updateMemberRole(@PathVariable("workspacePublicId") String workspacePublicId,
                                 @PathVariable("targetUserPublicId") String targetUserPublicId,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 @Valid @RequestBody MemberRoleRequest request) {
        workspaceService.updateMemberRole(principal.userId(), workspacePublicId,
                targetUserPublicId, request.toRole());
    }

    // ------------------------------------------------------------------------
    // 邀请
    // ------------------------------------------------------------------------

    /**
     * 列出待处理的邀请。
     *
     * @param workspacePublicId 空间对外标识
     * @param principal         当前用户
     * @return 待处理邀请（含邀请码）
     */
    @GetMapping("/{workspacePublicId}/invites")
    @PreAuthorize("hasAuthority('workspace:member:invite')")
    @Operation(summary = "待处理邀请",
            description = "拥有者与管理员可见。响应里带邀请码 —— 邀请人需要把它转达给被邀请人，"
                    + "系统不代发消息（通知属于 Phase 10）。")
    public List<InviteResponse> invites(@PathVariable("workspacePublicId") String workspacePublicId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return workspaceService.pendingInvites(principal.userId(), workspacePublicId).stream()
                .map(InviteResponse::from)
                .toList();
    }

    /**
     * 邀请一个用户。
     *
     * @param workspacePublicId 空间对外标识
     * @param principal         当前用户
     * @param request           被邀请人与角色
     * @return 新建的邀请（含邀请码）
     */
    @PostMapping("/{workspacePublicId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('workspace:member:invite')")
    @Operation(summary = "邀请成员",
            description = "定向邀请：生成的码只有被邀请人能兑换，非受邀人凭同一串码兑换返回 404。"
                    + "被邀请人已在空间内、或邀请自己时返回 409。")
    public InviteResponse invite(@PathVariable("workspacePublicId") String workspacePublicId,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 @Valid @RequestBody InviteRequest request) {
        WorkspaceMemberRole role = request.toRole();
        return InviteResponse.from(workspaceService.invite(principal.userId(), workspacePublicId,
                request.username(), role));
    }

    /**
     * 撤销一条待处理邀请。
     *
     * @param workspacePublicId 空间对外标识
     * @param code              邀请码
     * @param principal         当前用户
     */
    @DeleteMapping("/{workspacePublicId}/invites/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('workspace:member:invite')")
    @Operation(summary = "撤销邀请",
            description = "只有仍处于 PENDING 的邀请可以被撤销。已兑换的返回 404 —— "
                    + "已经生效的成员关系不能被一次撤销抹掉。")
    public void revokeInvite(@PathVariable("workspacePublicId") String workspacePublicId,
                             @PathVariable("code") String code,
                             @AuthenticationPrincipal UserPrincipal principal) {
        workspaceService.revokeInvite(principal.userId(), workspacePublicId, code);
    }

    /**
     * 解析实际生效的页大小。
     *
     * <p>只在这里做"省略则用默认值"，而不是给 {@code @RequestParam} 写
     * {@code defaultValue = "20"}：默认值应当只有一个来源（配置文件）。
     * 超过上限的截断发生在服务层（它读同一份配置），因此这里不做上界判断 ——
     * 两处都判一次，早晚会有一处被改而另一处没改。
     *
     * @param size 客户端请求的页大小，可为 null
     * @return 传给服务层的页大小
     */
    private int effectiveSize(Integer size) {
        return size == null ? properties.feed().defaultPageSize() : size;
    }
}
