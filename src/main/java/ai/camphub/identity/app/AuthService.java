package ai.camphub.identity.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.identity.config.SecurityProperties;
import ai.camphub.identity.domain.PasswordPolicy;
import ai.camphub.identity.domain.RandomValues;
import ai.camphub.identity.domain.User;
import ai.camphub.identity.domain.UserCredential;
import ai.camphub.identity.domain.UserStatus;
import ai.camphub.identity.infrastructure.UserCredentialMapper;
import ai.camphub.identity.infrastructure.UserMapper;
import ai.camphub.platform.audit.app.AuditService;
import ai.camphub.platform.audit.domain.AuditAction;
import ai.camphub.platform.audit.domain.AuditResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册、登录与登出。
 *
 * <h2>两条贯穿全类的安全原则</h2>
 *
 * <p><b>一、不泄漏账号是否存在。</b>登录时"账号不存在"与"密码错误"返回完全相同的
 * 错误码与文案；并且账号不存在时仍然执行一次 BCrypt 校验（对预置的哑哈希），
 * 让两条路径的<b>响应耗时</b>也接近。否则攻击者可以拿一份邮箱列表逐条试，
 * 用响应内容或响应时间就能筛出平台上有哪些账号，为后续定向钓鱼与撞库提供名单。
 *
 * <p><b>二、账号状态的检查放在口令校验之后。</b>"已锁定""已停用"这类提示对真实用户很重要，
 * 但对攻击者同样是信息。放在口令校验之后，只有已经知道口令的人才会看到它 ——
 * 而那时账号是否存在对他已不是秘密。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 注册时自动授予的角色。与 V2 迁移中种子的角色码一致。 */
    private static final String DEFAULT_ROLE_CODE = "USER";

    /** 登录标识含该字符时按邮箱解析，否则按用户名。用户名的字符集已禁止 {@code @}，因此不存在歧义。 */
    private static final char EMAIL_MARKER = '@';

    private final UserMapper userMapper;
    private final UserCredentialMapper userCredentialMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final LoginAttemptService loginAttemptService;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final SecurityProperties securityProperties;
    private final Clock clock;

    /**
     * 用于"账号不存在"分支的哑哈希，使该分支与"密码错误"分支的耗时相当。
     *
     * <p>在构造时现算而不是硬编码一串 BCrypt 文本：硬编码的哈希对应的是一个
     * 已知明文，一旦有人反推出它，就可能被拿去构造"已知口令"的探测请求；
     * 每次启动随机生成可以彻底避免这层联想，代价只是一次约 250ms 的启动开销。
     */
    private final String dummyPasswordHash;

    /**
     * 构造注入。
     *
     * @param userMapper           用户 Mapper
     * @param userCredentialMapper 凭据 Mapper
     * @param passwordEncoder      密码编码器
     * @param passwordPolicy       密码策略
     * @param loginAttemptService  登录失败记账
     * @param sessionService       会话服务
     * @param auditService         审计服务
     * @param securityProperties   安全配置
     * @param clock                时钟
     */
    public AuthService(UserMapper userMapper,
                       UserCredentialMapper userCredentialMapper,
                       PasswordEncoder passwordEncoder,
                       PasswordPolicy passwordPolicy,
                       LoginAttemptService loginAttemptService,
                       SessionService sessionService,
                       AuditService auditService,
                       SecurityProperties securityProperties,
                       Clock clock) {
        this.userMapper = userMapper;
        this.userCredentialMapper = userCredentialMapper;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.loginAttemptService = loginAttemptService;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.securityProperties = securityProperties;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(RandomValues.opaqueToken());
    }

    /**
     * 注册并直接建立会话。
     *
     * <p>注册后自动登录，省掉"刚设完密码马上又输一遍"的多余步骤。
     *
     * @param command 注册命令
     * @return 令牌对
     */
    @Transactional
    public IssuedTokens register(RegisterCommand command) {
        String username = command.username().trim();
        String email = command.email().trim();
        String nickname = command.nickname() == null || command.nickname().isBlank()
                ? username
                : command.nickname().trim();

        requirePasswordAcceptable(command.password(), username, email);

        if (userMapper.countByUsername(username) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "该用户名已被使用");
        }
        if (userMapper.countByEmail(email) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "该邮箱已被注册");
        }

        Instant now = clock.instant();
        String publicId = RandomValues.publicId();
        User newUser = new User(0L, publicId, username, email, nickname, null, null,
                UserStatus.ACTIVE, 1, now, now);

        try {
            userMapper.insert(newUser);
        } catch (DuplicateKeyException e) {
            // 上面的 count 检查与这里的插入之间存在竞态：两个并发注册可能都通过了检查。
            // 唯一索引是最终裁决者，这里把它翻译成同一种对外错误，
            // 否则用户会看到一次莫名其妙的 500 —— 而实际上他的操作是完全合法的。
            throw new BusinessException(ErrorCode.CONFLICT, "用户名或邮箱已被使用");
        }

        // 插入不返回自增主键（原因见 UserMapper 注释），按已知的 public_id 回查
        User created = userMapper.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalStateException("注册后无法回查用户：" + publicId));

        userCredentialMapper.insert(new UserCredential(
                created.id(),
                passwordEncoder.encode(command.password()),
                0,
                null,
                null,
                now));
        userMapper.assignRoleByCode(created.id(), DEFAULT_ROLE_CODE);

        auditService.record(AuditAction.AUTH_REGISTER, AuditResult.SUCCESS, created.id(),
                "USER", created.publicId(), null);

        return sessionService.createSession(created, command.device(), null);
    }

    /**
     * 登录。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：失败路径上的"失败计数"与"审计"
     * 必须独立提交，否则外层事务因抛异常回滚时会把它们一起抹掉
     * （详见 {@link LoginAttemptService} 的类注释）。
     *
     * @param command 登录命令
     * @return 令牌对
     */
    public IssuedTokens login(LoginCommand command) {
        Instant now = clock.instant();
        String identifier = command.identifier().trim();

        Optional<User> found = findByLoginIdentifier(identifier);
        if (found.isEmpty()) {
            // 走与"密码错误"等价的耗时路径，抹平响应时间差异
            passwordEncoder.matches(command.password(), dummyPasswordHash);
            auditService.record(AuditAction.AUTH_LOGIN_FAILURE, AuditResult.FAILURE, null,
                    "USER", null, Map.of("reason", "unknown_identifier"));
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }

        User user = found.get();
        UserCredential credential = userCredentialMapper.findByUserId(user.id())
                .orElseThrow(() -> new IllegalStateException(
                        "用户 " + user.publicId() + " 缺少凭据记录，数据库状态不一致"));

        if (!passwordEncoder.matches(command.password(), credential.passwordHash())) {
            loginAttemptService.recordFailure(
                    user.id(),
                    securityProperties.login().maxFailures(),
                    securityProperties.login().lockDuration());
            auditService.record(AuditAction.AUTH_LOGIN_FAILURE, AuditResult.FAILURE, user.id(),
                    "USER", user.publicId(), Map.of("reason", "bad_password"));
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }

        // 口令已确认，此后揭示账号状态不再泄漏"账号是否存在"
        if (credential.isLockedAt(now)) {
            auditService.record(AuditAction.AUTH_LOGIN_FAILURE, AuditResult.FAILURE, user.id(),
                    "USER", user.publicId(), Map.of("reason", "account_locked"));
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_USABLE,
                    "账号因多次登录失败被临时锁定，请稍后重试");
        }
        if (!user.isActive()) {
            auditService.record(AuditAction.AUTH_LOGIN_FAILURE, AuditResult.FAILURE, user.id(),
                    "USER", user.publicId(), Map.of("reason", "account_" + user.status().name().toLowerCase()));
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_USABLE, "账号当前不可用，请联系管理员");
        }

        IssuedTokens issued = sessionService.createSession(user, command.device(), null);
        auditService.record(AuditAction.AUTH_LOGIN_SUCCESS, AuditResult.SUCCESS, user.id(),
                "USER", user.publicId(), null);
        log.info("登录成功 userId={} username={}", user.id(), user.username());
        return issued;
    }

    /**
     * 校验密码是否满足策略。
     *
     * @param password 候选密码
     * @param username 登录名（用于判断密码是否与账号相关）
     * @param email    邮箱
     */
    private void requirePasswordAcceptable(String password, String username, String email) {
        List<String> problems = passwordPolicy.violations(password, username, email);
        if (!problems.isEmpty()) {
            // 一次性返回全部问题，用户不必"改一次被拒一次"
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "密码不符合安全要求：" + String.join("；", problems));
        }
    }

    /**
     * 按登录标识查找用户。
     *
     * @param identifier 用户名或邮箱
     * @return 用户
     */
    private Optional<User> findByLoginIdentifier(String identifier) {
        if (identifier.indexOf(EMAIL_MARKER) >= 0) {
            return userMapper.findByEmail(identifier);
        }
        return userMapper.findByUsername(identifier);
    }

    /**
     * 注册命令。
     *
     * @param username 登录名
     * @param email    邮箱
     * @param password 密码
     * @param nickname 昵称，可为空（为空时取登录名）
     * @param device   设备标识，可为空
     */
    public record RegisterCommand(String username, String email, String password, String nickname, String device) {
    }

    /**
     * 登录命令。
     *
     * @param identifier 用户名或邮箱
     * @param password   密码
     * @param device     设备标识，可为空
     */
    public record LoginCommand(String identifier, String password, String device) {
    }
}
