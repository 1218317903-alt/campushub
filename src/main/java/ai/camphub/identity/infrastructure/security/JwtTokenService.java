package ai.camphub.identity.infrastructure.security;

import ai.camphub.identity.config.SecurityProperties;
import ai.camphub.identity.domain.User;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * 访问令牌的签发与解析。
 *
 * <h2>令牌里放什么、不放什么</h2>
 * <pre>
 *   iss  签发者          —— 防止别处签发的同密钥令牌被接受
 *   sub  用户的 public_id —— 对外标识，避免自增 id 进入任何客户端可见的数据
 *   uid  用户的内部 id    —— 鉴权时用它回查数据库（不再暴露给客户端使用）
 *   ver  令牌世代号        —— 与 user.token_version 比对，实现"立即撤销"
 *   iat / exp / jti      —— 签发时间 / 过期时间 / 令牌唯一标识
 * </pre>
 *
 * <p><b>刻意不放角色与权限</b>：令牌签发后无法收回，把权限写进令牌意味着
 * 撤销一个管理员的权限要等他的令牌自然过期。改为每次鉴权回库读取，
 * 权限变更立即生效 —— 代价与控制说明见 {@link ai.camphub.identity.domain.UserPrincipal}。
 *
 * <h2>为什么是 HS256 而不是 RS256</h2>
 * RS256 的价值在于"验证方拿不到签发密钥"（公私钥分离），适用于令牌由 A 服务签发、
 * 由 B/C 服务验证的场景。当前是单体应用，签发与验证在同一进程内，
 * 引入非对称密钥只增加密钥分发与轮换的复杂度，不带来实际安全收益。
 * 若将来 AI Runtime 等服务需要独立验证令牌，那一次演进会同时需要密钥分发机制，
 * 到那时再换 RS256 才是有依据的决定。
 */
@Component
public class JwtTokenService {

    /** 自定义声明名：用户内部 id。 */
    public static final String CLAIM_UID = "uid";

    /** 自定义声明名：令牌世代号。 */
    public static final String CLAIM_TOKEN_VERSION = "ver";

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final SecurityProperties securityProperties;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param jwtEncoder         编码器
     * @param jwtDecoder         解码与校验器
     * @param securityProperties 安全配置（取有效期与签发者）
     * @param clock              时钟。注入而非直接 {@code Instant.now()}：
     *                           签发时间与过期时间必须来自同一个时间源，
     *                           否则容器内的两个时钟（应用与数据库）会让令牌边界行为变得不可预测
     */
    public JwtTokenService(JwtEncoder jwtEncoder,
                           JwtDecoder jwtDecoder,
                           SecurityProperties securityProperties,
                           Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.securityProperties = securityProperties;
        this.clock = clock;
    }

    /**
     * 为一个用户签发访问令牌。
     *
     * @param user 用户
     * @return 已签名的 JWT 字符串
     */
    public String issueAccessToken(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(securityProperties.jwt().accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.jwt().issuer())
                .subject(user.publicId())
                .claim(CLAIM_UID, user.id())
                .claim(CLAIM_TOKEN_VERSION, user.tokenVersion())
                .issuedAt(now)
                .expiresAt(expiresAt)
                // jti 让"同一个用户在不同设备上的令牌"彼此可区分，
                // 也是将来做"精准撤销单个访问令牌"（黑名单）时需要的标识
                .id(UUID.randomUUID().toString())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * 解析并校验访问令牌。
     *
     * <p>校验内容包括：签名、{@code exp}/{@code nbf}、{@code iss}、以及必需声明的存在性。
     * 任何一项不通过都会抛出 {@code JwtException} 的子类，由调用方转成统一错误响应。
     *
     * <p><b>本方法不检查令牌是否已被撤销</b> —— 撤销依赖 {@code user.token_version}，
     * 只有查库才能知道，属于 {@link JwtAuthenticationFilter} 的职责。
     *
     * @param token 令牌字符串
     * @return 已解码的 JWT
     * @throws org.springframework.security.oauth2.jwt.JwtException 校验失败时
     */
    public Jwt decode(String token) {
        return jwtDecoder.decode(token);
    }

    /**
     * @return 访问令牌有效期（秒），用于在响应里告知客户端
     */
    public long accessTokenTtlSeconds() {
        return securityProperties.jwt().accessTokenTtl().toSeconds();
    }
}
