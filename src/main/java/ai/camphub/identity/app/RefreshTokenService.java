package ai.camphub.identity.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.identity.domain.DeviceLabel;
import ai.camphub.identity.domain.RefreshTokenRecord;
import ai.camphub.identity.domain.TokenHasher;
import ai.camphub.identity.domain.User;
import ai.camphub.identity.infrastructure.RefreshTokenMapper;
import ai.camphub.identity.infrastructure.UserMapper;
import ai.camphub.platform.audit.app.AuditService;
import ai.camphub.platform.audit.domain.AuditAction;
import ai.camphub.platform.audit.domain.AuditResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 刷新令牌的轮换、撤销与泄露检测。
 *
 * <h2>轮换（rotation）为什么是必要的</h2>
 * 一个 30 天有效、可以不断换取访问令牌的凭据，如果不轮换，
 * 一旦泄露就是 30 天的稳定后门，而且服务端<b>没有任何办法察觉</b>泄露已经发生。
 * 每次刷新都换发新令牌并作废旧令牌之后，局面变了：正常客户端永远只持有链尾，
 * 链中间节点再次出现就说明有人拿着旧副本 —— 泄露从"不可见"变成"可判定事件"。
 *
 * <h2>关于"旧令牌被再次使用"的两种情形</h2>
 * 直观的做法是见到即判泄露并撤销全部会话。但这会误伤一种完全正常的场景：
 * 浏览器多个标签页同时到期、同时用同一个刷新令牌去换新令牌。
 * 二者在数据上的区别是"后继令牌是否存在、是否仍有效、多久之前签发的"，
 * 因此这里用 {@link #REPLAY_GRACE_WINDOW} 把两者分开：
 * <ul>
 *   <li><b>宽限窗口内的重复使用</b>：不判泄露，但也不重新签发
 *       （服务端只有哈希，无法把已发出的新令牌再读出来），
 *       返回一个明确的"请使用最新令牌"提示，客户端重新读取本地存储后即可继续。</li>
 *   <li><b>其余情况</b>：按泄露处置 —— 撤销该用户全部会话并推进令牌世代号，
 *       让攻击者手里的访问令牌也一并作废。用户需要重新登录，但攻击者被彻底清出。</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /**
     * 判定"正常并发"的时间窗。
     *
     * <p>取 5 秒：足以覆盖多标签页同时唤醒并发的请求，
     * 又远短于攻击者拿到旧令牌后可能发起请求的任何现实时间尺度。
     */
    private static final Duration REPLAY_GRACE_WINDOW = Duration.ofSeconds(5);

    private final RefreshTokenMapper refreshTokenMapper;
    private final UserMapper userMapper;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final RefreshTokenLeakHandler leakHandler;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param refreshTokenMapper 刷新令牌 Mapper
     * @param userMapper         用户 Mapper
     * @param sessionService     会话服务
     * @param auditService       审计服务
     * @param leakHandler        泄露处置（独立事务，理由见该类注释）
     * @param clock              时钟
     */
    public RefreshTokenService(RefreshTokenMapper refreshTokenMapper,
                              UserMapper userMapper,
                              SessionService sessionService,
                              AuditService auditService,
                              RefreshTokenLeakHandler leakHandler,
                              Clock clock) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.userMapper = userMapper;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.leakHandler = leakHandler;
        this.clock = clock;
    }

    /**
     * 用刷新令牌换取新的令牌对（轮换）。
     *
     * @param rawRefreshToken 客户端持有的刷新令牌原文
     * @param device          设备标识
     * @return 新的令牌对
     */
    @Transactional
    public IssuedTokens rotate(String rawRefreshToken, String device) {
        Instant now = clock.instant();
        // 与 SessionService 共用同一个规范化实现：这里需要的是"与后继令牌写库时完全一致"
        // 的设备标签，两者若各写一份，宽限窗口的判等迟早会因微小差异而永远不成立
        String deviceLabel = DeviceLabel.normalize(device);

        RefreshTokenRecord token = refreshTokenMapper.findByTokenHash(TokenHasher.sha256Hex(rawRefreshToken))
                // 令牌不存在：可能是伪造的，也可能是早已被清理的。两者都只需拒绝，
                // 不区分开来对外说明，避免给探测者提供反馈
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));

        if (token.revokedAt() != null) {
            return handleAlreadyRevoked(token, deviceLabel, now);
        }

        if (!token.expiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }

        User user = userMapper.findById(token.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
        if (!user.isActive()) {
            auditService.record(AuditAction.AUTH_TOKEN_REFRESH, AuditResult.FAILURE, user.id(),
                    "USER", user.publicId(), Map.of("reason", "account_not_active"));
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_USABLE);
        }

        // 先作废旧令牌再签发新令牌：顺序反过来的话，若签发过程中失败，
        // 用户会同时持有一个已失效的旧令牌和一个未生效的新令牌，直接失去会话
        // 条件 UPDATE 是一次性令牌的竞争点。只有真正撤销成功的请求可以签发后继；
        // 普通 SELECT 的快照可能被多个并发事务同时读到，不能据此判断自己赢得了轮换。
        if (refreshTokenMapper.revoke(token.id(), now) != 1) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID,
                    "登录状态已被其他请求更新，请使用最新的登录凭据重试");
        }
        refreshTokenMapper.updateLastUsed(token.id(), now);

        IssuedTokens issued = sessionService.createSession(user, deviceLabel, token.id());
        auditService.record(AuditAction.AUTH_TOKEN_REFRESH, AuditResult.SUCCESS, user.id(),
                "SESSION", String.valueOf(token.id()), null);
        return issued;
    }

    /**
     * 撤销单个会话。
     *
     * <p><b>必须写审计</b>：{@code AuditAction.AUTH_LOGOUT} 已在枚举中登记，
     * 而枚举的纪律是"只登记已经在写的动作"。若这里不写，日志里就会缺掉
     * "用户主动登出"这一类事件 —— 排查"某账号在某个时间点为什么掉线"时，
     * 恰恰要靠区分"用户自己登出"与"被别人挤下线"。
     *
     * @param rawRefreshToken 该会话的刷新令牌原文
     */
    @Transactional
    public void revokeByRawToken(String rawRefreshToken) {
        Instant now = clock.instant();
        refreshTokenMapper.findByTokenHash(TokenHasher.sha256Hex(rawRefreshToken))
                .ifPresent(token -> {
                    // 审计只在"这一次真的撤销了一个会话"时写。
                    // revoke 的 WHERE 带 revoked_at IS NULL，重复登出影响 0 行 ——
                    // 若无条件记账，同一个会话会留下多条 AUTH_LOGOUT，
                    // 按动作聚合出来的"登出次数"就不再等于"被撤销的会话数"。
                    if (refreshTokenMapper.revoke(token.id(), now) > 0) {
                        auditService.record(AuditAction.AUTH_LOGOUT, AuditResult.SUCCESS, token.userId(),
                                "SESSION", String.valueOf(token.id()), null);
                    }
                });
        // 令牌不存在或已失效都视为登出成功：登出必须是幂等的，
        // 让"重复登出"返回错误只会给客户端制造无意义的失败分支。
    }

    /**
     * 撤销某用户全部会话，并推进令牌世代号。
     *
     * <p>两步缺一不可：只撤刷新令牌，攻击者手里的<b>访问令牌</b>仍能在剩余有效期内使用；
     * 只推进世代号，旧刷新令牌仍可在用户不知情时换出新会话。
     *
     * @param userId 用户 ID
     * @param action 审计动作码（登出全部设备 / 改密 / 泄露处置，语义不同但操作相同）
     */
    @Transactional
    public void revokeAllSessions(long userId, AuditAction action) {
        Instant now = clock.instant();
        int revoked = refreshTokenMapper.revokeAllActiveByUserId(userId, now);
        userMapper.incrementTokenVersion(userId);
        auditService.record(action, AuditResult.SUCCESS, userId, "USER", null,
                Map.of("revokedSessions", revoked));
    }

    /**
     * 列出某用户当前仍有效的会话。
     *
     * @param userId 用户 ID
     * @return 有效会话列表，按签发时间倒序
     */
    public List<RefreshTokenRecord> listActiveSessions(long userId) {
        return refreshTokenMapper.findActiveByUserId(userId, clock.instant());
    }

    /**
     * 处理"已被撤销的令牌再次出现"。
     *
     * @param token       已撤销的令牌
     * @param deviceLabel 本次请求上报的设备
     * @param now         当前时间
     * @return 本方法不会正常返回；宽限窗口内判定为并发刷新时抛"请使用最新令牌"，否则按泄露处置后抛异常
     */
    private IssuedTokens handleAlreadyRevoked(RefreshTokenRecord token, String deviceLabel, Instant now) {
        Optional<RefreshTokenRecord> successor = refreshTokenMapper.findLatestByRotatedFrom(token.id());

        boolean looksLikeConcurrentRefresh = successor.isPresent()
                && successor.get().revokedAt() == null
                && successor.get().expiresAt().isAfter(now)
                && token.revokedAt() != null
                && now.isBefore(token.revokedAt().plus(REPLAY_GRACE_WINDOW))
                && successor.get().device().equals(deviceLabel);

        if (looksLikeConcurrentRefresh) {
            // 正常的并发刷新：不判泄露、不撤销会话。客户端拿到这个提示后
            // 重新读取本地存储（另一个标签页已写入新令牌）再试即可。
            log.debug("刷新令牌在宽限窗口内被重复使用，判定为并发刷新 tokenId={}", token.id());
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态已被其他请求更新，请使用最新的登录凭据重试");
        }

        // 泄露处置交给独立事务执行：本方法随后的抛出会让外层事务回滚，
        // 处置动作若留在外层事务里就会连同回滚一起消失。理由详见 RefreshTokenLeakHandler。
        leakHandler.handleDetectedLeak(token.userId(), token.id());
        throw new BusinessException(ErrorCode.TOKEN_REVOKED,
                "检测到异常的登录凭据使用，已出于安全考虑登出全部设备，请重新登录");
    }
}
