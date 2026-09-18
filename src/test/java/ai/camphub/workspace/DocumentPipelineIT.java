package ai.camphub.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 文档处理流水线的端到端测试：上传 → 落盘 → 入队 → 解析 → 切分 → 可查。
 *
 * <p>断言在三处都要看：接口、数据库、磁盘分别回答不同的问题 ——
 * 接口说"用户看到什么"，数据库说"事实是什么"，磁盘说"字节还在不在"。
 * 三者中任意一处单独成立都不够：一个"接口说 READY 而库里没有任何分块"的系统，
 * 从接口测试看是完全正常的。
 *
 * <p>解析由 {@link WorkspaceTestSupport#driveQueue()} 驱动，测试期 worker 是关的。
 */
@DisplayName("文档流水线 · 端到端")
class DocumentPipelineIT extends WorkspaceTestSupport {

    /**
     * 一份刻意超过分块上界的 Markdown。
     *
     * <p>测试 profile 把 {@code chunk-max-chars} 收紧到 100，因此第一段（111 字符）
     * 会在<b>同一个标题之内</b>被切成两块，且切点落在句末标点之后（79 + 31）。
     * 这是有意构造的：若样本里每一段都短于上界，那么"超长段落的两级切分"
     * 这条路径在整条流水线上永远不会被执行到，而它是切分器里最容易写错的一处。
     *
     * <p>第二、三段各不足上界，因此各出一块。三个标题互不相同，
     * 而切分器<b>不跨越标题合并</b> —— 于是标题路径与块总数的关系是确定的。
     */
    private static final String MARKDOWN = """
            # 空间文档规范

            上传的文档会先落盘，再由后台任务解析成可检索的分块。这一段正文刻意写得长一些，
            目的是让切分器在一个标题之内至少产出两个块：测试环境把分块上界收紧到一百字符，
            因此两三行文字就跨越了边界，不必在样本里塞进一份几千字的文档。

            ## 支持的格式

            当前支持纯文本、Markdown 与 PDF。

            ## 失败与重试

            文件损坏属于不可重试的失败：同一份字节重跑必然得到同样的结果，重试只会让一份大文件被完整读三遍。
            """;

    /** 三个标题各自的完整路径，按文档顺序。 */
    private static final List<String> EXPECTED_HEADINGS = List.of(
            "空间文档规范",
            "空间文档规范 > 支持的格式",
            "空间文档规范 > 失败与重试");

    @Nested
    @DisplayName("顺利路径")
    class HappyPath {

        @Test
        @DisplayName("上传只入队不解析；驱动之后状态、计数与分块三者必须互相吻合")
        void uploadQueuesAndParseProducesChunks() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            String docId = uploadDocumentOk(token, workspace, "文档规范.md", "text/markdown",
                    MARKDOWN.getBytes(StandardCharsets.UTF_8));

            JsonNode queued = documentOf(token, workspace, docId);
            assertThat(queued.path("parseStatus").asString(""))
                    .as("上传不解析：测试期 worker 是关的，此刻只能是等待解析")
                    .isEqualTo("PENDING");
            assertThat(queued.path("chunkCount").asInt(-1)).isZero();
            assertThat(queued.path("textLength").asInt(-1)).isZero();
            assertThat(queued.path("parsedAt").isNull())
                    .as("还没解析过，不该有完成时间")
                    .isTrue();
            assertThat(queued.path("deletableByMe").asBoolean(false)).isTrue();
            assertThat(queued.path("retryableByMe").asBoolean(false)).isTrue();

            int processed = driveQueue();
            assertThat(processed).as("上传入队的 PARSE 任务应当被驱动到").isGreaterThanOrEqualTo(1);

            JsonNode ready = documentOf(token, workspace, docId);
            assertThat(ready.path("parseStatus").asString("")).isEqualTo("READY");
            assertThat(ready.path("parseProgress").asInt(-1)).isEqualTo(100);
            assertThat(ready.path("parseMessage").isTextual())
                    .as("成功时不该留下任何面向用户的失败描述")
                    .isFalse();
            assertThat(ready.path("parsedAt").isNull()).isFalse();
            assertThat(ready.path("textLength").asInt(0)).isPositive();

            int chunkCount = ready.path("chunkCount").asInt(-1);
            assertThat(chunkCount).isPositive();

            // 接口、库、分块表三者必须说同一件事。
            JsonNode chunks = pageOf(docPath(workspace, docId) + "/chunks", token);
            assertThat(chunks.path("total").asLong(-1)).isEqualTo(chunkCount);
            assertThat(chunks.path("items").size()).isEqualTo(chunkCount);
            assertThat(chunkRowCount(docId)).isEqualTo(chunkCount);

            assertChunkShape(chunks.path("items"));
        }

        @Test
        @DisplayName("分块不跨越标题合并 —— 标题是作者画出的语义边界")
        void chunksCarryTheirHeadingPath() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            String docId = uploadDocumentOk(token, workspace, "文档规范.md", "text/markdown",
                    MARKDOWN.getBytes(StandardCharsets.UTF_8));
            driveQueue();

            JsonNode items = pageOf(docPath(workspace, docId) + "/chunks", token).path("items");

            List<String> headings = new ArrayList<>();
            for (JsonNode item : items) {
                headings.add(item.path("heading").asString(""));
            }

            // 把连续相同的标题折叠成一段：折叠后的序列就是"标题在文档里出现的顺序"。
            List<String> runs = new ArrayList<>();
            for (String heading : headings) {
                if (runs.isEmpty() || !runs.get(runs.size() - 1).equals(heading)) {
                    runs.add(heading);
                }
            }

            assertThat(runs)
                    .as("标题路径必须与 Markdown 的结构一致；"
                            + "某个标题出现两次说明切分器跨过它合并了")
                    .isEqualTo(EXPECTED_HEADINGS);
            assertThat(items.size())
                    .as("块数应多于标题数，证明超长段落真的被切开了")
                    .isGreaterThan(EXPECTED_HEADINGS.size());
        }

        @Test
        @DisplayName("尚未解析成功时，分块接口返回空列表而不是 404")
        void chunksAreEmptyNotMissingBeforeParse() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            String docId = uploadDocumentOk(token, workspace, "还没解析.txt", "text/plain",
                    "正文".getBytes(StandardCharsets.UTF_8));

            HttpResponse<String> response =
                    getWithToken(docPath(workspace, docId) + "/chunks", token);
            assertThat(response.statusCode())
                    .as("它确实存在，只是还没有内容 —— 404 会让前端误以为文档没了")
                    .isEqualTo(200);
            assertThat(json(response).path("total").asLong(-1)).isZero();
            assertThat(json(response).path("items").size()).isZero();

            driveQueue();
        }

        @Test
        @DisplayName("重新解析会替换而不是累加旧分块 —— 这是重试能成立的先决条件")
        void retryReplacesChunksInsteadOfAppending() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            String docId = uploadDocumentOk(token, workspace, "文档规范.md", "text/markdown",
                    MARKDOWN.getBytes(StandardCharsets.UTF_8));
            driveQueue();

            int before = documentOf(token, workspace, docId).path("chunkCount").asInt(-1);
            assertThat(before).isPositive();

            HttpResponse<String> retried = sendJson("POST", docPath(workspace, docId) + "/parse",
                    "{}", token);
            assertThat(retried.statusCode()).isEqualTo(200);
            assertThat(json(retried).path("parseStatus").asString(""))
                    .as("重试把文档放回等待解析，而不是留在 READY")
                    .isEqualTo("PENDING");

            driveQueue();

            int after = documentOf(token, workspace, docId).path("chunkCount").asInt(-1);
            assertThat(after)
                    .as("旧分块必须先被清掉；否则新块会撞 uk_document_chunk_ordinal，"
                            + "表现为'第一次失败之后永远失败'")
                    .isEqualTo(before);
            assertThat(chunkRowCount(docId)).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("失败路径")
    class FailurePath {

        @Test
        @DisplayName("文件损坏是不可重试的：一次尝试就终结，且不把内部细节交给用户")
        void corruptFileFailsOnceAndStaysFailed() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            // 声称是 PDF，内容却不是：PDFBox 打开时必然失败，且同一份字节重试结果相同。
            String docId = uploadDocumentOk(token, workspace, "损坏.pdf", "application/pdf",
                    "这根本不是一份 PDF".getBytes(StandardCharsets.UTF_8));

            driveQueue();

            JsonNode failed = documentOf(token, workspace, docId);
            assertThat(failed.path("parseStatus").asString("")).isEqualTo("FAILED");
            assertThat(failed.path("parseMessage").asString("")).isNotBlank();

            String message = failed.path("parseMessage").asString("");
            assertThat(message)
                    .as("面向用户的失败描述不能泄漏实现细节")
                    .doesNotContain("PDFBox", "Exception", "IOException", "RandomAccessRead");

            assertThat(failed.path("retryableByMe").asBoolean(false))
                    .as("失败后仍应允许上传者手工再试一次 —— 换一份文件就能成功")
                    .isTrue();

            JsonNode task = parseTaskOf(docId);
            assertThat(task.path("status").asString("")).isEqualTo("FAILED");
            assertThat(task.path("attempt_count").asInt(-1))
                    .as("不可重试的失败只该被尝试一次；重试只是让大文件被完整读三遍")
                    .isEqualTo(1);
            assertThat(task.path("last_error").asString("")).isNotBlank();

            assertThat(driveQueue())
                    .as("终态任务不应再被派发")
                    .isZero();
        }

        @Test
        @DisplayName("字节读不到是可重试的：退避等待期间文档回到 PENDING 而不是 FAILED")
        void unreadableBytesRescheduleWithBackoff() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            String docId = uploadDocumentOk(token, workspace, "临时故障.txt", "text/plain",
                    "一份内容完全正常的纯文本".getBytes(StandardCharsets.UTF_8));

            // 把字节从磁盘上抹掉，模拟"存储暂时取不到"这一类瞬时故障。
            Files.delete(storedFileOf(docId));

            driveQueue();

            JsonNode document = documentOf(token, workspace, docId);
            assertThat(document.path("parseStatus").asString(""))
                    .as("瞬时故障不该让用户看到'文件无法解析'")
                    .isEqualTo("PENDING");
            assertThat(document.path("parseProgress").asInt(-1))
                    .as("进度必须归零；留在 5 会让用户在整个退避期看到'正在解析'")
                    .isZero();

            JsonNode task = parseTaskOf(docId);
            assertThat(task.path("status").asString("")).isEqualTo("PENDING");
            assertThat(task.path("attempt_count").asInt(-1)).isEqualTo(1);
            assertThat(task.path("last_error").asString("")).isNotBlank();
            assertThat(task.path("next_attempt_at").isNull())
                    .as("退避落在 next_attempt_at 上，它同时就是'什么时候可以重试'")
                    .isFalse();

            assertThat(driveQueue())
                    .as("退避窗口未到，任务不该被再次派发")
                    .isZero();
        }

        @Test
        @DisplayName("人工重试能立刻切掉退避等待，故障排除后真的能解析成功")
        void manualRetryCutsTheBackoffShort() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String token = owner.tokens().access();
            String workspace = createWorkspace(token);
            byte[] content = "故障排除之后应当可以正常解析".getBytes(StandardCharsets.UTF_8);
            String docId = uploadDocumentOk(token, workspace, "待恢复.txt", "text/plain", content);

            Path stored = storedFileOf(docId);
            Files.delete(stored);

            driveQueue();
            assertThat(documentOf(token, workspace, docId).path("parseStatus").asString(""))
                    .isEqualTo("PENDING");
            assertThat(parseTaskOf(docId).path("attempt_count").asInt(-1)).isEqualTo(1);

            // 故障排除：把字节放回去 —— 但任务还在退避窗口里，不会自己跑起来。
            Files.createDirectories(stored.getParent());
            Files.write(stored, content);

            // 用户点了「重新解析」。
            HttpResponse<String> retried = sendJson("POST", docPath(workspace, docId) + "/parse",
                    "{}", token);
            assertThat(retried.statusCode()).isEqualTo(200);

            JsonNode reset = parseTaskOf(docId);
            assertThat(reset.path("attempt_count").asInt(-1))
                    .as("等退避不等于'正在排队等它跑'——人工重试的语义就是'别等了'")
                    .isZero();
            assertThat(reset.path("status").asString("")).isEqualTo("PENDING");

            assertThat(driveQueue())
                    .as("重置之后任务必须立刻可派发；否则用户点了按钮却要等最长两分钟"
                            + "才看到任何变化，而响应是 200、状态是 PENDING —— "
                            + "界面无法把'已经受理'与'什么都没发生'区分开")
                    .isGreaterThanOrEqualTo(1);

            JsonNode ready = documentOf(token, workspace, docId);
            assertThat(ready.path("parseStatus").asString(""))
                    .as("故障排除后应当自愈，不需要重新上传")
                    .isEqualTo("READY");
            assertThat(ready.path("chunkCount").asInt(0)).isPositive();
        }
    }

    // ------------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------------

    /**
     * 断言分块列表的形状：序号自 0 连续、内容非空且不超过配置上界。
     *
     * @param items 接口返回的分块数组
     */
    private void assertChunkShape(JsonNode items) {
        int bound = properties.parsing().chunkMaxChars();
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            assertThat(item.path("ordinal").asInt(-1))
                    .as("序号必须自 0 连续 —— 它是 uk_document_chunk_ordinal 的一半")
                    .isEqualTo(index);
            assertThat(item.path("content").asString("")).isNotBlank();
            assertThat(item.path("charCount").asInt(-1))
                    .as("分块不得超过配置的上界")
                    .isBetween(1, bound);
            assertThat(item.path("charCount").asInt(-1))
                    .as("charCount 必须与内容长度一致，否则它就是一个可以被写错的字段")
                    .isEqualTo(item.path("content").asString("").length());
        }
    }

    /**
     * 取文档元数据。
     *
     * @param token       访问令牌
     * @param workspaceId 空间对外标识
     * @param docId       文档对外标识
     * @return 响应 JSON
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    private JsonNode documentOf(String token, String workspaceId, String docId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = getWithToken(docPath(workspaceId, docId), token);
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response);
    }

    /**
     * 取一个分页响应。
     *
     * @param path  路径
     * @param token 访问令牌
     * @return 响应 JSON
     * @throws IOException          网络异常
     * @throws InterruptedException 被中断
     */
    private JsonNode pageOf(String path, String token) throws IOException, InterruptedException {
        HttpResponse<String> response = getWithToken(path, token);
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response);
    }

    /**
     * 拼文档接口的路径。
     *
     * @param workspaceId 空间对外标识
     * @param docId       文档对外标识
     * @return 路径
     */
    private static String docPath(String workspaceId, String docId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + docId;
    }
}
