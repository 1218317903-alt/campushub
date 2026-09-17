package ai.camphub.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ai.camphub.identity.config.SecurityProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import tools.jackson.databind.JsonNode;

/**
 * 访问令牌与刷新令牌的生命周期：轮换、重放、撤销、过期。
 *
 * <h2>为什么这些用例值得单独存在</h2>
 * 身份系统里最贵的一类缺陷是"令牌该失效的时候没失效"，而它在功能上完全看不出来：
 * 所有正向流程都正常，只有攻击者知道哪里能进。因此本类的断言大多<b>不是</b>
 * "接口返回了什么"，而是<b>"事后那个令牌还能不能用"</b>。
 *
 * <h2>自己签发令牌的用途</h2>
 * 有几条分支（过期、缺少声明、账号不存在）无法通过正常流程触发 ——
 * 正常签发的令牌 15 分钟才过期，等不起。因此这里用<b>同样密钥、同样签发者</b>
 * 自造令牌，精准命中校验链的每一环。用同一密钥而不是随便一个，
 * 是为了确保失败原因就是被测的那一环，而不是被"签名不符"提前拦掉。
 */
@DisplayName("身份 · 令牌生命周期（轮换 / 重放 / 撤销 / 过期）")
class TokenLifecycleIT extends IdentityTestSupport {

    /** 由容器提供，用于取测试环境的签名密钥与签发者。 */
    @Autowired
    private SecurityProperties securityProperties;

    @Test
    @DisplayName("账号不存在与密码错误：状态码、错误码、文案完全一致（防账号枚举）")
    void login_unknownAccountAndWrongPassword_areIndistinguishable() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        register(username, emailOf(username), DEFAULT_PASSWORD, null, ip);

        HttpResponse<String> unknown = login(uniqueUsername(), DEFAULT_PASSWORD, "dev", ip);
        HttpResponse<String> wrongPassword = login(username, DEFAULT_PASSWORD + "-nope", "dev", ip);

        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(wrongPassword.statusCode()).isEqualTo(401);

        JsonNode unknownBody = json(unknown);
        JsonNode wrongBody = json(wrongPassword);

        // 三处都必须一致。任何一处不同，接口就变成了账号枚举器：
        // 拿一份邮箱列表逐条试，就能筛出平台上注册了哪些账号。
        assertThat(unknownBody.path("code").asInt()).isEqualTo(wrongBody.path("code").asInt());
        assertThat(unknownBody.path("code").asInt()).isEqualTo(40103);
        assertThat(unknownBody.path("message").asString()).isEqualTo(wrongBody.path("message").asString());

