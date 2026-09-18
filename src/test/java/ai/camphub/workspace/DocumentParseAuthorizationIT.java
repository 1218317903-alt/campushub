package ai.camphub.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 「重新解析」与「读取分块」的授权矩阵。
 *
 * <h2>为什么这两条路径需要一组专门的用例</h2>
 * 它们都是 Phase 05 新开的读写面，而两者的授权理由并不相同：
 * <ul>
 *   <li><b>重新解析是一次写操作</b>，而且它的影响不局限于自己 ——
 *       它会把这份文档现有的可检索内容<b>整体替换</b>（旧分块先删后写），
 *       并占用一次解析资源。因此它是一个归属敏感的动作用例（与删除同类），
 *       而不是"随手可做的小事"。这条判定依赖第二层防线里的
 *       {@code ownedBySelf} 参数，是最容易在重构中被写成 {@code true} 的一处。</li>
 *   <li><b>读取分块是读操作</b>，它与元数据读取同源，因此必须被同一个空间边界约束 ——
 *       若它漏掉了资源级判定，就会成为一条能绕开元数据权限的旁路。</li>
 * </ul>
 *
 * <p>用例同时覆盖了"被拒绝"与"被允许"两个方向。只测拒绝的一侧是最省事的写法，
 * 也最容易通过 —— 一个把所有请求都拒掉的实现能通过全部拒绝用例。
 */
@DisplayName("文档解析授权 · 重试与分块")
class DocumentParseAuthorizationIT extends WorkspaceTestSupport {

    /** 上传用的正文。内容本身不重要，重要的是它能够被成功解析。 */
    private static final byte[] CONTENT = "一段用于授权测试的正文内容".getBytes(StandardCharsets.UTF_8);

    @Nested
    @DisplayName("重新解析的归属")
    class Retrying {

