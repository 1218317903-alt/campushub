package ai.camphub.identity;

import ai.camphub.support.AbstractIntegrationTest;
import java.io.IOException;
import java.net.http.HttpResponse;

/**
 * 身份模块集成测试的夹具：认证接口的调用封装 + 身份相关表的断言辅助。
 *
 * <h2>与基类的分工</h2>
 * "怎么发一个 JSON 请求""怎么注册一个能用的测试账号"已上提到
 * {@link AbstractIntegrationTest} —— 因为 Phase 02 之后所有受保护接口都需要令牌，
 * 那是全项目共用的测试基础设施，不是身份模块专有。
 *
 * <p>本类只保留<b>身份专有</b>的两类东西：
 * <ol>
 *   <li>登录 / 刷新 / 登出这三个接口的调用封装（只有身份模块的测试会直接调它们）；</li>
 *   <li>对 {@code user} / {@code audit_log} 等表的断言辅助
 *       —— 用于验证"库里到底存了什么"，这是接口断言覆盖不到的一层。</li>
 * </ol>
 */
abstract class IdentityTestSupport extends AbstractIntegrationTest {

    /**
     * 调用登录接口。
     *
     * @param identifier 用户名或邮箱
     * @param password   密码
     * @param device     设备标识，可为 null
     * @param ip         来源 IP
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> login(String identifier, String password, String device, String ip)
            throws IOException, InterruptedException {
        return sendJson("POST", "/api/v1/auth/login",
                toJson(body("identifier", identifier, "password", password, "device", device)),
                null, fromIp(ip));
    }

    /**
     * 登录并断言成功，返回令牌对。
     *
     * @param identifier 用户名或邮箱
     * @param password   密码
     * @param device     设备标识
     * @param ip         来源 IP
     * @return 令牌对
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected Tokens loginOk(String identifier, String password, String device, String ip)
            throws IOException, InterruptedException {
        HttpResponse<String> response = login(identifier, password, device, ip);
        if (response.statusCode() != 200) {
            throw new AssertionError("登录应返回 200，实际 " + response.statusCode() + "：" + response.body());
        }
        return tokensOf(response);
    }

    /**
     * 调用刷新令牌接口。
     *
     * @param refreshToken 刷新令牌
     * @param device       设备标识
     * @param ip           来源 IP
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> refresh(String refreshToken, String device, String ip)
            throws IOException, InterruptedException {
        return sendJson("POST", "/api/v1/auth/refresh",
                toJson(body("refreshToken", refreshToken, "device", device)),
                null, fromIp(ip));
    }

    /**
     * 调用登出接口。
     *
     * @param refreshToken 刷新令牌
     * @param ip           来源 IP
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> logout(String refreshToken, String ip)
            throws IOException, InterruptedException {
        return sendJson("POST", "/api/v1/auth/logout",
                toJson(body("refreshToken", refreshToken)), null, fromIp(ip));
    }

    /**
     * 按登录名取用户自增 ID。
     *
     * @param username 登录名
     * @return 用户 ID
     */
    protected long userIdOf(String username) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", Long.class, username);
        if (id == null) {
            throw new AssertionError("数据库中不存在登录名：" + username);
        }
        return id;
    }

    /**
     * 取某用户的令牌世代号。
     *
     * @param userId 用户 ID
     * @return 世代号
     */
    protected int tokenVersionOf(long userId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT token_version FROM `user` WHERE id = ?", Integer.class, userId);
        return version == null ? 0 : version;
    }

    /**
     * 统计某个用户的某类审计记录条数。
     *
     * @param userId 用户 ID
     * @param action 动作码
     * @return 条数
     */
    protected int auditCount(long userId, String action) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE actor_user_id = ? AND action = ?",
                Integer.class, userId, action);
        return count == null ? 0 : count;
    }

    /**
     * 统计某个用户仍有效的刷新令牌数。
     *
     * @param userId 用户 ID
     * @return 条数
     */
    protected int activeSessionCount(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
        return count == null ? 0 : count;
    }
}
