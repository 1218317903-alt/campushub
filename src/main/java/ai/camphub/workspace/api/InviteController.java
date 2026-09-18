package ai.camphub.workspace.api;

import ai.camphub.identity.domain.UserPrincipal;
import ai.camphub.workspace.app.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兑换邀请码加入空间。
 *
 * <h2>为什么这个端点不在 {@code /api/v1/workspaces} 下</h2>
 * 因为它<b>没有空间标识</b>，也不可能有。兑换的人此刻还不是空间成员，
 * 自然不知道（也不该被提前告知）那个空间的 {@code publicId} ——
 * 告诉他这个标识，等于在他还没加入之前就泄漏了这个空间的存在。
 * 路径上唯一的定位信息就是邀请码本身。
 *
 * <p>若把它写成 {@code POST /api/v1/workspaces/{workspacePublicId}/invites/{code}/accept}，
 * 强制要求一个"兑换者本来就不该有"的参数，会逼着调用方去别处搞到它 ——
 * 而那个"别处"通常就是邀请消息本身，于是 path 里出现一个纯粹为了满足路由形状的字段。
 *
 * <h2>四种拒绝，三种响应码</h2>
 * <ul>
 *   <li>码不存在 / 不是发给我的 / 已兑换 / 已撤销 → <b>404</b>。
 *       合并成同一个码，意味着"这个码存在吗"从响应里读不出来；
 *       已兑换返回 404 而不是"你已经是成员"，也是同一个取舍 ——
 *       否则攻击者可以用自己的账号批量试码，靠响应区分"存在且用过"与"不存在"。</li>
 *   <li>码是我的、但已过期 → <b>40020</b>。这一支必须与 404 分开：调用方已经证明了
 *       他就是受邀人（码在他手上），此时"过期了"是可操作的反馈（让邀请人重发）。</li>
 *   <li>我已经在这个空间里 → <b>409</b>。这与邀请的有效性无关，给一个能读懂的状态码。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/workspace-invites")
@Tag(name = "Workspace · Invites", description = "兑换定向邀请码加入空间")
public class InviteController {

    private final WorkspaceService workspaceService;

    /**
     * 构造注入。
     *
     * @param workspaceService 空间服务
     */
    public InviteController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 兑换邀请码。
     *
     * @param code      邀请码
     * @param principal 当前用户（即被邀请人）
     * @return 加入的空间，带我在其中的身份
     */
    @PostMapping("/{code}/accept")
    @PreAuthorize("hasAuthority('workspace:join')")
    @Operation(summary = "接受邀请加入空间",
            description = "定向邀请：只有被邀请人能兑换。非受邀人、不存在的码、已兑换或已撤销的码都返回 404，"
                    + "因此无法从响应判断某个码是否存在。已过期返回 40020，已是成员返回 409。")
    public WorkspaceResponse accept(@PathVariable("code") String code,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        return WorkspaceResponse.from(workspaceService.acceptInvite(principal.userId(), code));
    }
}
