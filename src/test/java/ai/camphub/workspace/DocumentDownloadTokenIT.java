package ai.camphub.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 短期下载链接的签发与兑换。
 *
 * <h2>为什么这个类要单独存在，而不是并进越权测试</h2>
 * 它是本项目<b>唯一一条不经过身份鉴权的读取路径</b>。其余所有接口都靠
 * "调用者是谁"来决定能不能看；这一条没有调用者 —— 请求里只有一枚令牌。
 * 因此它的正确性完全落在三件事上：<b>签发前是否真的判过权限、令牌是否绑定了
 * 一份具体的文档、以及它是否会在该失效的时候失效</b>。
 * 这三件事都不是"越权矩阵"能表达的，需要一组专门的用例。
 *
 * <h2>为什么"令牌能被转发"不是缺陷</h2>
 * 短链的定义就是"在时限内、凭地址即可访问"。因此这里不断言"别人拿到链接用不了" ——
 * 那与短链的用途直接矛盾。被断言的是它的<b>边界</b>：不能改指向
 * （换文档、延长有效期都要重签名）、不能当身份凭据用、文档删除后立即失效。
 */
@DisplayName("文档下载链接 · 令牌签发与兑换")
class DocumentDownloadTokenIT extends WorkspaceTestSupport {

    /** 下载路径前缀。只在断言里出现一次，避免与实现各写一遍。 */
    private static final String DOWNLOAD_PATH = "/api/v1/document-downloads/";

    /** 内容与文件名唯一化序号。 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 一份文档：对外标识 + 上传时的原始字节。 */
    private record Uploaded(String publicId, byte[] content) {
    }

    @Nested
    @DisplayName("签发")
    class Issuing {

        @Test
        @DisplayName("本地后端签出的是应用自己托管的地址，并如实说明它绕不开应用")
        void localBackendIssuesAnAppHostedLink() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            Uploaded doc = upload(token, workspace);

            HttpResponse<String> response = issueLink(token, workspace, doc.publicId());
            assertThat(response.statusCode()).isEqualTo(200);

            JsonNode link = json(response);
            assertThat(link.path("direct").asBoolean(true))
                    .as("本地磁盘给不出预签名直链 —— 谎报 direct 会让容量规划照着错误的假设做")
                    .isFalse();
            assertThat(link.path("url").asString("")).startsWith(DOWNLOAD_PATH);

            Instant expiresAt = Instant.parse(link.path("expiresAt").asString(""));
            Instant now = Instant.now();
            assertThat(expiresAt).isAfter(now);
            assertThat(expiresAt)
                    .as("有效期就是链接泄漏后的暴露窗口，不该远大于配置的 TTL")
                    .isBefore(now.plusSeconds(600));
        }

