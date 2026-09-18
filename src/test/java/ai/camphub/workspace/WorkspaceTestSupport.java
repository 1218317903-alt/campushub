package ai.camphub.workspace;

import ai.camphub.support.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * 空间模块集成测试的夹具：把"建一个空间、拉一个人进来、放一篇笔记"这些
 * 多步准备动作各收成一个方法。
 *
 * <h2>为什么这些准备动作值得被收起来</h2>
 * 本模块的越权测试需要反复构造"两个用户 + 一个私有空间 + 里面有一点内容"这个场景，
 * 而它最少需要 4 次以上的 HTTP 调用（注册两个人、建空间、邀请、兑换、写内容）。
 * 若每个用例各写一遍，测试主体的篇幅会被准备工作淹没 ——
 * 而越权测试的<b>可读性</b>恰恰是它的价值所在：读的人要能一眼看出
 * "这一步是攻击者在尝试什么"。
 *
 * <h2>它不隐藏任何断言</h2>
 * 这里所有方法都只做"发起请求并返回结果"，不断言状态码（除了少数
 * "这一步必须成功否则后面的步骤没有意义"的准备方法）。
 * 把断言藏在夹具里，会让测试读起来像是通过了，而实际什么都没验证。
 */
abstract class WorkspaceTestSupport extends AbstractIntegrationTest {

    /** 上传用的 multipart 边界。固定值即可：每次请求只有一个部分。 */
    private static final String BOUNDARY = "CamphubTestBoundary9f2a";

    /** 上传/下载用独立的客户端：下载要拿字节而不是字符串。 */
    private static final HttpClient BYTE_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 空间名唯一化序号，避免"同名空间"在断言里无法区分。 */
    private static final AtomicInteger NAME_SEQ = new AtomicInteger();

    // ------------------------------------------------------------------------
    // 空间
    // ------------------------------------------------------------------------

    /**
     * 创建一个空间并返回它的对外标识。
     *
     * <p>这是少数"必须成功"的准备方法之一：若建空间失败，后面的每一步都只是在
     * 测试一个不存在的东西，断言会变得毫无意义又难以看懂。
     *
     * @param token      创建者令牌
     * @param visibility 可见性，可为 null（按 PRIVATE）
     * @return 空间对外标识
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected String createWorkspace(String token, String visibility)
            throws IOException, InterruptedException {
        String name = "测试空间-" + NAME_SEQ.incrementAndGet();
        HttpResponse<String> response = sendJson("POST", "/api/v1/workspaces",
                toJson(body("name", name, "description", "集成测试用", "visibility", visibility)), token);
        if (response.statusCode() != 201) {
            throw new AssertionError("创建空间应返回 201，实际 " + response.statusCode() + "：" + response.body());
        }
        return json(response).path("publicId").asString("");
    }

    /**
     * 建一个默认（PRIVATE）空间。
     *
     * @param token 创建者令牌
     * @return 空间对外标识
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected String createWorkspace(String token) throws IOException, InterruptedException {
        return createWorkspace(token, null);
    }

    /**
     * 邀请一个用户并返回邀请码。
     *
     * @param inviterToken   邀请人令牌
     * @param workspaceId    空间对外标识
     * @param inviteeUsername 被邀请人登录名
     * @param role           角色，可为 null（按 MEMBER）
     * @return 邀请码；失败时返回空串（由调用方决定如何断言）
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected String invite(String inviterToken, String workspaceId, String inviteeUsername, String role)
            throws IOException, InterruptedException {
        HttpResponse<String> response = sendJson("POST",
                "/api/v1/workspaces/" + workspaceId + "/invites",
                toJson(body("username", inviteeUsername, "role", role)), inviterToken);
        if (response.statusCode() != 201) {
            throw new AssertionError("邀请应返回 201，实际 " + response.statusCode() + "：" + response.body());
        }
        return json(response).path("code").asString("");
    }

    /**
     * 兑换邀请码并断言成功。
     *
     * @param inviteeToken 被邀请人令牌
     * @param code         邀请码
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected void acceptInvite(String inviteeToken, String code) throws IOException, InterruptedException {
        HttpResponse<String> response = sendJson("POST",
                "/api/v1/workspace-invites/" + code + "/accept", "{}", inviteeToken);
        if (response.statusCode() != 200) {
            throw new AssertionError("兑换邀请应返回 200，实际 " + response.statusCode() + "：" + response.body());
        }
    }

    /**
     * 把一个人拉进空间：邀请 + 兑换。两步都必须成功。
     *
     * @param inviterToken    邀请人令牌
     * @param workspaceId     空间对外标识
     * @param inviteeToken    被邀请人令牌
     * @param inviteeUsername 被邀请人登录名
     * @param role            角色，可为 null
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected void addMember(String inviterToken, String workspaceId, String inviteeToken,
                            String inviteeUsername, String role)
            throws IOException, InterruptedException {
        acceptInvite(inviteeToken, invite(inviterToken, workspaceId, inviteeUsername, role));
    }

    // ------------------------------------------------------------------------
    // 笔记
    // ------------------------------------------------------------------------

    /**
     * 创建一篇笔记并返回它的对外标识。
     *
     * @param token       作者令牌
     * @param workspaceId 空间对外标识
     * @param title       标题
     * @return 笔记对外标识
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected String createNote(String token, String workspaceId, String title)
            throws IOException, InterruptedException {
        HttpResponse<String> response = sendJson("POST",
                "/api/v1/workspaces/" + workspaceId + "/notes",
                toJson(body("title", title, "bodyMd", "# " + title + "\n\n正文内容")), token);
        if (response.statusCode() != 201) {
            throw new AssertionError("创建笔记应返回 201，实际 " + response.statusCode() + "：" + response.body());
        }
        return json(response).path("publicId").asString("");
    }

    /**
     * 直接建一条笔记行，返回其自增主键（供"绕过服务层"的防线测试使用）。
     *
     * @param workspacePublicId 空间对外标识
     * @param authorUsername    作者登录名
     * @param title             标题
     * @return 笔记自增主键
     */
    protected long noteIdOf(String workspacePublicId, String authorUsername, String title) {
        return jdbcTemplate.queryForObject("""
                SELECT n.id
                FROM note n
                JOIN workspace w ON w.id = n.workspace_id
                JOIN `user` u ON u.id = n.author_id
                WHERE w.public_id = ? AND u.username = ? AND n.title = ?
                """, Long.class, workspacePublicId, authorUsername, title);
    }

