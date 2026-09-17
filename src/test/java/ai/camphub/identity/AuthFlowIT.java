package ai.camphub.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 身份认证：注册 / 登录 / 自助资料。
 *
 * <h2>本类覆盖什么</h2>
 * 正向链路（能注册、能登录、能读到自己）与<b>契约边界</b>
 * （重复注册被拒、弱密码被拒、非法用户名被拒、响应不泄漏不该给的字段）。
 *
 * <h2>本类不覆盖什么</h2>
 * 令牌生命周期（轮换、重放、过期、撤销）在 {@link TokenLifecycleIT}；
 * 越权、锁定、限流在 {@link AccountSecurityIT}。三类分开是为了让失败信息本身就能
 * 指出问题出在哪一层 —— 一个 200 行的测试类里挂掉一条时，定位成本要高得多。
 */
@DisplayName("身份 · 注册 / 登录 / 资料（真实 HTTP + 真实 MySQL）")
class AuthFlowIT extends IdentityTestSupport {

    @Test
    @DisplayName("注册成功：返回令牌对，库里只落密码哈希与刷新令牌哈希")
    void register_createsAccountAndReturnsTokenPair() throws Exception {
        String username = uniqueUsername();
        String email = emailOf(username);
        String ip = nextIp();

        HttpResponse<String> response = register(username, email, DEFAULT_PASSWORD, "Chrome on macOS", ip);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(body.path("accessToken").asString("")).isNotBlank();
        assertThat(body.path("refreshToken").asString("")).isNotBlank();
        assertThat(body.path("tokenType").asString("")).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(900L);

        // 响应体里不得出现明文密码
        assertThat(response.body()).doesNotContain(DEFAULT_PASSWORD);

        long userId = userIdOf(username);

        // 1) 密码：BCrypt cost=12 的产物（$2a$12$ 前缀），绝不是明文，也不是任何可逆编码
        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM user_credential WHERE user_id = ?", String.class, userId);
        assertThat(passwordHash).isNotNull();
        assertThat(passwordHash).startsWith("$2");
        assertThat(passwordHash).contains("$12$");
        assertThat(passwordHash).doesNotContain(DEFAULT_PASSWORD);

        // 2) 刷新令牌：库里存的是 SHA-256，不是原文。
        //    期望值在测试中独立算一遍（不复用生产的 TokenHasher）——
        //    否则 TokenHasher 自己写错时测试会跟着一起错，等于没测。
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM refresh_token WHERE user_id = ?", String.class, userId);
        String rawRefreshToken = body.path("refreshToken").asString();
        assertThat(storedHash).isNotEqualTo(rawRefreshToken);
        assertThat(storedHash).isEqualTo(sha256Hex(rawRefreshToken));
        assertThat(storedHash).hasSize(64);

        // 3) 自增主键不外泄：public_id 是随机 22 位
        String publicId = jdbcTemplate.queryForObject(
                "SELECT public_id FROM `user` WHERE id = ?", String.class, userId);
        assertThat(publicId).hasSize(22);

        // 4) 默认角色与审计
        Integer roleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id = ur.role_id "
                        + "WHERE ur.user_id = ? AND r.code = 'USER'", Integer.class, userId);
        assertThat(roleCount).isEqualTo(1);
        assertThat(auditCount(userId, "AUTH_REGISTER")).isEqualTo(1);
    }

    @Test
    @DisplayName("重复用户名 → 409；大小写不同也算重复（排序规则 utf8mb4_0900_ai_ci）")
    void register_rejectsDuplicateUsernameIncludingDifferentCase() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();

        assertThat(register(username, emailOf(username), DEFAULT_PASSWORD, null, ip).statusCode())
                .isEqualTo(201);

        // 同一用户名、不同邮箱
        HttpResponse<String> sameCase = register(username, uniqueUsername() + "@example.test",
                DEFAULT_PASSWORD, null, ip);
        assertThat(sameCase.statusCode()).isEqualTo(409);
        assertThat(json(sameCase).path("code").asInt()).isEqualTo(40900);

        // 大小写不同 —— 若排序规则是区分大小写的，这里会变成 201，
        // 于是 "Adm1n" 可以冒充 "adm1n"
        HttpResponse<String> differentCase = register(username.toUpperCase(), uniqueUsername() + "@example.test",
                DEFAULT_PASSWORD, null, ip);
        assertThat(differentCase.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("弱密码 / 超长密码 / 与账号相关的密码 → 400 且错误码为密码策略码")
    void register_rejectsWeakPasswords() throws Exception {
        String ip = nextIp();

        // 太短（策略要求 ≥ 10）
        HttpResponse<String> tooShort = register(uniqueUsername(), uniqueUsername() + "@example.test",
                "abc12345", null, ip);
        assertThat(tooShort.statusCode()).isEqualTo(400);
        assertThat(json(tooShort).path("code").asInt()).isEqualTo(40010);

        // 常见弱密码（弱密码表内置）
        HttpResponse<String> common = register(uniqueUsername(), uniqueUsername() + "@example.test",
                "password123", null, ip);
        assertThat(common.statusCode()).isEqualTo(400);
        assertThat(json(common).path("code").asInt()).isEqualTo(40010);

        // 超过 BCrypt 的 72 字节上限：必须拒绝而不是静默截断
        HttpResponse<String> tooLong = register(uniqueUsername(), uniqueUsername() + "@example.test",
                "zq7".repeat(30), null, ip);
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(json(tooLong).path("code").asInt()).isEqualTo(40010);

        // 密码里出现自己的用户名
        String username = uniqueUsername();
        HttpResponse<String> related = register(username, emailOf(username),
                username + "-suffix-2026", null, ip);
        assertThat(related.statusCode()).isEqualTo(400);
        assertThat(json(related).path("code").asInt()).isEqualTo(40010);
    }

    @Test
    @DisplayName("用户名含 @ → 400 参数校验失败（@ 是区分用户名与邮箱的判据）")
    void register_rejectsUsernameContainingAtSign() throws Exception {
        HttpResponse<String> response = register("bad@name", "bad@example.test", DEFAULT_PASSWORD, null, nextIp());

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = json(response);
        assertThat(body.path("code").asInt()).isEqualTo(40000);
        assertThat(body.path("details").isArray()).isTrue();
        assertThat(body.path("details").toString()).contains("username");
    }

    @Test
    @DisplayName("登录：用户名与邮箱两种标识都可用，且用户名不区分大小写")
    void login_acceptsUsernameOrEmailCaseInsensitively() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        register(username, emailOf(username), DEFAULT_PASSWORD, null, ip);

        assertThat(login(username, DEFAULT_PASSWORD, "dev-1", ip).statusCode()).isEqualTo(200);
        assertThat(login(emailOf(username), DEFAULT_PASSWORD, "dev-1", ip).statusCode()).isEqualTo(200);
        assertThat(login(username.toUpperCase(), DEFAULT_PASSWORD, "dev-1", ip).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET /users/me：返回本人资料，且不含邮箱、状态、内部 ID 与世代号")
    void me_returnsOwnProfileWithoutLeakingPersonalFields() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens tokens = registerOk(username, "Chrome on macOS", ip);

        HttpResponse<String> response = getWithToken("/api/v1/users/me", tokens.access(), fromIp(ip));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("username").asString()).isEqualTo(username);
        assertThat(body.path("nickname").asString()).isEqualTo(username);
        assertThat(body.path("publicId").asString()).hasSize(22);

        // 通用资料响应刻意不放这些字段：邮箱属个人信息、状态对本人无意义、
        // 自增 ID 与世代号属于内部实现
        assertThat(body.has("email")).isFalse();
        assertThat(body.has("id")).isFalse();
        assertThat(body.has("status")).isFalse();
        assertThat(body.has("tokenVersion")).isFalse();
        assertThat(body.has("deletedAt")).isFalse();
        // 响应体里绝不能出现密码哈希
        assertThat(response.body()).doesNotContain("$2a$");
    }

    @Test
    @DisplayName("未带令牌访问受保护接口 → 401/40100，并给出 WWW-Authenticate")
    void me_withoutToken_returnsUnauthenticated() throws Exception {
        HttpResponse<String> response = get("/api/v1/users/me");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).path("code").asInt()).isEqualTo(40100);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
        // 受保护接口的 401 必须走统一错误契约，而不是容器的空白 401
        assertThat(json(response).path("traceId").asString("")).isNotBlank();
    }

    @Test
    @DisplayName("令牌被篡改（签名不符）→ 401/40102")
    void me_withTamperedToken_returnsTokenInvalid() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens tokens = registerOk(username, "dev", ip);

        // 只动签名段的最后一个字符：这是最"像真"的篡改方式
        String tampered = tokens.access().substring(0, tokens.access().length() - 1)
                + (tokens.access().endsWith("A") ? "B" : "A");

        HttpResponse<String> response = getWithToken("/api/v1/users/me", tampered, fromIp(ip));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).path("code").asInt()).isEqualTo(40102);
    }

    @Test
    @DisplayName("修改资料：可持久化，且头像地址拒绝 javascript: 伪协议")
    void updateProfile_persistsAndRejectsUnsafeAvatarUrl() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens tokens = registerOk(username, "dev", ip);

        HttpResponse<String> updated = sendJson("PATCH", "/api/v1/users/me",
                toJson(body("nickname", "小海", "avatarUrl", "https://cdn.example.test/a.png", "bio", "在读学生")),
                tokens.access(), fromIp(ip));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json(updated).path("nickname").asString()).isEqualTo("小海");

        // 再读一次，确认真的落库了（而不是只在响应里改了）
        JsonNode reread = json(getWithToken("/api/v1/users/me", tokens.access(), fromIp(ip)));
        assertThat(reread.path("nickname").asString()).isEqualTo("小海");
        assertThat(reread.path("avatarUrl").asString()).isEqualTo("https://cdn.example.test/a.png");
        assertThat(reread.path("bio").asString()).isEqualTo("在读学生");

        // javascript: 头像地址会在展示端变成脚本执行入口，服务端必须自己拦
        HttpResponse<String> unsafe = sendJson("PATCH", "/api/v1/users/me",
                toJson(body("nickname", "小海", "avatarUrl", "javascript:alert(1)", "bio", null)),
                tokens.access(), fromIp(ip));
        assertThat(unsafe.statusCode()).isEqualTo(400);
        assertThat(json(unsafe).path("code").asInt()).isEqualTo(40000);
    }

    @Test
    @DisplayName("修改资料：昵称为空 → 400")
    void updateProfile_rejectsBlankNickname() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens tokens = registerOk(username, "dev", ip);

        HttpResponse<String> response = sendJson("PATCH", "/api/v1/users/me",
                toJson(body("nickname", "   ", "avatarUrl", null, "bio", null)),
                tokens.access(), fromIp(ip));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asInt()).isEqualTo(40000);
    }

    @Test
    @DisplayName("登出接口在访问令牌缺失时仍可调用（登出只依赖刷新令牌）")
    void logout_doesNotRequireAccessToken() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens tokens = registerOk(username, "dev", ip);

        // 不带 Authorization 头直接登出 —— 这正是"访问令牌刚过期、想登出"的场景。
        // 若该接口要求访问令牌有效，用户在这种状态下唯一能做的就是放任会话继续有效。
        HttpResponse<String> response = logout(tokens.refresh(), ip);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("注册时不传 device 也能成功：可选字段不该变成 500，会话设备显示为 unknown")
    void register_withoutDevice_succeedsAndFallsBackToUnknownDevice() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();

        // device 在接口上是可选的（@Size(max=64)，允许 null），而 refresh_token.device
        // 是 NOT NULL。两者之间的补齐必须由服务端负责 —— 否则一个"完全合法的用户操作"
        // 会以数据库约束冲突的形式变成 500。
        HttpResponse<String> response = register(username, emailOf(username), DEFAULT_PASSWORD, null, ip);
        assertThat(response.statusCode()).isEqualTo(201);

        Tokens tokens = tokensOf(response);
        JsonNode sessions = json(getWithToken("/api/v1/users/me/sessions", tokens.access(), fromIp(ip)));

        assertThat(sessions.size()).isEqualTo(1);
        assertThat(sessions.get(0).path("device").asString()).isEqualTo("unknown");
    }

    /**
     * 独立计算 SHA-256 十六进制。
     *
     * @param value 原始值
     * @return 小写十六进制
     */
    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}