        @Test
        @DisplayName("成员可以为空间里别人上传的文档签链接 —— 签发权与下载权同源")
        void memberCanIssueForAnyonesDocument() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(),
                    member.username(), null);

            Uploaded doc = upload(owner.tokens().access(), workspace);

            assertThat(issueLink(member.tokens().access(), workspace, doc.publicId()).statusCode())
                    .as("能下载就能签链接；否则会出现'下载按钮可用、生成链接报错'的不一致")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("陌生人签不出链接：空间不可见时返回 404，不确认文档是否存在")
        void outsiderCannotIssue() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            Uploaded doc = upload(owner.tokens().access(), workspace);

            HttpResponse<String> response =
                    issueLink(stranger.tokens().access(), workspace, doc.publicId());
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(errorCodeOf(response)).isEqualTo(40400);
        }

        @Test
        @DisplayName("每次成功签发都留一条审计 —— 短链的事后追溯只能从这条记录开始")
        void issuingIsAudited() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            Uploaded doc = upload(token, workspace);

            int before = auditCountOf(owner.username(), "DOCUMENT_DOWNLOAD_LINK");
            issueLink(token, workspace, doc.publicId());

            assertThat(auditCountOf(owner.username(), "DOCUMENT_DOWNLOAD_LINK"))
                    .as("它与 /content 的审计语义不同：这里记录的是"
                            + "'某人获准得到一个可转发的地址'，而不是'某人下载了一次'")
                    .isEqualTo(before + 1);
        }
    }

    @Nested
    @DisplayName("兑换")
    class Redeeming {

        @Test
        @DisplayName("不需要登录凭据，且三个响应头全部到位")
        void redemptionNeedsNoCredential() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            Uploaded doc = upload(token, workspace);

            String path = pathOf(issueLink(token, workspace, doc.publicId()));
            // 刻意不带 Authorization 头：这就是短链的用法。
            HttpResponse<byte[]> response = getBytes(path, null);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .as("下载路径唯一要验证的就是字节本身")
                    .isEqualTo(doc.content());

            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .as("不回显用户声明的类型；否则上传一个 HTML 就是一次链接式 XSS")
                    .startsWith("application/octet-stream");
            assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                    .startsWith("attachment");
            assertThat(response.headers().firstValue("Cache-Control").orElse(""))
                    .as("凭地址即可访问的 URL 若被中间代理缓存，5 分钟的有效期就不再是暴露窗口")
                    .contains("no-store");
        }

        @Test
        @DisplayName("令牌在有效期内可重复使用 —— 它是一枚凭据，不是一次性的券")
        void tokenIsReusable() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            Uploaded doc = upload(token, workspace);

            String path = pathOf(issueLink(token, workspace, doc.publicId()));
            assertThat(getBytes(path, null).statusCode()).isEqualTo(200);
            assertThat(getBytes(path, null).statusCode())
                    .as("浏览器重试、下载管理器分块取数都会二次请求同一个地址")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("令牌只指向它被签发时的那一份文档")
        void tokenIsBoundToItsDocument() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            Uploaded mine = upload(token, workspace);
            Uploaded other = upload(token, workspace);

            // 伪造一版"把文档标识换掉"的令牌：载荷自己拼，签名沿用原来那一枚。
            String forged = forgeToken(workspace, other.publicId(),
                    tokenOf(pathOf(issueLink(token, workspace, mine.publicId()))));

            HttpResponse<String> response = getWithToken(DOWNLOAD_PATH + forged, null);
            assertThat(response.statusCode())
                    .as("换指向必须重签名，而这需要密钥")
                    .isEqualTo(400);
            assertThat(errorCodeOf(response)).isEqualTo(40024);
        }

        @Test
        @DisplayName("令牌不能当身份凭据使用")
        void tokenIsNotAnIdentityCredential() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            Uploaded doc = upload(token, workspace);

            String downloadToken = tokenOf(pathOf(issueLink(token, workspace, doc.publicId())));

            HttpResponse<String> response = getWithToken(
                    "/api/v1/workspaces/" + workspace + "/documents/" + doc.publicId(),
                    downloadToken);

            assertThat(response.statusCode())
                    .as("把短链令牌放进 Authorization 头是这次尝试的核心 —— "
                            + "若它被当成访问令牌接受，短链就从'受限的一次下载'升级成了'完整会话'")
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("篡改、截断与垃圾输入一律 40024，且不泄漏它错在哪一步")
        void invalidTokensAreRejected() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            Uploaded doc = upload(token, workspace);

            String valid = tokenOf(pathOf(issueLink(token, workspace, doc.publicId())));
            int dot = valid.indexOf('.');
            String payloadPart = valid.substring(0, dot);
            String signaturePart = valid.substring(dot + 1);

            String[] broken = {
                    // 自助延长有效期：把载荷换成一年之后，签名照旧。
                    encode(workspace + ":" + doc.publicId() + ":"
                            + Instant.now().plusSeconds(31_536_000L).getEpochSecond())
                            + "." + signaturePart,
                    // 改签名段。
                    payloadPart + "." + flipFirstChar(signaturePart),
                    // 截断：只剩载荷。
                    payloadPart,
                    // 分隔符落在开头 / 结尾。
                    "." + signaturePart,
                    payloadPart + ".",
                    // 随机串，以及"分隔符在、但两段都不是合法 base64url"。
                    // 这里不能用 `#` 之类的字符：它在 URL 里是片段分隔符，
                    // 会在请求到达接口之前就被客户端拒掉，于是测的就不是被测代码了。
                    "not-a-token-" + SEQ.incrementAndGet(),
                    "!!!.~~~",
            };

            for (String candidate : broken) {
                HttpResponse<String> response = getWithToken(DOWNLOAD_PATH + candidate, null);
                assertThat(response.statusCode())
                        .as("被篡改的令牌必须被拒绝：%s", candidate)
                        .isEqualTo(400);
                assertThat(errorCodeOf(response))
                        .as("错误码必须统一为 40024，不能按失败原因分叉 —— "
                                + "分叉的返回会告诉攻击者'你的签名是对的、只是过期了'")
                        .isEqualTo(40024);
            }
        }

        @Test
        @DisplayName("文档一删除，链接立即失效")
        void tokenDiesWithItsDocument() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            Uploaded doc = upload(token, workspace);

            String path = pathOf(issueLink(token, workspace, doc.publicId()));
            assertThat(getBytes(path, null).statusCode()).isEqualTo(200);

            assertThat(deleteWithToken(
                    "/api/v1/workspaces/" + workspace + "/documents/" + doc.publicId(), token)
                    .statusCode())
                    .isEqualTo(204);

            assertThat(getBytes(path, null).statusCode())
                    .as("删除必须比链接的时限更有权威，否则'删掉的文件'还能再被下载 5 分钟")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("空令牌与超长令牌都不例外")
        void emptyAndOversizedTokensAreRejected() throws IOException, InterruptedException {
            assertThat(getWithToken("/api/v1/document-downloads/", null).statusCode())
                    .as("没有令牌时是路由不匹配，而不是'验签通过'")
                    .isIn(400, 404, 405);

            HttpResponse<String> response = getWithToken(DOWNLOAD_PATH + "A".repeat(4096), null);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(errorCodeOf(response)).isEqualTo(40024);
        }
    }

    // ------------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------------

    /**
     * 上传一份内容与文件名都唯一的纯文本。
     *
     * @param token       上传者令牌
     * @param workspaceId 空间对外标识
     * @return 文档标识与字节
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    private Uploaded upload(String token, String workspaceId)
            throws IOException, InterruptedException {
        int seq = SEQ.incrementAndGet();
        byte[] content = ("文档内容 " + seq + " —— 用于校验字节是否被完整搬运")
                .getBytes(StandardCharsets.UTF_8);
        String docId = uploadDocumentOk(token, workspaceId, "正文" + seq + ".txt", "text/plain", content);
        return new Uploaded(docId, content);
    }

    /**
     * 请求签发一个下载链接。
     *
     * @param token       调用者令牌
     * @param workspaceId 空间对外标识
     * @param docId       文档对外标识
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    private HttpResponse<String> issueLink(String token, String workspaceId, String docId)
            throws IOException, InterruptedException {
        return sendJson("POST",
                "/api/v1/workspaces/" + workspaceId + "/documents/" + docId + "/download-link",
                "{}", token);
    }

    /**
     * 从签发响应里取出下载地址。
     *
     * @param response 签发响应
     * @return 地址（相对路径）
     */
    private String pathOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        String path = json(response).path("url").asString("");
        assertThat(path).as("签发响应必须带一个可用的地址").isNotBlank();
        return path;
    }

    /**
     * 取出地址路径里的令牌。
     *
     * @param downloadPath 下载地址
     * @return 令牌
     */
    private static String tokenOf(String downloadPath) {
        return downloadPath.substring(downloadPath.lastIndexOf('/') + 1);
    }

    /**
     * 把载荷换成另一份文档（签名沿用原来的那一枚），构造一次伪造尝试。
     *
     * <p>令牌形状是 {@code base64url(载荷).base64url(签名)}，载荷为
     * {@code 空间:文档:过期秒}。base64url 的字母表里没有 {@code .}，
     * 因此第一个 {@code .} 必然是分隔符。
     *
     * @param workspaceId   空间对外标识
     * @param targetDocId   想换成的文档
     * @param originalToken 原始令牌
     * @return 伪造的令牌
     */
    private static String forgeToken(String workspaceId, String targetDocId, String originalToken) {
        String signature = originalToken.substring(originalToken.indexOf('.') + 1);
        String payload = workspaceId + ":" + targetDocId + ":"
                + Instant.now().plusSeconds(3600).getEpochSecond();
        return encode(payload) + "." + signature;
    }

    /**
     * 把字符串的第一个字符换掉，得到一个确定损坏的签名。
     *
     * @param value 原字符串
     * @return 改动后的字符串
     */
    private static String flipFirstChar(String value) {
        char original = value.charAt(0);
        char replacement = original == 'A' ? 'B' : 'A';
        return replacement + value.substring(1);
    }

    /**
     * base64url 无填充编码，与令牌签发的编码方式一致。
     *
     * @param value 原文
     * @return 编码结果
     */
    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
