package ai.camphub.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 越权、密码变更、账号锁定与限流。
 *
 * <h2>本类测试的共同点</h2>
 * 它们都不验证"功能能不能用"，而是验证<b>"不能用的地方确实不能用"</b>：
 * 别人的会话下不了、别人的资料读不到、猜密码会被锁、刷接口会被限。
 * 这类断言在功能上线时永远不会有反馈 —— 只有攻击者会告诉你漏了哪一条，
 * 因此必须在交付前由测试来当这个"攻击者"。
 */
@DisplayName("身份 · 越权 / 密码 / 锁定 / 限流")
class AccountSecurityIT extends IdentityTestSupport {

    /** 满足密码策略、且与默认密码不同的新密码。 */
    private static final String NEW_PASSWORD = "maple-river-quiet-88";

    @Test
    @DisplayName("改密：其它设备立即失效，当前设备拿到新令牌，旧密码不再可登录")
    void changePassword_invalidatesOtherDevicesButKeepsCurrentOne() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens deviceA = registerOk(username, "dev-A", ip);
        Tokens deviceB = loginOk(username, DEFAULT_PASSWORD, "dev-B", ip);

        HttpResponse<String> changed = sendJson("POST", "/api/v1/users/me/password",
                toJson(body("currentPassword", DEFAULT_PASSWORD, "newPassword", NEW_PASSWORD, "device", "dev-A")),
                deviceA.access(), fromIp(ip));
        assertThat(changed.statusCode()).isEqualTo(200);
        Tokens deviceAAfter = tokensOf(changed);

        // 其它设备：访问令牌没过期也得失效 —— 否则"怀疑密码泄露后改密"挡不住已经登录的攻击者
        HttpResponse<String> otherDevice = getWithToken("/api/v1/users/me", deviceB.access(), fromIp(ip));
        assertThat(otherDevice.statusCode()).isEqualTo(401);
        assertThat(json(otherDevice).path("code").asInt()).isEqualTo(40104);

        // 当前设备的旧访问令牌同样被作废（世代号已推进）
        assertThat(getWithToken("/api/v1/users/me", deviceA.access(), fromIp(ip)).statusCode()).isEqualTo(401);

        // 但当前设备拿到了可用的新令牌：改密不该把正在操作的人自己也踢下线
        assertThat(getWithToken("/api/v1/users/me", deviceAAfter.access(), fromIp(ip)).statusCode())
                .isEqualTo(200);

        // 新密码可登录，旧密码不可
        assertThat(login(username, NEW_PASSWORD, "dev-C", ip).statusCode()).isEqualTo(200);
        HttpResponse<String> oldPassword = login(username, DEFAULT_PASSWORD, "dev-C", ip);
        assertThat(oldPassword.statusCode()).isEqualTo(401);
        assertThat(json(oldPassword).path("code").asInt()).isEqualTo(40103);

