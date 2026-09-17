package ai.camphub.identity.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.identity.domain.PasswordPolicy;
import ai.camphub.identity.domain.RefreshTokenRecord;
import ai.camphub.identity.domain.User;
import ai.camphub.identity.domain.UserCredential;
import ai.camphub.identity.infrastructure.RefreshTokenMapper;
import ai.camphub.identity.infrastructure.UserCredentialMapper;
import ai.camphub.identity.infrastructure.UserMapper;
import ai.camphub.platform.audit.app.AuditService;
import ai.camphub.platform.audit.domain.AuditAction;
import ai.camphub.platform.audit.domain.AuditResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号自助服务：查看与修改个人资料、修改密码、管理自己的设备会话。
 *
 * <h2>本类全部方法都在处理"自己的"资源</h2>
 * 因此每个方法都以"当前登录用户的 ID"为起点，而不是接受一个来自外部的用户标识。
 * 这一条看似显然，但它正是防住 IDOR 的关键：
 * 只要接口的参数里从来不出现"要操作哪个用户"，就不存在"传别人的 ID"这个攻击面。
 *
 * <p>唯一的例外是会话下线（需要指定会话 ID），那里必须显式校验归属 ——
 * 见 {@link #revokeSession(long, long)}。
 */
@Service
public class AccountService {

    private final UserMapper userMapper;
    private final UserCredentialMapper userCredentialMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RefreshTokenService refreshTokenService;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param userMapper           用户 Mapper
     * @param userCredentialMapper 凭据 Mapper
     * @param refreshTokenMapper   刷新令牌 Mapper
     * @param passwordEncoder      密码编码器
     * @param passwordPolicy       密码策略
     * @param refreshTokenService  刷新令牌服务
     * @param sessionService       会话服务
     * @param auditService         审计服务
     * @param clock                时钟
     */
    public AccountService(UserMapper userMapper,
                          UserCredentialMapper userCredentialMapper,
                          RefreshTokenMapper refreshTokenMapper,
                          PasswordEncoder passwordEncoder,
                          PasswordPolicy passwordPolicy,
                          RefreshTokenService refreshTokenService,
                          SessionService sessionService,
                          AuditService auditService,
                          Clock clock) {
        this.userMapper = userMapper;
        this.userCredentialMapper = userCredentialMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.refreshTokenService = refreshTokenService;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * 查询自己的账号。
     *
     * @param userId 当前用户 ID
     * @return 用户
     */
    public User getSelf(long userId) {
        return requireUser(userId);
    }

    /**
     * 修改个人资料。
     *
     * @param userId    当前用户 ID
     * @param nickname  昵称
     * @param avatarUrl 头像地址，可为 null（表示清空）
     * @param bio       简介，可为 null
     * @return 更新后的用户
     */
    @Transactional
    public User updateProfile(long userId, String nickname, String avatarUrl, String bio) {
        requireUser(userId);
        userMapper.updateProfile(userId, nickname, avatarUrl, bio);
        auditService.record(AuditAction.USER_PROFILE_UPDATE, AuditResult.SUCCESS, userId, "USER", null, null);
        return requireUser(userId);
    }

    /**
     * 修改密码。
     *
     * <h2>为什么改密后要"撤销全部会话，但为当前设备重新签发"</h2>
     * 改密的动机通常是"怀疑密码泄露"，因此必须让其它设备上的登录状态立即失效 ——
     * 否则改密就挡不住已经登录进来的攻击者。
     * 但同时把正在操作的这台设备也踢下线，会让用户困惑于"我刚改完就被登出"，
     * 而且没有任何安全收益：这台设备刚刚证明了它同时知道旧密码和新密码。
     *
     * @param userId          当前用户 ID
     * @param currentPassword 当前密码
     * @param newPassword     新密码
     * @param device          设备标识，用于为当前设备重新建立会话
     * @return 为当前设备新签发的令牌对
     */
    @Transactional
    public IssuedTokens changePassword(long userId, String currentPassword, String newPassword, String device) {
        User user = requireUser(userId);
        UserCredential credential = userCredentialMapper.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "用户 " + user.publicId() + " 缺少凭据记录，数据库状态不一致"));

        if (!passwordEncoder.matches(currentPassword, credential.passwordHash())) {
            auditService.record(AuditAction.AUTH_PASSWORD_CHANGE, AuditResult.FAILURE, userId,
                    "USER", user.publicId(), Map.of("reason", "current_password_mismatch"));
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前密码不正确");
        }

        List<String> problems = passwordPolicy.violations(newPassword, user.username(), user.email());
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "新密码不符合安全要求：" + String.join("；", problems));
        }
        if (passwordEncoder.matches(newPassword, credential.passwordHash())) {
            // 换成一个"看起来改过了但实际没变"的密码，会让人误以为旧密码已经失效，
            // 这是真实存在的风险场景（比如怀疑泄露后"改"了密码但输错了）
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码不能与当前密码相同");
        }

        Instant now = clock.instant();
        userCredentialMapper.updatePassword(userId, passwordEncoder.encode(newPassword), now);

        refreshTokenService.revokeAllSessions(userId, AuditAction.AUTH_PASSWORD_CHANGE);

        // 关键：revokeAllSessions 递增了 token_version，必须重新读取用户再签发令牌。
        // 若沿用上面读到的旧对象，新访问令牌会带着旧的世代号签发，一出门就被自己的
        // 鉴权过滤器判定为"已撤销"——这个缺陷不会在编译期暴露，只会在运行期表现为
        // "改完密码立刻 401"。
        User refreshed = requireUser(userId);
        return sessionService.createSession(refreshed, device, null);
    }

    /**
     * 列出自己的有效会话（登录的设备）。
     *
     * @param userId 当前用户 ID
     * @return 有效会话列表
     */
    public List<RefreshTokenRecord> listSessions(long userId) {
        return refreshTokenService.listActiveSessions(userId);
    }

    /**
     * 下线指定的设备会话。
     *
     * <p><b>越权时返回 404 而不是 403</b>：403 等于承认"这个会话确实存在，只是不归你"，
     * 攻击者由此可以确认某个 ID 是否有效，进而估算平台规模。
     * 统一返回 404 让"不存在"与"不属于你"在外部完全不可区分 ——
     * 这也是 docs/03-domain-permission.md §10.3 第二层防线的既定做法。
     *
     * @param userId    当前用户 ID
     * @param sessionId 会话 ID
     */
    @Transactional
    public void revokeSession(long userId, long sessionId) {
        RefreshTokenRecord session = refreshTokenMapper.findById(sessionId)
                .filter(token -> token.userId() == userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        refreshTokenMapper.revoke(session.id(), clock.instant());
        auditService.record(AuditAction.SESSION_REVOKE, AuditResult.SUCCESS, userId,
                "SESSION", String.valueOf(session.id()), null);
    }

    /**
     * 取当前用户，不存在则视为凭据已失效。
     *
     * @param userId 用户 ID
     * @return 用户
     */
    private User requireUser(long userId) {
        return userMapper.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_REVOKED));
    }
}
