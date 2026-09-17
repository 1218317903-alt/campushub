package ai.camphub.identity.app;

import ai.camphub.identity.config.SecurityProperties;
import ai.camphub.identity.domain.DeviceLabel;
import ai.camphub.identity.domain.RefreshTokenRecord;
import ai.camphub.common.random.RandomValues;
import ai.camphub.identity.domain.TokenHasher;
import ai.camphub.identity.domain.User;
import ai.camphub.identity.infrastructure.RefreshTokenMapper;
import ai.camphub.identity.infrastructure.UserCredentialMapper;
import ai.camphub.identity.infrastructure.security.JwtTokenService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话的建立与令牌签发。
 *
 * <h2>为什么单独成类而不是塞进 AuthService</h2>
 * "签发一对令牌"这件事被<b>登录</b>、<b>刷新</b>、<b>改密后保留当前设备</b>三条路径复用。
 * 三处各自实现一遍的结果，是刷新令牌的 TTL、哈希方式、审计时机逐渐出现分叉 ——
 * 而这类分叉往往在很久之后才以"某个入口签发的令牌无法被撤销"的形式暴露。
 */
@Service
public class SessionService {

    private final RefreshTokenMapper refreshTokenMapper;
    private final UserCredentialMapper userCredentialMapper;
    private final JwtTokenService jwtTokenService;
    private final SecurityProperties securityProperties;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param refreshTokenMapper   刷新令牌 Mapper
     * @param userCredentialMapper 凭据 Mapper（登录成功时重置失败计数）
     * @param jwtTokenService      令牌服务
     * @param securityProperties   安全配置
     * @param clock                时钟
     */
    public SessionService(RefreshTokenMapper refreshTokenMapper,
                          UserCredentialMapper userCredentialMapper,
                          JwtTokenService jwtTokenService,
                          SecurityProperties securityProperties,
                          Clock clock) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.userCredentialMapper = userCredentialMapper;
        this.jwtTokenService = jwtTokenService;
        this.securityProperties = securityProperties;
        this.clock = clock;
    }

    /**
     * 为用户建立一次新会话。
     *
     * <p>顺带完成两件与"建立会话"语义一致的事：把登录失败计数清零、
     * 记录登录时间。放在这里而不是拆到调用方，是为了避免某个调用点漏做。
     *
     * <p><b>设备标识在这里统一规范化</b>：注册、登录、令牌轮换三条路径都经过本方法，
     * 因此只要在此处规范化，就不可能出现"某条路径不传 device 导致 NOT NULL 失败"。
     * 早期版本把规范化写在轮换路径上，结果"注册时不传 device"直接 500 ——
     * 这正是把同一件事写在两处会产生的典型缺陷。
     *
     * @param user       用户（必须是当前最新状态，携带最新 tokenVersion）
     * @param device     设备标识，可为 null（规范化为 {@code unknown}）
     * @param rotatedFrom 前驱令牌 ID；首次登录/新设备登录时为 null
     * @return 令牌对
     */
    @Transactional
    public IssuedTokens createSession(User user, String device, Long rotatedFrom) {
        Instant now = clock.instant();
        String rawRefreshToken = RandomValues.opaqueToken();

        userCredentialMapper.updateOnLoginSuccess(user.id(), now);
        refreshTokenMapper.insert(new RefreshTokenRecord(
                0L,
                user.id(),
                TokenHasher.sha256Hex(rawRefreshToken),
                DeviceLabel.normalize(device),
                now.plus(securityProperties.jwt().refreshTokenTtl()),
                null,
                rotatedFrom,
                null,
                now));

        String accessToken = jwtTokenService.issueAccessToken(user);
        return new IssuedTokens(accessToken, rawRefreshToken, jwtTokenService.accessTokenTtlSeconds());
    }
}
