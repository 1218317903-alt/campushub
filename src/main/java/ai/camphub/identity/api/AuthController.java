package ai.camphub.identity.api;

import ai.camphub.identity.app.AuthRateLimiter;
import ai.camphub.identity.app.AuthService;
import ai.camphub.identity.app.RefreshTokenService;
import ai.camphub.identity.domain.UserPrincipal;
import ai.camphub.platform.audit.domain.AuditAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册、登录、刷新令牌、登出。
 *
 * <h2>限流为什么放在控制器而不是拦截器</h2>
 * 三个桶只作用于这三个接口，且额度各不相同。放在控制器里，
 * "哪个接口限多少"与接口定义在同一屏可见 —— 而一个全局拦截器需要
 * 用路径匹配去反推同样的对应关系，改路径时容易与限流规则脱节。
 *
 * <p>限流器本身（{@link AuthRateLimiter}）位于应用层而非基础设施层：
 * 它是业务策略，不是外部系统适配。这一点由架构测试守护，详见该类注释。
 *
 * <h2>为什么登出不需要有效访问令牌</h2>
 * 登出请求要撤销的是刷新令牌，而<b>持有刷新令牌本身就是授权</b>。
 * 若强制要求访问令牌有效，就会出现"访问令牌刚过期、想登出却被 401 拦住"的死角 ——
 * 用户在这种状态下唯一能做的就是放任那个会话继续有效。因此
 * {@code /auth/logout} 在安全配置中列为公开端点。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "注册、登录、令牌刷新与登出")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final AuthRateLimiter rateLimiter;

    /**
     * 构造注入。
     *
     * @param authService         认证服务
     * @param refreshTokenService 刷新令牌服务
     * @param rateLimiter         限流器
     */
    public AuthController(AuthService authService,
                          RefreshTokenService refreshTokenService,
                          AuthRateLimiter rateLimiter) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 注册。
     *
     * @param request 注册请求
     * @return 令牌对（注册后直接登录）
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "注册账号",
            description = "创建账号并直接返回令牌对。密码需满足密码策略；用户名与邮箱需唯一。")
    public IssuedTokensResponse register(@Valid @RequestBody RegisterRequest request) {
        rateLimiter.checkRegister();
        return IssuedTokensResponse.from(authService.register(new AuthService.RegisterCommand(
                request.username(), request.email(), request.password(), request.nickname(), request.device())));
    }

    /**
     * 登录。
     *
     * @param request 登录请求
     * @return 令牌对
     */
    @PostMapping("/login")
    @Operation(summary = "登录",
            description = "用户名或邮箱 + 密码。账号不存在与密码错误返回完全相同的错误，避免账号枚举。")
    public IssuedTokensResponse login(@Valid @RequestBody LoginRequest request) {
        rateLimiter.checkLogin();
        return IssuedTokensResponse.from(authService.login(new AuthService.LoginCommand(
                request.identifier(), request.password(), request.device())));
    }

    /**
     * 用刷新令牌换取新的令牌对（轮换）。
     *
     * @param request 刷新请求
     * @return 新的令牌对；旧刷新令牌随即失效
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌",
            description = "换取新的令牌对，旧刷新令牌立即失效（轮换）。"
                    + "若一个已失效的刷新令牌被再次使用，将判定为泄露并登出该账号的全部设备。")
    public IssuedTokensResponse refresh(@Valid @RequestBody RefreshRequest request) {
        rateLimiter.checkRefresh();
        return IssuedTokensResponse.from(refreshTokenService.rotate(request.refreshToken(), request.device()));
    }

    /**
     * 登出当前设备。
     *
     * <p>幂等：重复调用同样返回 204，不区分"令牌本来就已失效"。
     *
     * @param request 登出请求
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "登出当前设备", description = "撤销本次会话的刷新令牌。访问令牌在其剩余有效期内仍可用，但无法再换新会话。")
    public void logout(@Valid @RequestBody LogoutRequest request) {
        refreshTokenService.revokeByRawToken(request.refreshToken());
    }

    /**
     * 登出全部设备。
     *
     * @param principal 当前用户
     */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "登出全部设备",
            description = "撤销该账号的所有会话，并使已签发的访问令牌立即失效（递增令牌世代号）。")
    public void logoutAll(@AuthenticationPrincipal UserPrincipal principal) {
        refreshTokenService.revokeAllSessions(principal.userId(), AuditAction.AUTH_LOGOUT_ALL);
    }
}
