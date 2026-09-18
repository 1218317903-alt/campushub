package ai.camphub.workspace.app;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.hash.Sha256;
import ai.camphub.workspace.domain.DocumentTask;
import ai.camphub.workspace.domain.DocumentTaskType;
import ai.camphub.workspace.domain.WorkspaceDocument;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 单个任务的执行编排：读数据 → 干重活 → 写结果。
 *
 * <h2>它不持有事务，这是刻意的</h2>
 * 事务方法全在 {@link DocumentTaskRunner} 上。本类只负责把三段接力起来，
 * 中间那段（读字节 + 解析）在事务之外运行 —— 一次解析可能跑几十秒，
 * 把它包进事务等于让每个 worker 任务都占着一个数据库连接等 CPU 干完活。
 *
 * <h2>三种"无事可做"与一种"真正失败"的区别</h2>
 * 任务的目标可能已经不可能达成（文档被删了、字节被清了、行不存在了）。
 * 这些是<b>正常结果</b>，直接标记完成；把它们算作失败会让"失败任务"
 * 这个指标被正常情况填满，也会让用户在一个自己删掉的文档上看到报错。
 *
 * <h2>异常分类决定重试行为</h2>
 * 这是本类最需要小心的地方。分类错的代价是不对称的：
 * <ul>
 *   <li>把不可重试的判成可重试 → 白烧几次 CPU（可接受）。</li>
 *   <li>把可重试的判成不可重试 → 一次瞬时故障让用户看到"文件无法解析"（难接受）。</li>
 * </ul>
 * 因此归类原则是<b>拿不准就往可重试那边靠</b>，唯一的例外是那些
 * "同一份字节重跑必然同样结果"的确定性失败（文件损坏、加密、超限）——
 * 它们由 {@code DocumentParseException} 自己声明，本类只负责传递。
 */