        long userId = userIdOf(username);
        assertThat(auditCount(userId, "AUTH_PASSWORD_CHANGE")).isEqualTo(1);
        assertThat(auditCount(userId, "AUTH_LOGOUT_ALL")).isZero();
    }

    @Test
    @DisplayName("改密：当前密码错误 / 新旧相同 / 新密码不合规，全部拒绝且不改动密码")
    void changePassword_rejectsInvalidRequests() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens tokens = registerOk(username, "dev-A", ip);
        long userId = userIdOf(username);
        String hashBefore = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM user_credential WHERE user_id = ?", String.class, userId);

        // 当前密码错误：要求出示它，否则"一次被窃取的访问令牌"就足以永久接管账号
        HttpResponse<String> wrongCurrent = sendJson("POST", "/api/v1/users/me/password",
                toJson(body("currentPassword", "definitely-not-it-2026", "newPassword", NEW_PASSWORD,
                        "device", "dev-A")),
                tokens.access(), fromIp(ip));
        assertThat(wrongCurrent.statusCode()).isEqualTo(400);
        assertThat(json(wrongCurrent).path("code").asInt()).isEqualTo(40001);

        // 新旧相同：换成一个"看起来改过了"的密码是最危险的假安全感
        HttpResponse<String> same = sendJson("POST", "/api/v1/users/me/password",
                toJson(body("currentPassword", DEFAULT_PASSWORD, "newPassword", DEFAULT_PASSWORD,
                        "device", "dev-A")),
                tokens.access(), fromIp(ip));
        assertThat(same.statusCode()).isEqualTo(400);
        assertThat(json(same).path("code").asInt()).isEqualTo(40001);

        // 新密码不满足策略
        HttpResponse<String> weak = sendJson("POST", "/api/v1/users/me/password",
                toJson(body("currentPassword", DEFAULT_PASSWORD, "newPassword", "password123",
                        "device", "dev-A")),
                tokens.access(), fromIp(ip));
        assertThat(weak.statusCode()).isEqualTo(400);
        assertThat(json(weak).path("code").asInt()).isEqualTo(40010);

        // 三次失败都不得改动密码，也不得撤销会话
        String hashAfter = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM user_credential WHERE user_id = ?", String.class, userId);
        assertThat(hashAfter).isEqualTo(hashBefore);
        assertThat(getWithToken("/api/v1/users/me", tokens.access(), fromIp(ip)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("设备列表：只列出自己的有效会话（已撤销的不出现）")
    void sessions_listOwnActiveDevicesOnly() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens deviceA = registerOk(username, "dev-A", ip);
        loginOk(username, DEFAULT_PASSWORD, "dev-B", ip);

        HttpResponse<String> response = getWithToken("/api/v1/users/me/sessions", deviceA.access(), fromIp(ip));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode sessions = json(response);
        assertThat(sessions.isArray()).isTrue();
        assertThat(sessions.size()).isEqualTo(2);
        assertThat(deviceNames(sessions)).containsExactlyInAnyOrder("dev-A", "dev-B");
    }

    @Test
    @DisplayName("下线别人的设备 → 404，且对方会话不受影响（不用 403 承认它存在）")
    void revokeSession_ofAnotherUser_returnsNotFoundAndDoesNotAffectVictim() throws Exception {
        String ip = nextIp();
        String victimName = uniqueUsername();
        Tokens victim = registerOk(victimName, "victim-device", ip);
        String attackerName = uniqueUsername();
        Tokens attacker = registerOk(attackerName, "attacker-device", ip);

        long victimSessionId = sessionIdOf(victimName);

        HttpResponse<String> response = deleteWithToken(
                "/api/v1/users/me/sessions/" + victimSessionId, attacker.access(), fromIp(ip));

        // 403 会等于承认"这个会话确实存在，只是不归你"，攻击者由此能确认 ID 是否有效
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).path("code").asInt()).isEqualTo(40400);

        // 受害者的会话必须完好 —— 越权请求连"误删"都不允许发生
        assertThat(refresh(victim.refresh(), "victim-device", ip).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("下线自己的设备：该会话从列表消失且刷新令牌失效")
    void revokeSession_ofOwnSession_removesIt() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        Tokens deviceA = registerOk(username, "dev-A", ip);
        loginOk(username, DEFAULT_PASSWORD, "dev-B", ip);

        long sessionA = sessionIdOf(username, "dev-A");
        assertThat(deleteWithToken("/api/v1/users/me/sessions/" + sessionA, deviceA.access(), fromIp(ip))
                .statusCode()).isEqualTo(204);

        JsonNode sessions = json(getWithToken("/api/v1/users/me/sessions", deviceA.access(), fromIp(ip)));
        assertThat(deviceNames(sessions)).containsExactly("dev-B");

        assertThat(refresh(deviceA.refresh(), "dev-A", ip).statusCode()).isEqualTo(401);
        assertThat(auditCount(userIdOf(username), "SESSION_REVOKE")).isEqualTo(1);
    }

    @Test
    @DisplayName("不存在「用别人的 ID 读资料」这条路由 → 404")
    void noEndpointAcceptsAnotherUsersIdentifier() throws Exception {
        String ip = nextIp();
        String victimName = uniqueUsername();
        registerOk(victimName, "victim-device", ip);

        Tokens attacker = registerOk(uniqueUsername(), "attacker-device", ip);
        long victimUserId = userIdOf(victimName);

        HttpResponse<String> response = getWithToken("/api/v1/users/" + victimUserId,
                attacker.access(), fromIp(ip));

        // 本模块的账号接口有意只提供 /users/me，路径里不出现"要操作哪个用户"这个参数。
        // 于是"把别人的 ID 填进去"这个攻击面在接口形状上就不存在 —— 少了这条路由，
        // 也就不依赖每个开发者都记得加归属判断。
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("连续登录失败 5 次：账号锁定，即使之后输对密码也拒绝")
    void accountLocks_afterRepeatedFailures() throws Exception {
        String username = uniqueUsername();
        String ip = nextIp();
        registerOk(username, "dev-A", ip);
        long userId = userIdOf(username);

        for (int i = 0; i < 5; i++) {
            HttpResponse<String> failure = login(username, "wrong-password-" + i, "dev-A", ip);
            assertThat(failure.statusCode()).isEqualTo(401);
            assertThat(json(failure).path("code").asInt()).isEqualTo(40103);
        }

        HttpResponse<String> locked = login(username, DEFAULT_PASSWORD, "dev-A", ip);

        // 口令校验通过之后才揭示"已锁定"：否则锁定状态本身就成了账号存在性探针
        assertThat(locked.statusCode()).isEqualTo(403);
        assertThat(json(locked).path("code").asInt()).isEqualTo(40301);

        Integer failCount = jdbcTemplate.queryForObject(
                "SELECT failed_login_count FROM user_credential WHERE user_id = ?", Integer.class, userId);
        assertThat(failCount).isEqualTo(5);
        Object lockedUntil = jdbcTemplate.queryForObject(
                "SELECT locked_until FROM user_credential WHERE user_id = ?", Object.class, userId);
        assertThat(lockedUntil).isNotNull();
        assertThat(auditCount(userId, "AUTH_LOGIN_FAILURE")).isEqualTo(6);
    }

    @Test
    @DisplayName("登录限流：超过每 IP 额度的请求 → 429 且带 Retry-After")
    void loginRateLimit_returns429WithRetryAfter() throws Exception {
        String ip = nextIp();
        String identifier = uniqueUsername();

        // 配置为每 IP 每分钟 10 次。前 10 次进入业务逻辑（返回 401），第 11 次被限流拦下。
        for (int i = 0; i < 10; i++) {
            assertThat(login(identifier, "irrelevant-password", "dev", ip).statusCode())
                    .as("第 %d 次请求不应被限流", i + 1)
                    .isEqualTo(401);
        }

        HttpResponse<String> limited = login(identifier, "irrelevant-password", "dev", ip);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(json(limited).path("code").asInt()).isEqualTo(42900);

        // 必须告诉调用方"多久之后可以再试"，否则客户端只能盲猜，
        // 而盲猜的结果通常是越试越密
        String retryAfter = limited.headers().firstValue("Retry-After").orElse(null);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isPositive();

        // 另一个来源 IP 不受影响 —— 限流是分桶的，不能让一个人把全站额度耗掉
        assertThat(login(identifier, "irrelevant-password", "dev", nextIp()).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 取设备名集合。
     *
     * @param sessions 会话数组
     * @return 设备名列表
     */
    private static List<String> deviceNames(JsonNode sessions) {
        return java.util.stream.StreamSupport.stream(sessions.spliterator(), false)
                .map(node -> node.path("device").asString())
                .toList();
    }

    /**
     * 取某用户的任意一条有效会话 ID。
     *
     * @param username 用户名
     * @return 会话 ID
     */
    private long sessionIdOf(String username) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL ORDER BY id LIMIT 1",
                Long.class, userIdOf(username));
        if (id == null) {
            throw new AssertionError("用户 " + username + " 没有有效会话");
        }
        return id;
    }

    /**
     * 取某用户指定设备的会话 ID。
     *
     * @param username 用户名
     * @param device   设备标识
     * @return 会话 ID
     */
    private long sessionIdOf(String username, String device) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM refresh_token WHERE user_id = ? AND device = ? AND revoked_at IS NULL "
                        + "ORDER BY id LIMIT 1", Long.class, userIdOf(username), device);
        if (id == null) {
            throw new AssertionError("用户 " + username + " 没有设备 " + device + " 的会话");
        }
        return id;
    }
}