        // 两者都应留下登录失败审计（一次是 unknown_identifier，一次是 bad_password）
        assertThat(auditCount(userIdOf(username), "AUTH_LOGIN_FAILURE")).isEqualTo(1);
    }

    @Test
    @DisplayName("刷新即轮换：旧刷新令牌立刻失效，无法再换取会话")
    void refresh_rotatesAndInvalidatesOldToken() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens first = registerOk(username, "dev-A", ip);

        HttpResponse<String> rotated = refresh(first.refresh(), "dev-A", ip);
        assertThat(rotated.statusCode()).isEqualTo(200);
        Tokens second = tokensOf(rotated);
        assertThat(second.refresh()).isNotEqualTo(first.refresh());

        // 旧令牌已撤销。这里刻意用**同一个设备**，但先让时间越过宽限窗口之外的判定条件 ——
        // 实际上同设备会走"并发刷新"分支，因此本条断言的是"旧令牌不能再换出新会话"，
        // 而"泄露处置"的完整行为由下一条用例专门覆盖。
        HttpResponse<String> reused = refresh(first.refresh(), "dev-A", ip);
        assertThat(reused.statusCode()).isEqualTo(401);
        assertThat(json(reused).path("code").asInt()).isIn(40102, 40104);

        // 轮换出的新令牌确实是可用的（证明"轮换"不是"把会话也一起废掉"）
        assertThat(refresh(second.refresh(), "dev-A", ip).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("刷新令牌重放（换设备使用）→ 判定泄露：撤销全部会话并使访问令牌立即失效")
    void refresh_replayFromAnotherDevice_revokesEverythingAndPersists() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens first = registerOk(username, "dev-A", ip);

        Tokens rotated = tokensOf(refresh(first.refresh(), "dev-A", ip));

        // 同一个刷新令牌从**另一台设备**再次使用：正常的并发刷新不会跨设备，
        // 因此这是"令牌已泄露到原设备之外"的信号
        HttpResponse<String> replay = refresh(first.refresh(), "dev-B", ip);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(json(replay).path("code").asInt()).isEqualTo(40104);

        long userId = userIdOf(username);

        // 关键断言一：轮换出来的那个访问令牌**现在必须已经失效**。
        // 这一条验证的是泄露处置真的落库了 —— 处置动作若和抛异常处在同一个事务里，
        // 就会随回滚一起消失：客户端收到"已登出全部设备"，数据库却什么都没变。
        HttpResponse<String> afterLeak = getWithToken("/api/v1/users/me", rotated.access(), fromIp(ip));
        assertThat(afterLeak.statusCode()).isEqualTo(401);
        assertThat(json(afterLeak).path("code").asInt()).isEqualTo(40104);

        // 关键断言二：世代号确实被推进了（这是"访问令牌立即失效"的机制本身）
        Integer tokenVersion = jdbcTemplate.queryForObject(
                "SELECT token_version FROM `user` WHERE id = ?", Integer.class, userId);
        assertThat(tokenVersion).isEqualTo(2);

        // 关键断言三：该用户名下不再有任何有效刷新令牌
        Integer activeSessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
        assertThat(activeSessions).isZero();

        // 关键断言四：留下可被运维检索的安全事件
        assertThat(auditCount(userId, "AUTH_TOKEN_REPLAY_DETECTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("宽限窗口内同设备重复刷新 → 不判泄露，不触发「登出全部设备」")
    void refresh_withinGraceWindowOnSameDevice_isNotTreatedAsLeak() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens first = registerOk(username, "dev-A", ip);

        Tokens rotated = tokensOf(refresh(first.refresh(), "dev-A", ip));

        // 模拟"两个标签页同时到期、同时用同一个刷新令牌去换新令牌"
        HttpResponse<String> concurrent = refresh(first.refresh(), "dev-A", ip);
        assertThat(concurrent.statusCode()).isEqualTo(401);
        assertThat(json(concurrent).path("code").asInt()).isEqualTo(40102);
        assertThat(json(concurrent).path("message").asString()).contains("最新");

        long userId = userIdOf(username);

        // 不能误伤：世代号不变，刚换来的访问令牌依然可用
        Integer tokenVersion = jdbcTemplate.queryForObject(
                "SELECT token_version FROM `user` WHERE id = ?", Integer.class, userId);
        assertThat(tokenVersion).isEqualTo(1);
        assertThat(getWithToken("/api/v1/users/me", rotated.access(), fromIp(ip)).statusCode()).isEqualTo(200);
        assertThat(auditCount(userId, "AUTH_TOKEN_REPLAY_DETECTED")).isZero();
    }

    @Test
    @DisplayName("刷新令牌不存在（伪造或已被清理）→ 401/40102，不泄漏该令牌是否存在")
    void refresh_withUnknownToken_returnsTokenInvalid() throws Exception {
        HttpResponse<String> response = refresh("not-a-real-refresh-token", "dev", nextIp());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).path("code").asInt()).isEqualTo(40102);
    }

    @Test
    @DisplayName("登出：撤销刷新令牌、写审计、且幂等")
    void logout_revokesRefreshTokenIsIdempotentAndAudited() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens tokens = registerOk(username, "dev-A", ip);
        long userId = userIdOf(username);

        assertThat(logout(tokens.refresh(), ip).statusCode()).isEqualTo(204);
        assertThat(logout(tokens.refresh(), ip).statusCode()).isEqualTo(204);

        // 登出后刷新令牌不能再换新会话
        HttpResponse<String> afterLogout = refresh(tokens.refresh(), "dev-A", ip);
        assertThat(afterLogout.statusCode()).isEqualTo(401);
        assertThat(json(afterLogout).path("code").asInt()).isEqualTo(40104);

        // 审计只记一次"真的撤销了一个会话"；重复登出是幂等命中，不重复记账
        assertThat(auditCount(userId, "AUTH_LOGOUT")).isEqualTo(1);
    }

    @Test
    @DisplayName("登出全部设备：已签发但未过期的访问令牌立即失效")
    void logoutAll_invalidatesAccessTokenImmediately() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens tokens = registerOk(username, "dev-A", ip);
        long userId = userIdOf(username);

        // 先确认这个访问令牌本来是好的
        assertThat(getWithToken("/api/v1/users/me", tokens.access(), fromIp(ip)).statusCode()).isEqualTo(200);

        HttpResponse<String> logoutAll = sendJson("POST", "/api/v1/auth/logout-all", null,
                tokens.access(), fromIp(ip));
        assertThat(logoutAll.statusCode()).isEqualTo(204);

        // 这正是"无状态 JWT 也能立即撤销"的验证点：令牌没过期，但世代号已经不被接受
        HttpResponse<String> after = getWithToken("/api/v1/users/me", tokens.access(), fromIp(ip));
        assertThat(after.statusCode()).isEqualTo(401);
        assertThat(json(after).path("code").asInt()).isEqualTo(40104);

        Integer tokenVersion = jdbcTemplate.queryForObject(
                "SELECT token_version FROM `user` WHERE id = ?", Integer.class, userId);
        assertThat(tokenVersion).isEqualTo(2);
    }

    @Test
    @DisplayName("已过期的访问令牌 → 401/40101（与「无效」分开，客户端才知道该去刷新）")
    void expiredAccessToken_reportsTokenExpired() throws Exception {
        String username = uniqueUsername();
        Tokens tokens = registerOk(username, "dev-A", nextIp());
        long userId = userIdOf(username);

        String expired = signToken(userId, 1, Instant.now().minusSeconds(3600));

        HttpResponse<String> response = getWithToken("/api/v1/users/me", expired, fromIp(nextIp()));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).path("code").asInt()).isEqualTo(40101);
    }

    @Test
    @DisplayName("访问令牌缺少 ver 声明 → 401/40102（显式拒绝，而不是被当成世代号不匹配）")
    void accessTokenWithoutVersionClaim_isRejected() throws Exception {
        String username = uniqueUsername();
        Tokens tokens = registerOk(username, "dev-A", nextIp());
        long userId = userIdOf(username);

        String claims = signTokenWithoutVersion(userId);

        HttpResponse<String> response = getWithToken("/api/v1/users/me", claims, fromIp(nextIp()));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).path("code").asInt()).isEqualTo(40102);
    }

    @Test
    @DisplayName("签名有效但账号已不存在 → 401/40104（签名只证明令牌是我们签的，不证明账号还在）")
    void accessTokenForNonexistentUser_isRejected() throws Exception {
        String forged = signToken(999_999_999L, 1, Instant.now().plusSeconds(600));

        HttpResponse<String> response = getWithToken("/api/v1/users/me", forged, fromIp(nextIp()));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).path("code").asInt()).isEqualTo(40104);
    }

    @Test
    @DisplayName("账号被停用 → 403/40301（令牌没错，是这个身份不该被放行）")
    void disabledAccount_isRejectedEvenWithValidToken() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens tokens = registerOk(username, "dev-A", ip);
        long userId = userIdOf(username);

        jdbcTemplate.update("UPDATE `user` SET status = 'DISABLED' WHERE id = ?", userId);

        HttpResponse<String> response = getWithToken("/api/v1/users/me", tokens.access(), fromIp(ip));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json(response).path("code").asInt()).isEqualTo(40301);
    }

    // ------------------------------------------------------------------
    // 自造令牌：同密钥、同签发者，只改动被测的那一项
    // ------------------------------------------------------------------

    /**
     * 用测试环境的密钥与签发者签一个访问令牌。
     *
     * @param userId       uid 声明
     * @param tokenVersion ver 声明
     * @param expiresAt    过期时间
     * @return JWT
     */
    private String signToken(long userId, int tokenVersion, Instant expiresAt) {
        return encode(JwtClaimsSet.builder()
                .issuer(securityProperties.jwt().issuer())
                .subject("self-signed")
                .issuedAt(expiresAt.minusSeconds(600))
                .expiresAt(expiresAt)
                .claim("uid", userId)
                .claim("ver", tokenVersion)
                .build());
    }

    /**
     * 签一个刻意缺少 {@code ver} 声明的访问令牌。
     *
     * @param userId uid 声明
     * @return JWT
     */
    private String signTokenWithoutVersion(long userId) {
        return encode(JwtClaimsSet.builder()
                .issuer(securityProperties.jwt().issuer())
                .subject("self-signed")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("uid", userId)
                .build());
    }

    /**
     * 用测试密钥编码声明集。
     *
     * @param claims 声明
     * @return JWT
     */
    private String encode(JwtClaimsSet claims) {
        SecretKey key = new SecretKeySpec(
                securityProperties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