@Service
public class DocumentTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTaskProcessor.class);

    /**
     * {@code document_task.last_error VARCHAR(500)} 的写入上限。
     *
     * <p>留出余量而不是顶着 500：异常消息可能带很长的路径或 SQL 片段，
     * 而一条"插入失败：Data too long"会盖掉真正的失败原因 ——
     * 那是最讽刺的一种失效：记录失败的动作自己失败了。
     */
    private static final int MAX_ERROR_LENGTH = 480;

    private final DocumentTaskRunner runner;
    private final DocumentParsingService parsingService;
    private final ObjectStorage objectStorage;

    /**
     * 构造注入。
     *
     * @param runner         事务边界
     * @param parsingService 解析与分块
     * @param objectStorage  对象存储端口
     */
    public DocumentTaskProcessor(DocumentTaskRunner runner,
                                 DocumentParsingService parsingService,
                                 ObjectStorage objectStorage) {
        this.runner = runner;
        this.parsingService = parsingService;
        this.objectStorage = objectStorage;
    }

    /**
     * 执行一个任务。
     *
     * @param task 已领取并持有租约的任务
     */
    public void process(DocumentTask task) {
        try {
            switch (task.taskType()) {
                case PARSE -> parse(task);
                case CLEANUP -> cleanup(task);
            }
        } catch (DocumentTaskRunner.LeaseLostException ex) {
            // 租约已被回收（很可能已被另一个 worker 接管）。
            // 这里什么都不要写 —— 任务已经不属于我们了。
            log.warn("租约已失效，本次结果被丢弃：taskId={} documentId={} type={}",
                    task.id(), task.documentId(), task.taskType());
        }
    }

    /**
     * 处理解析任务。
     *
     * @param task 任务
     */
    private void parse(DocumentTask task) {
        Optional<WorkspaceDocument> found = runner.load(task.documentId());
        if (found.isEmpty()) {
            runner.abandon(task, "文档行已不存在");
            return;
        }
        WorkspaceDocument document = found.get();
        if (document.isDeleted()) {
            // 用户上传后立刻删除。此时解析的产物没有任何去处 ——
            // 而提前判断比"跑完再发现文档已删"省下的是整份文件的读取与解析。
            runner.abandon(task, "文档已被删除");
            return;
        }
        if (!document.hasStoredContent()) {
            runner.abandon(task, "字节已被清理");
            return;
        }

        // 先提交"解析中"。它必须在解析之前落地，否则用户在整个解析期间
        // 看到的都是"等待解析"，而进度只会从 0 直接跳到 100。
        runner.beginProcessing(document.id());

        try {
            byte[] content = readContent(document);
            ParseOutcome outcome = parsingService.parse(content, document.mimeType(),
                    document.id(), document.workspaceId());
            runner.completeParse(task, outcome);
        } catch (DocumentTaskRunner.LeaseLostException ex) {
            throw ex;
        } catch (DocumentParseException ex) {
            runner.fail(task, ex, describe(ex));
        } catch (BusinessException ex) {
            // 存储返回 503（依赖不可用）。这是一次瞬时故障，重试有意义。
            runner.fail(task, DocumentParseException.transientFailure(
                    "读取文件内容失败，稍后会自动重试",
                    "存储访问失败：" + ex.errorCode(),
                    ex), describe(ex));
        } catch (RuntimeException ex) {
            // 未预期的异常按可重试处理。理由：它最可能来自一次瞬时故障
            // （连接抖动、临时文件系统问题），而重试有上限，
            // 最坏的结果也只是多试两次后照常失败。
            // 反过来假设它一定有 bug 而直接终止，代价是一次偶发问题
            // 让用户看到"文件无法解析"。
            runner.fail(task, DocumentParseException.transientFailure(
                    "解析过程中出现意外问题，稍后会自动重试",
                    "未预期异常", ex), describe(ex));
        }
    }

    /**
     * 处理清理任务。
     *
     * @param task 任务
     */
    private void cleanup(DocumentTask task) {
        Optional<WorkspaceDocument> found = runner.load(task.documentId());
        if (found.isEmpty()) {
            runner.abandon(task, "文档行已不存在");
            return;
        }
        WorkspaceDocument document = found.get();
        if (!document.hasStoredContent()) {
            runner.abandon(task, "字节已清理");
            return;
        }
        try {
            objectStorage.delete(document.storageKey());
        } catch (RuntimeException ex) {
            runner.fail(task, DocumentParseException.transientFailure(
                    "清理文件内容失败，稍后会自动重试",
                    "删除存储对象失败", ex), describe(ex));
            return;
        }
        runner.completeCleanup(task);
    }

    /**
     * 从存储读出完整内容并做完整性校验。
     *
     * <h2>两道校验，分工不同</h2>
     * <ul>
     *   <li><b>字节数</b>：能抓到截断。它便宜，而且它抓到的是最可能的一种损坏
     *       （写入中途断掉留下的半份内容）。</li>
     *   <li><b>SHA-256</b>：能抓到内容被替换或静默翻转。它贵一些（要遍历全部字节），
     *       但相比紧随其后的解析开销可以忽略。</li>
     * </ul>
     * 两者都失败时归为<b>不可重试</b>：同一份字节再读一次还是同样的结果，
     * 而重试会让一份大文件被完整读三遍。
     *
     * @param document 文档
     * @return 内容字节
     * @throws DocumentParseException 读取失败或校验不通过
     */
    private byte[] readContent(WorkspaceDocument document) {
        byte[] content;
        try (InputStream in = objectStorage.open(document.storageKey())) {
            content = in.readAllBytes();
        } catch (IOException ex) {
            // 流读到一半断了：瞬时故障的可能性很大，值得重试。
            throw DocumentParseException.transientFailure(
                    "读取文件内容失败，稍后会自动重试",
                    "读取存储对象失败：key=" + document.storageKey(),
                    ex);
        }

        if (content.length != document.sizeBytes()) {
            throw DocumentParseException.permanent(
                    "文件内容与登记的大小不一致，可能已损坏",
                    "字节数不符：expected=" + document.sizeBytes() + " actual=" + content.length,
                    null);
        }
        if (!Sha256.matches(content, document.sha256())) {
            throw DocumentParseException.permanent(
                    "文件内容校验失败，可能已损坏",
                    "SHA-256 不符：expected=" + document.sha256(),
                    null);
        }
        return content;
    }

    /**
     * 把异常转成一条写入 {@code last_error} 的描述。
     *
     * @param ex 异常
     * @return 截断到列宽之内的描述
     */
    private static String describe(Throwable ex) {
        String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH) + "...";
    }
}