        @Test
        @DisplayName("成员能重试自己上传的文档")
        void memberRetriesOwnDocument() throws IOException, InterruptedException {
            TestAccount member = registerAccount(nextIp());
            String token = member.tokens().access();
            String workspace = createWorkspace(token);
            String docId = upload(token, workspace, "自己的.txt");

            HttpResponse<String> response = retry(token, workspace, docId);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response).path("parseStatus").asString(""))
                    .as("重试把文档放回等待解析")
                    .isEqualTo("PENDING");
        }

        @Test
        @DisplayName("成员不能重试别人的文档：会替换掉别人现有的可检索内容")
        void memberCannotRetryOthersDocument() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(),
                    member.username(), null);

            // 先让这份文档停在一个终态上，这样"有没有被重置"才是可观测的。
            String docId = uploadBrokenDocument(owner.tokens().access(), workspace);
            driveQueue();
            assertThat(parseTaskOf(docId).path("status").asString(""))
                    .as("前置条件：任务应当已经进入终态")
                    .isEqualTo("FAILED");

            HttpResponse<String> response = retry(member.tokens().access(), workspace, docId);

            assertThat(response.statusCode())
                    .as("成员看得到这份文档，因此不是 404；他缺的是'改动别人的东西'这项权限")
                    .isEqualTo(403);
            assertThat(errorCodeOf(response)).isEqualTo(40300);
            assertThat(parseTaskOf(docId).path("status").asString(""))
                    .as("被拒绝的请求不能留下任何副作用")
                    .isEqualTo("FAILED");
        }

        @Test
        @DisplayName("拥有者能重试任何人的文档 —— '收拾烂摊子'正是这个角色的职责")
        void ownerRetriesAnybodysDocument() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(),
                    member.username(), null);

            String docId = uploadBrokenDocument(member.tokens().access(), workspace);
            driveQueue();
            assertThat(parseTaskOf(docId).path("status").asString("")).isEqualTo("FAILED");

            HttpResponse<String> response = retry(owner.tokens().access(), workspace, docId);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(parseTaskOf(docId).path("status").asString(""))
                    .as("拥有者的重试必须真的生效，否则'能管理'就只是一句话")
                    .isEqualTo("PENDING");
            assertThat(parseTaskOf(docId).path("attempt_count").asInt(-1))
                    .as("重试要清零重试计数，否则一份已经耗尽的文档再试也没用")
                    .isZero();
        }

        @Test
        @DisplayName("陌生人一律 404：连'这份文档存在'都不会被确认")
        void strangerCannotEvenSeeIt() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            String docId = upload(owner.tokens().access(), workspace, "私有.txt");

            assertThat(retry(stranger.tokens().access(), workspace, docId).statusCode())
                    .as("空间不可见时按不存在处理")
                    .isEqualTo(404);
            assertThat(chunks(stranger.tokens().access(), workspace, docId).statusCode())
                    .as("分块接口不能成为'不知道空间也能读到内容'的旁路")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("已删除的文档不能被重试")
        void deletedDocumentCannotBeRetried() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            String docId = upload(token, workspace, "待删除.txt");

            assertThat(deleteWithToken(
                    "/api/v1/workspaces/" + workspace + "/documents/" + docId, token).statusCode())
                    .isEqualTo(204);

            assertThat(retry(token, workspace, docId).statusCode())
                    .as("删除之后再解析没有任何去处；返回 404 而不是把它复活")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("重复调用重试是安全的：任务始终只有一行，不会被复制")
        void repeatedRetryKeepsASingleTask() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            String docId = upload(token, workspace, "正文.txt");

            assertThat(retry(token, workspace, docId).statusCode()).isEqualTo(200);
            assertThat(retry(token, workspace, docId).statusCode())
                    .as("排队中的任务不该被打断，重复调用必须是无害的")
                    .isEqualTo(200);

            assertThat(parseTaskRowCount(docId))
                    .as("uk_document_task_document_type 只允许一行；"
                            + "新建而不是重置会撞唯一键，而'插入失败'不是重试的正确表达")
                    .isEqualTo(1);

            driveQueue();
        }
    }

    @Nested
    @DisplayName("分块读取")
    class ReadingChunks {

        @Test
        @DisplayName("成员能读空间里任何人上传的文档分块")
        void memberReadsAnybodysChunks() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(),
                    member.username(), null);

            String docId = upload(owner.tokens().access(), workspace, "拥有者的.txt");
            driveQueue();

            HttpResponse<String> response = chunks(member.tokens().access(), workspace, docId);
            assertThat(response.statusCode())
                    .as("分块是文档内容的一部分；能看到文档就能看到它")
                    .isEqualTo(200);
            assertThat(json(response).path("total").asLong(0))
                    .as("前置条件：解析应当已经产出分块")
                    .isPositive();
        }

        @Test
        @DisplayName("退出成员后立刻读不到分块 —— 授权集合是按请求算的，没有缓存残留")
        void leavingRevokesChunkAccessImmediately() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(),
                    member.username(), null);

            String docId = upload(owner.tokens().access(), workspace, "拥有者的.txt");
            driveQueue();
            assertThat(chunks(member.tokens().access(), workspace, docId).statusCode())
                    .isEqualTo(200);

            assertThat(deleteWithToken(
                    "/api/v1/workspaces/" + workspace + "/members/me", member.tokens().access())
                    .statusCode())
                    .isEqualTo(204);

            assertThat(chunks(member.tokens().access(), workspace, docId).statusCode())
                    .as("离开空间后连文档都不该看得见")
                    .isEqualTo(404);
        }
    }

    // ------------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------------

    /**
     * 上传一份正常文档并断言成功。
     *
     * @param token       上传者令牌
     * @param workspaceId 空间对外标识
     * @param fileName    文件名
     * @return 文档对外标识
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    private String upload(String token, String workspaceId, String fileName)
            throws IOException, InterruptedException {
        return uploadDocumentOk(token, workspaceId, fileName, "text/plain", CONTENT);
    }

    /**
     * 上传一份解析必然失败的文档（声称是 PDF，内容却不是）。
     *
     * <p>用它把任务推到 {@code FAILED} 终态，好让"重试有没有生效"成为一件可观测的事：
     * 一个从未执行过的任务本来就在 {@code PENDING}，重置与不重置看起来完全一样。
     *
     * @param token       上传者令牌
     * @param workspaceId 空间对外标识
     * @return 文档对外标识
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    private String uploadBrokenDocument(String token, String workspaceId)
            throws IOException, InterruptedException {
        return uploadDocumentOk(token, workspaceId, "损坏.pdf", "application/pdf",
                "这根本不是一份 PDF".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 发起一次重新解析。
     *
     * @param token       调用者令牌
     * @param workspaceId 空间对外标识
     * @param docId       文档对外标识
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    private HttpResponse<String> retry(String token, String workspaceId, String docId)
            throws IOException, InterruptedException {
        return sendJson("POST",
                "/api/v1/workspaces/" + workspaceId + "/documents/" + docId + "/parse", "{}", token);
    }

    /**
     * 读取文档分块。
     *
     * @param token       调用者令牌
     * @param workspaceId 空间对外标识
     * @param docId       文档对外标识
     * @return 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    private HttpResponse<String> chunks(String token, String workspaceId, String docId)
            throws IOException, InterruptedException {
        return getWithToken(
                "/api/v1/workspaces/" + workspaceId + "/documents/" + docId + "/chunks", token);
    }
}
