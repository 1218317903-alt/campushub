package ai.camphub.identity.app;

import ai.camphub.identity.infrastructure.UserCredentialMapper;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录失败记账。
 *
 * <h2>为什么必须是独立事务</h2>
 * 调用它之后，登录流程<b>一定会抛异常</b>（返回 401）。如果记账与登录处于同一个事务，
 * 异常会触发回滚，失败次数永远停在 0 —— 于是"连续失败 5 次锁定"这个策略
 * 在生产上永远不会生效，而单元测试若只测"抛不抛异常"也发现不了。
 * 因此这里用 {@link Propagation#REQUIRES_NEW}，让计数独立提交。
 *
 * <h2>为什么单独成类而不是 AuthService 的一个方法</h2>
 * Spring 的事务是基于代理的：<b>同一个类内部的方法自调用不会走代理</b>，
 * {@code @Transactional} 会被静默忽略。把它拆成独立 Bean 是让这个注解真正生效的前提 ——
 * 这个坑不会报错，只会让事务边界悄悄失效，因此值得用类结构来避免。
 */
@Service
public class LoginAttemptService {

    private final UserCredentialMapper userCredentialMapper;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param userCredentialMapper 凭据 Mapper
     * @param clock                时钟
     */
    public LoginAttemptService(UserCredentialMapper userCredentialMapper, Clock clock) {
        this.userCredentialMapper = userCredentialMapper;
        this.clock = clock;
    }

    /**
     * 记录一次登录失败，并在达到阈值时写入临时锁定。
     *
     * <p>计数与阈值判定在一条 SQL 内完成（见 {@code UserCredentialMapper.xml}），
     * 因此并发失败不会互相覆盖。
     *
     * @param userId          用户 ID
     * @param maxFailures     触发锁定的失败次数
     * @param lockDuration    锁定时长
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(long userId, int maxFailures, Duration lockDuration) {
        userCredentialMapper.updateOnLoginFailure(userId, maxFailures, clock.instant().plus(lockDuration));
    }
}
