package ai.camphub.identity.app;

import ai.camphub.identity.infrastructure.RefreshTokenMapper;
import ai.camphub.identity.infrastructure.UserMapper;
import ai.camphub.platform.audit.app.AuditService;
import ai.camphub.platform.audit.domain.AuditAction;
import ai.camphub.platform.audit.domain.AuditResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 刷新令牌泄露的处置动作。
 *
 * <h2>为什么必须单独成类、且必须在**独立事务**里执行</h2>
 * 这是本项目最容易写错、后果又最严重的一处事务语义，因此单独成类并把理由写在最前面。
 *
 * <p>泄露处置的调用位置很特殊：它在 {@link RefreshTokenService#rotate} 内部，
 * 而 {@code rotate} 是 {@code @Transactional} 的；处置做完之后，
 * 代码还要<b>抛出一个业务异常</b>去告诉客户端"请重新登录"。
 * Spring 的默认回滚规则是"遇到 {@code RuntimeException} 就回滚当前事务"，
 * 而 {@code BusinessException} 正是 {@code RuntimeException} 的子类。
 *
 * <p>把处置动作留在 {@code rotate} 的事务里，会发生这样一条静默的失效链：
 * <pre>
 *   撤销全部会话  ──┐
 *   世代号 +1     ──┤ 都在 TX1 里
 *   抛 BusinessException → TX1 回滚 → 上述两笔写入**全部消失**
 * </pre>
 * 结果是：客户端确实收到了"已登出全部设备"的提示，数据库里却什么都没变 ——
 * 攻击者手里的令牌继续有效。更糟的是审计日志（它是 {@code REQUIRES_NEW}，
 * 不受回滚影响）已经写下"检测到重放并处置成功"，事后排查会得到与事实相反的结论。
 *
 * <p>用 {@code REQUIRES_NEW} 让处置在独立事务中提交，异常随后回滚的只是外层事务，
 * 处置结果与审计一并留存。这与 {@code AuditService} 采取的是同一个思路：
 * <b>"必须留存的事实"不能挂在会回滚的事务上。</b>
 *
 * <p>之所以不复用 {@link RefreshTokenService#revokeAllSessions}：
 * 那是"用户主动要求"的语义（登出全部设备 / 改密），审计动作码是成功类；
 * 而这里是"服务端判定泄露后强制处置"，审计动作码是失败类安全事件。
 * 两者共用方法会让审计日志里分不清"用户自己登出的"和"我们发现泄露后强制登出的"。
 */
@Service
public class RefreshTokenLeakHandler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenLeakHandler.class);

    private final RefreshTokenMapper refreshTokenMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param refreshTokenMapper 刷新令牌 Mapper
     * @param userMapper         用户 Mapper（推进世代号）
     * @param auditService       审计服务
     * @param clock              时钟
     */
    public RefreshTokenLeakHandler(RefreshTokenMapper refreshTokenMapper,
                                   UserMapper userMapper,
                                   AuditService auditService,
                                   Clock clock) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * 按"已判定泄露"处置：撤销该用户全部会话，并推进令牌世代号。
     *
     * <p>两步缺一不可：只撤刷新令牌，攻击者手里的<b>访问令牌</b>仍能在剩余有效期内使用；
     * 只推进世代号，旧刷新令牌仍可在用户不知情时换出新会话。
     *
     * @param userId           用户 ID
     * @param detectedTokenId  触发判定的那条令牌 ID（写入审计，便于回溯是哪一条被重放）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleDetectedLeak(long userId, long detectedTokenId) {
        Instant now = clock.instant();
        int revoked = refreshTokenMapper.revokeAllActiveByUserId(userId, now);
        userMapper.incrementTokenVersion(userId);
        auditService.record(AuditAction.AUTH_TOKEN_REPLAY_DETECTED, AuditResult.FAILURE, userId,
                "SESSION", String.valueOf(detectedTokenId),
                Map.of("reason", "revoked_refresh_token_reused", "revokedSessions", revoked));
        log.warn("已按泄露处置：撤销全部会话并推进世代号 userId={} 撤销会话数={} 触发令牌={}",
                userId, revoked, detectedTokenId);
    }
}
