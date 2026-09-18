package ai.camphub.workspace.app;

import ai.camphub.workspace.config.WorkspaceProperties;
import ai.camphub.workspace.domain.ChunkDraft;
import ai.camphub.workspace.domain.DocumentTask;
import ai.camphub.workspace.domain.WorkspaceDocument;
import ai.camphub.workspace.infrastructure.DocumentChunkMapper;
import ai.camphub.workspace.infrastructure.DocumentMapper;
import ai.camphub.workspace.infrastructure.DocumentTaskMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 异步任务的事务边界。
 *
 * <h2>为什么把事务单独抽成一个类，而不是给 worker 的方法加 {@code @Transactional}</h2>
 * 两个原因，都是硬性的：
 *
 * <ol>
 *   <li><b>自调用不走代理。</b>若把 {@code claim} 与 {@code execute} 写在同一个类里，
 *       一个方法调用另一个方法时 Spring 的代理不介入 —— {@code @Transactional}
 *       静默失效。那是一个不会报错、只表现为"回滚没生效"的问题。</li>
 *   <li><b>解析必须在事务外。</b>一次解析可能跑几十秒，而它期间不应该持有
 *       数据库连接。把它包在事务里，等于让 worker 的每个任务都占着一个连接
 *       等待 CPU 工作完成 —— 数据库连接池会很诚实地在并发几个任务后耗尽。</li>
 * </ol>
 *
 * <p>因此边界是：<b>短暂的事务（领任务、改状态）→ 事务外的长任务（读字节、解析）
 * → 再次短暂的事务（写结果）</b>。这个类负责全部事务方法，worker 负责在这三段之间接力。
 *
 * <h2>失败也要提交</h2>
 * 任务失败时，"状态改成失败/重排"本身就是一次需要提交的写入。因此
 * {@link #fail} 不抛异常 —— 若让异常冒泡出去，事务回滚，失败状态一起丢掉，
 * 任务会永远停在 RUNNING 直到租约过期。
 */
@Service
public class DocumentTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentTaskRunner.class);

    /** 退避上限（秒）。指数退避在几次之后会涨到不合理的量级，必须封顶。 */
    private static final long MAX_BACKOFF_SECONDS = 3_600;

    private final DocumentTaskMapper taskMapper;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final WorkspaceProperties properties;
    private final Clock clock;

    /**
     * 构造注入。
     *
     * @param taskMapper     任务队列访问
     * @param documentMapper 文档访问
     * @param chunkMapper    分块访问
     * @param properties     空间模块配置（租约、退避、重试上限）
     * @param clock          时钟
     */
    public DocumentTaskRunner(DocumentTaskMapper taskMapper,
                              DocumentMapper documentMapper,
                              DocumentChunkMapper chunkMapper,
                              WorkspaceProperties properties,
                              Clock clock) {
        this.taskMapper = taskMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 回收租约过期的任务。
     *
     * @return 被回收的任务数
     */
    @Transactional
    public int recoverExpiredLeases() {
        int recovered = taskMapper.recoverExpiredLeases(clock.instant());
        if (recovered > 0) {
            // 这条日志值得留：它意味着确实有一个进程没有走完就消失了，
            // 或者某个任务的执行时间超过了租约。两种都值得被看见。
            log.warn("回收了 {} 个租约过期的任务", recovered);
        }
        return recovered;
    }

    /**
     * 领取一批任务并写入租约。
     *
     * <h2>租约长度为什么按批量放大</h2>
     * 本方法一次领走 {@code batchSize} 个任务，而它们由同一个线程<b>串行</b>处理。
     * 若租约只按单个任务的时限给，最后一个任务开始执行时前面几个已经耗尽了租约 ——
     * 于是它会被另一个 worker 判定为"持有者已死"而回收并重复执行。
     * 两个 worker 同时解析同一份文档不会损坏数据（写入有租约条件保护），
     * 但它白白烧掉了双倍的 CPU，而且让"为什么这个任务跑了两次"变成一个要查日志的问题。
     *
     * @param batchSize  本次最多领取几个
     * @param leaseOwner worker 标识
     * @return 已置为运行中的任务；没有可领任务时为空列表
     */
    @Transactional
    public List<DocumentTask> claim(int batchSize, String leaseOwner) {
        Instant now = clock.instant();
        List<Long> ids = taskMapper.lockDispatchable(batchSize, now).stream()
                .map(DocumentTask::id)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        // 租约覆盖"整批串行处理"所需的时间，再加一分钟的余量吸收调度抖动。
        long leaseSeconds = (long) properties.worker().leaseSeconds() * batchSize + 60;
        int updated = taskMapper.markRunning(ids, leaseOwner, now.plusSeconds(leaseSeconds), now);
        if (updated != ids.size()) {
            // 锁定数与更新数不符：说明这批行在本次事务内被别的事务改动了。
            // 抛异常回滚，让它们回到可派发状态 —— 下一轮重新领取，
            // 而不是带着一个不完整的批次继续跑。
            throw new IllegalStateException(
                    "领取任务时影响行数与锁定数不符：locked=" + ids.size() + " updated=" + updated);
        }

        // 重读一次：attempt_count 与租约刚被改写，执行方需要准确值。
        return ids.stream()
                .map(id -> taskMapper.findById(id).orElseThrow(() -> new IllegalStateException(
                        "刚领取的任务读不回来：id=" + id)))
                .toList();
    }

    /**
     * 读取任务对应的文档。
     *
     * @param documentId 文档自增主键
     * @return 文档；行已物理删除时为空
     */
    @Transactional(readOnly = true)
    public Optional<WorkspaceDocument> load(long documentId) {
        return documentMapper.findByIdForProcessing(documentId);
    }

    /**
     * 把文档标记为解析中。
     *
     * <p>独立的一次事务：它必须在解析开始<b>之前</b>就提交，否则其他事务
     * （也就是用户刷新的那个请求）在整个解析期间都会看到旧状态 ——
     * 于是"正在解析"这件事对用户永远不可见，进度条只会从 0 直接跳到 100。
     *
     * @param documentId 文档自增主键
     */
    @Transactional
    public void beginProcessing(long documentId) {
        documentMapper.markProcessing(documentId, clock.instant());
    }

    /**
     * 写入解析结果并把任务标记成功。
     *
     * <h2>四件事必须在同一个事务里</h2>
     * 清掉旧分块、写入新分块、更新文档状态、标记任务成功 —— 少提交任何一件，
     * 都会留下一个自相矛盾的状态（例如分块已就位而文档仍是 PENDING，
     * 那些分块对检索可见，而文档看起来还没准备好）。
     *
     * <h2>租约丢失时必须整体回滚</h2>
     * 若 {@code markSucceeded} 命中 0 行，说明本 worker 的租约已经被回收、
     * 任务可能已被另一个 worker 接管。此时本次结果必须<b>整体丢弃</b>
     * （包括刚写入的分块），否则两个 worker 会互相覆盖结果，
     * 而最终落库的那一份来自谁是不确定的。
     *
     * @param task    任务
     * @param outcome 解析与切分的结果
     * @throws LeaseLostException 租约已失效，本次结果被丢弃
     */
    @Transactional
    public void completeParse(DocumentTask task, ParseOutcome outcome) {
        long documentId = task.documentId();
        List<ChunkDraft> chunks = outcome.chunks();

        // 先清后写：重试时旧分块若不删，会撞 uk_document_chunk_ordinal，
        // 于是"第一次失败之后就永远失败"。这是重试能成立的先决条件。
        chunkMapper.deleteByDocument(documentId);
        if (!chunks.isEmpty()) {
            chunkMapper.insertBatch(chunks);
        }

        documentMapper.markReady(documentId,
                outcome.parsed().textLength(),
                chunks.size(),
                outcome.parsed().parserVersion(),
                clock.instant());

        int updated = taskMapper.markSucceeded(task.id(), task.leaseOwner(), clock.instant());
        if (updated != 1) {
            throw new LeaseLostException(task.id());
        }
    }

    /**
     * 清理完成：清掉分块、置空存储键，并把任务标记成功。
     *
     * <h2>顺序不能颠倒</h2>
     * 先删字节、再置空键。反过来的话，一旦置空成功而删除失败，
     * 库里就再也没有"该删哪个对象"的信息 —— 那份字节永久留在存储上，
     * 而所有记录都显示清理已完成。当前顺序下最坏的结果是"字节已删、键还在"，
     * 而重试会在一个已经不存在的对象上再来一次删除（幂等，无害）。
     *
     * <h2>为什么分块也在这里删</h2>
     * 文档的删除是软删除，而行还在，因此外键的 {@code ON DELETE CASCADE} 不会触发 ——
     * 分块必须被显式清掉。留着它们的后果是：一份"已删除"的文档，它的内容仍然
     * 完整地躺在分块表里，而"读不到"这件事只能靠每个查询都记得 JOIN 一次
     * {@code document} 并过滤 {@code deleted_at} 来保证。
     * <b>把一处遗漏的后果交给调用方去记得，不是一个可接受的设计</b>；
     * 在这里删掉，让"删除"在数据上就是完整的。
     *
     * @param task 任务
     * @throws LeaseLostException 租约已失效
     */
    @Transactional
    public void completeCleanup(DocumentTask task) {
        chunkMapper.deleteByDocument(task.documentId());
        documentMapper.clearStorageKey(task.documentId());
        int updated = taskMapper.markSucceeded(task.id(), task.leaseOwner(), clock.instant());
        if (updated != 1) {
            throw new LeaseLostException(task.id());
        }
    }

    /**
     * 任务没有工作可做，直接标记成功。
     *
     * <h2>为什么这不是"失败"</h2>
     * 典型的三种情形：文档已被用户删除、字节已被清理、任务对应的行已经不存在。
     * 这三种情况下任务的目标都<b>已经不可能达成</b>，而重试不会改变任何事 ——
     * 把它标成失败会让用户在一个自己删掉的文档上看到一个失败提示，
     * 也会让"失败任务"这个指标失去意义（它会被这类正常情况填满）。
     *
     * @param task   任务
     * @param reason 原因，仅写入日志
     * @throws LeaseLostException 租约已失效
     */
    @Transactional
    public void abandon(DocumentTask task, String reason) {
        int updated = taskMapper.markSucceeded(task.id(), task.leaseOwner(), clock.instant());
        if (updated != 1) {
            throw new LeaseLostException(task.id());
        }
        log.info("任务无需执行，已标记完成：taskId={} type={} reason={}",
                task.id(), task.taskType(), reason);
    }

    /**
     * 记录一次失败：要么重排重试，要么终止。
     *
     * <h2>两个判据缺一不可</h2>
     * <ul>
     *   <li>{@code cause.retryable()}：这个失败<b>重试有没有可能不同</b>。
     *       文件损坏、加密、页数超限都是不可重试的 —— 同一份字节重跑一百次
     *       都是同一个结果，而每一次都要把整份文件读完。</li>
     *   <li>{@code task.canRetry()}：还剩几次机会。</li>
     * </ul>
     * 只看后者（"失败就重试 N 次"）是最常见的实现，也是代价最大的一个：
     * 一份 200 MB 的损坏 PDF 会被完整重读三次，用户等到超时才被告知"文件损坏"。
     *
     * @param task       任务
     * @param cause      失败原因
     * @param logMessage 写入 {@code last_error} 的内部描述
     */
    @Transactional
    public void fail(DocumentTask task, DocumentParseException cause, String logMessage) {
        Instant now = clock.instant();
        if (cause.retryable() && task.canRetry()) {
            Instant nextAttemptAt = now.plus(backoffOf(task.attemptCount()));
            documentMapper.markAwaitingRetry(task.documentId());
            int updated = taskMapper.reschedule(task.id(), task.leaseOwner(),
                    nextAttemptAt, logMessage, now);
            if (updated != 1) {
                throw new LeaseLostException(task.id());
            }
            log.warn("任务失败，已安排重试：taskId={} attempt={}/{} next={} reason={}",
                    task.id(), task.attemptCount(), task.maxAttempts(), nextAttemptAt, logMessage);
            return;
        }

        documentMapper.markFailed(task.documentId(), cause.userMessage());
        int updated = taskMapper.markFailed(task.id(), task.leaseOwner(), logMessage, now);
        if (updated != 1) {
            throw new LeaseLostException(task.id());
        }
        log.error("任务最终失败：taskId={} attempt={}/{} retryable={} reason={}",
                task.id(), task.attemptCount(), task.maxAttempts(), cause.retryable(), logMessage);
    }

    /**
     * 计算退避时长。
     *
     * <p>指数退避：第 n 次失败后等待 {@code backoffSeconds × 2^(n-1)}，并封顶。
     * 封顶是必要的 —— 若上限够大，第 20 次失败会推出一年之后，而那时
     * 任务实际上再也不会被处理，它只是从"失败"变成了"被遗忘"。
     *
     * @param attemptCount 已尝试次数
     * @return 退避时长
     */
    private Duration backoffOf(int attemptCount) {
        long base = properties.worker().backoffSeconds();
        int shift = Math.max(0, Math.min(attemptCount - 1, 20));
        long seconds = Math.min(base << shift, MAX_BACKOFF_SECONDS);
        return Duration.ofSeconds(seconds);
    }

    /**
     * 租约已失效。
     *
     * <h2>为什么它必须是一个异常</h2>
     * 它唯一的用途是触发事务回滚 —— 把一个"不该提交的结果"撤回。
     * 返回一个布尔值做不到这件事（调用方可能忘了检查），
     * 而在方法内部直接返回则会留下已经写入的数据。
     */
    public static class LeaseLostException extends RuntimeException {

        /** 序列化兼容标识。异常本身不建议序列化。 */
        private static final long serialVersionUID = 1L;

        /**
         * 构造异常。
         *
         * @param taskId 任务主键
         */
        public LeaseLostException(long taskId) {
            super("任务租约已失效，本次结果被丢弃：taskId=" + taskId);
        }
    }
}