    /**
     * 取空间的自增主键（供直接操作数据库的测试使用）。
     *
     * @param workspacePublicId 空间对外标识
     * @return 自增主键
     */
    protected long workspaceIdOf(String workspacePublicId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM workspace WHERE public_id = ?", Long.class, workspacePublicId);
        if (id == null) {
            throw new AssertionError("数据库中不存在空间：" + workspacePublicId);
        }
        return id;
    }

    /**
     * 取用户的自增主键。
     *
     * @param username 登录名
     * @return 自增主键
     */
    protected long findUserId(String username) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", Long.class, username);
        if (id == null) {
            throw new AssertionError("数据库中不存在登录名：" + username);
        }
        return id;
    }

    // ------------------------------------------------------------------------
    // 文档
    // ------------------------------------------------------------------------

    /**
     * 上传一个文档并返回它的对外标识。
     *
     * @param token       上传者令牌
     * @param workspaceId 空间对外标识
     * @param fileName    文件名（可以是恶意构造的，由被测代码净化）
     * @param contentType 声明的内容类型
     * @param content     内容
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<String> uploadDocument(String token, String workspaceId, String fileName,
                                                 String contentType, byte[] content)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url("/api/v1/workspaces/" + workspaceId + "/documents")))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipartBody(fileName, contentType, content)))
                .build();
        return BYTE_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 上传一个文档并断言成功。
     *
     * @param token       上传者令牌
     * @param workspaceId 空间对外标识
     * @param fileName    文件名
     * @param contentType 内容类型
     * @param content     内容
     * @return 文档对外标识
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected String uploadDocumentOk(String token, String workspaceId, String fileName,
                                     String contentType, byte[] content)
            throws IOException, InterruptedException {
        HttpResponse<String> response = uploadDocument(token, workspaceId, fileName, contentType, content);
        if (response.statusCode() != 201) {
            throw new AssertionError("上传应返回 201，实际 " + response.statusCode() + "：" + response.body());
        }
        return json(response).path("publicId").asString("");
    }

    /**
     * 下载文档，返回字节响应（下载的断言对象是字节与响应头，不是 JSON）。
     *
     * @param token       调用者令牌
     * @param workspaceId 空间对外标识
     * @param docId       文档对外标识
     * @return 字节响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    protected HttpResponse<byte[]> downloadDocument(String token, String workspaceId, String docId)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url("/api/v1/workspaces/" + workspaceId + "/documents/" + docId + "/content")))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return BYTE_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * 拼一个只含一个文件的 multipart 请求体。
     *
     * <p>刻意手写而不是引入一个测试用的 multipart 构造工具：这段格式是
     * RFC 7578 的固定部分，手写之后测试不依赖任何额外依赖，
     * 而且"服务端到底收到了什么"在测试里是可见的 ——
     * 用一个封装好的构造器时，这一点被藏了起来。
     *
     * @param fileName    文件名
     * @param contentType 内容类型
     * @param content     内容
     * @return 请求体字节
     */
    private static byte[] multipartBody(String fileName, String contentType, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String header = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /**
     * 从 JSON 响应里取错误码。
     *
     * @param response 响应
     * @return 错误码；响应里没有 code 字段时返回 -1
     */
    protected int errorCodeOf(HttpResponse<String> response) {
        JsonNode node = json(response).path("code");
        return node.isMissingNode() ? -1 : node.asInt(-1);
    }

    /**
     * 取某条审计动作在该用户下的条数。
     *
     * @param username 登录名
     * @param action   动作码
     * @return 条数
     */
    protected int auditCountOf(String username, String action) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit_log a
                JOIN `user` u ON u.id = a.actor_user_id
                WHERE u.username = ? AND a.action = ?
                """, Integer.class, username, action);
        return count == null ? 0 : count;
    }
}
