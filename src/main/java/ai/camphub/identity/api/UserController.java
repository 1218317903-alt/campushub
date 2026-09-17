package ai.camphub.identity.api;

import ai.camphub.identity.app.AccountService;
import ai.camphub.identity.app.IssuedTokens;
import ai.camphub.identity.domain.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号自助接口。
 *
 * <h2>路径里为什么是 {@code me} 而不是 {@code {userId}}</h2>
 * 这是一个刻意的接口设计约束：<b>本控制器的所有路径都不接受外部传入的用户标识</b>。
 * 当前用户一律取自鉴权上下文（{@link AuthenticationPrincipal}）。
 *
 * <p>好处不是"少写一次校验"，而是<b>从接口形状上消除了整类漏洞</b>：
 * 只要 URL 里不存在"要操作哪个用户"这个参数，就不可能出现
 * "把别人的 ID 填进去"的越权尝试，也就不依赖每个开发者都记得加归属判断。
 * 将来若确实需要访问他人资源（如查看公开主页），应当是新开一个
 * {@code /api/v1/users/{publicId}} 之类的只读接口，并由资源级鉴权明确它开放哪些字段 ——
 * 而不是让这个自助接口变成通用入口。
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "账号自助：个人资料、密码、登录设备")
public class UserController {

    private final AccountService accountService;

    /**
     * 构造注入。
     *
     * @param accountService 账号服务
     */
    public UserController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * 查询自己的资料。
     *
     * @param principal 当前用户
     * @return 资料
     */
    @GetMapping("/me")
    @Operation(summary = "查询自己的资料")
    public UserProfileResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return UserProfileResponse.from(accountService.getSelf(principal.userId()));
    }

    /**
     * 修改自己的资料。
     *
     * @param principal 当前用户
     * @param request   修改请求
     * @return 更新后的资料
     */
    @PatchMapping("/me")
    @Operation(summary = "修改自己的资料")
    public UserProfileResponse updateMe(@AuthenticationPrincipal UserPrincipal principal,
                                        @Valid @RequestBody UpdateProfileRequest request) {
        return UserProfileResponse.from(accountService.updateProfile(
                principal.userId(), request.nickname(), request.avatarUrl(), request.bio()));
    }

    /**
     * 修改密码。
     *
     * @param principal 当前用户
     * @param request   修改密码请求
     * @return 为当前设备新签发的令牌对（其它设备会被下线）
     */
    @PostMapping("/me/password")
    @Operation(summary = "修改密码",
            description = "需要提供当前密码。成功后其它设备的登录状态立即失效，并为当前设备重新签发令牌对。")
    public IssuedTokensResponse changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        IssuedTokens issued = accountService.changePassword(
                principal.userId(), request.currentPassword(), request.newPassword(), request.device());
        return IssuedTokensResponse.from(issued);
    }

    /**
     * 列出自己的登录设备。
     *
     * @param principal 当前用户
     * @return 有效会话列表
     */
    @GetMapping("/me/sessions")
    @Operation(summary = "列出自己的登录设备")
    public List<SessionResponse> sessions(@AuthenticationPrincipal UserPrincipal principal) {
        return accountService.listSessions(principal.userId()).stream()
                .map(SessionResponse::from)
                .toList();
    }

    /**
     * 下线指定的设备会话。
     *
     * @param principal 当前用户
     * @param sessionId 会话 ID
     */
    @DeleteMapping("/me/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "下线指定设备",
            description = "只能下线自己的会话。不属于自己的会话一律返回 404，避免泄漏该会话是否存在。")
    public void revokeSession(@AuthenticationPrincipal UserPrincipal principal,
                              @PathVariable("sessionId") long sessionId) {
        accountService.revokeSession(principal.userId(), sessionId);
    }
}
