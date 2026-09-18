package ai.camphub.workspace.infrastructure;

import ai.camphub.workspace.domain.DocumentTask;
import ai.camphub.workspace.domain.DocumentTaskType;
import ai.camphub.workspace.domain.Unscoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code document_task} 表访问接口（异步任务队列）。SQL 定义在
 * {@code resources/mapper/workspace/DocumentTaskMapper.xml}。
 *
 * <h2>为什么这张表的每个方法都标 {@link Unscoped}</h2>
 * 这张表<b>没有 {@code workspace_id}</b>，因此第三层防线无法作用于它 ——
 * 而这正是刻意的设计（理由见 {@code V6__document_pipeline.sql}）：它的读取方只有
 * worker，而 worker 以<b>系统身份</b>跨空间领取待办任务。
 *
 * <p>给它加一个 workspace_id 再让 worker 绕过过滤，只会多一列无人使用的数据；
 * 真正约束"worker 能碰哪些任务"的是任务行自己的租约（{@code lease_owner} +
 * {@code lease_expires_at}），而不是调用者的空间范围。worker 不是 HTTP 入口，
 * 它没有"当前用户"这个概念 —— 给它套一层空间过滤只会让它在有多个空间待办时
 * 什么都领不到，而那是一个"看似安全、实际让功能瘫痪"的改动。
 *
 * <p>每个方法都显式标注 {@link Unscoped} 并写下这条理由，由
 * {@code WorkspaceScopeCoverageTest} 在构建期强制 —— 漏标即构建失败。
 *
 * <h2>三个写入方法为什么要带 leaseOwner</h2>
 * 租约可能在工作途中过期，而另一个 worker 会回收这个任务并<b>重新执行它</b>。
 * 若原持有者此时才写下结果，两件事会同时发生：一份重复写入的结果，
 * 以及一个"已经被别人接管"的任务被标成完成。
 *
 * <p>带上 {@code lease_owner} 条件之后，原持有者的写入会命中 0 行 ——
 * 调用方据此知道自己的租约已经失效，应当丢弃这次的结果而不是提交它。
 * 这是"同一任务被并发执行时，只有一方能落地"的唯一保证。
 */
public interface DocumentTaskMapper {

    /**
     * 入队一个任务，已存在则不做任何改变。
     *
     * <h2>幂等落在这条语句上</h2>
     * {@code ON DUPLICATE KEY UPDATE} 后面写的是一个自赋值，因此冲突时
     * <b>没有任何列被真正修改</b>（MySQL 也因此不会触发 {@code ON UPDATE}
     * 的时间戳更新）。这比 {@code INSERT IGNORE} 更可取：
     * 后者会连同外键违反、值超长这类真实错误一起吞掉。
     *
     * <p>于是"重复上传导致重复入队"与"应用层重入"都不会产生第二个解析任务，
     * 而"幂等"这件事不再只是应用层一句无法被验证的承诺。
     *
     * @param documentId  文档自增主键
     * @param taskType    任务类型
     * @param maxAttempts 重试上限
     * @return 影响行数；插入成功为 1，已存在为 0
     */
    int insertIfAbsent(@Param("documentId") long documentId,
                       @Param("taskType") DocumentTaskType taskType,
                       @Param("maxAttempts") int maxAttempts);

    /**
     * 锁定一批可派发的任务。
     *
     * <h2>{@code FOR UPDATE SKIP LOCKED} 是这套队列的核心</h2>
     * 它让多个 worker 实例可以并发领取而互不阻塞：已被人锁住的行被<b>跳过</b>，
     * 而不是排队等待。没有 {@code SKIP LOCKED}，并发的 worker 会串行化在
     * 同一批行上 —— 那时"加实例"不会提高吞吐，只会增加锁等待。
     *
     * <p>调用方<b>必须在同一个事务里</b>紧接着执行 {@link #markRunning}：
     * 锁只在事务期间有效，事务一结束，锁定的行就会回到普通状态，
     * 而那时它们的状态还是 PENDING —— 于是另一个 worker 会领到同一批任务。
     *
     * @param limit 最多锁定几条
     * @param now   当前时间；早于或等于它的 PENDING 任务才可派发
     * @return 被锁定的任务
     */
    @Unscoped(reason = "worker 以系统身份跨空间领取任务；任务的安全性由租约约束，"
            + "不由调用者的空间范围约束")
    List<DocumentTask> lockDispatchable(@Param("limit") int limit, @Param("now") Instant now);

    /**
     * 把锁定的任务置为运行中并写入租约。
     *
     * <h2>为什么 {@code WHERE} 里还要再写一遍 {@code status = 'PENDING'}</h2>
     * 这条语句的输入来自同一次事务里刚锁定的行，因此状态理应仍是 PENDING。
     * 但"理应"不是一种可以依赖的性质：如果锁的范围写错（例如 limit 与 id 集合
     * 不是同一批），这一条会在别人正在跑的任务上再加一次 {@code attempt_count}。
     * 带上这个条件之后，那种错误的影响是"影响行数少于预期"，
     * 而调用方对比行数就能发现它，而不是安静地重复执行。
     *
     * @param ids            锁定的任务主键
     * @param leaseOwner     worker 标识
     * @param leaseExpiresAt 租约到期时间
     * @param now            当前时间，写入 {@code started_at}
     * @return 影响行数；应当等于 {@code ids.size()}
     */
    @Unscoped(reason = "worker 以系统身份跨空间领取任务；任务的安全性由租约约束，"
            + "不由调用者的空间范围约束")
    int markRunning(@Param("ids") List<Long> ids,
                    @Param("leaseOwner") String leaseOwner,
                    @Param("leaseExpiresAt") Instant leaseExpiresAt,
                    @Param("now") Instant now);

    /**
     * 回收租约已过期的运行中任务，放回队列。
     *
     * <h2>崩溃恢复只有这一条路径</h2>
     * 进程被 kill 时，它领走的任务会永远停在 RUNNING —— 没有任何代码会去改它。
     * 恢复的依据只有"租约到期"这一个事实，因此不需要任何组件记住
     * 上一位持有者的状态。这也意味着<b>本方法必须由每个实例都定期执行</b>：
     * 死掉的那个实例自己不会再执行它。
     *
     * @param now 当前时间
     * @return 被回收的任务数
     */
    @Unscoped(reason = "worker 以系统身份跨空间回收任务；回收依据是租约到期，"
            + "与调用者的空间范围无关")
    int recoverExpiredLeases(@Param("now") Instant now);

    /**
     * 标记任务成功。
     *
     * @param id         任务主键
     * @param leaseOwner 当前持有者；租约已被别人接管时这条更新会命中 0 行
     * @param now        当前时间
     * @return 影响行数；0 表示租约已失效，调用方应丢弃本次结果
     */
    @Unscoped(reason = "worker 以系统身份跨空间完成任务；任务的安全性由租约约束，"
            + "不由调用者的空间范围约束")
    int markSucceeded(@Param("id") long id,
                      @Param("leaseOwner") String leaseOwner,
                      @Param("now") Instant now);

    /**
     * 标记任务最终失败（已用尽重试次数）。
     *
     * @param id         任务主键
     * @param leaseOwner 当前持有者
     * @param lastError  失败原因
     * @param now        当前时间
     * @return 影响行数；0 表示租约已失效
     */
    @Unscoped(reason = "worker 以系统身份跨空间完成任务；任务的安全性由租约约束，"
            + "不由调用者的空间范围约束")
    int markFailed(@Param("id") long id,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("lastError") String lastError,
                   @Param("now") Instant now);

    /**
     * 把任务放回队列，推迟到指定时间才可再次派发。
     *
     * <p>退避就落在 {@code next_attempt_at} 上：它既是"什么时候可以重试"，
     * 也是 {@link #lockDispatchable} 的筛选条件，因此不需要额外的调度器。
     *
     * @param id            任务主键
     * @param leaseOwner    当前持有者
     * @param nextAttemptAt 下次可见时间
     * @param lastError     本次失败原因
     * @param now           当前时间
     * @return 影响行数；0 表示租约已失效
     */
    @Unscoped(reason = "worker 以系统身份跨空间重排任务；任务的安全性由租约约束，"
            + "不由调用者的空间范围约束")
    int reschedule(@Param("id") long id,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("nextAttemptAt") Instant nextAttemptAt,
                   @Param("lastError") String lastError,
                   @Param("now") Instant now);

    /**
     * 人工重置任务：清零重试计数并立即回到可派发状态。
     *
     * <h2>为什么重置而不是新建一行</h2>
     * {@code uk_document_task_document_type} 只允许同一文档的同类任务存在一行。
     * 新建会撞唯一键，而"插入失败"显然不是重试的正确表达。
     * 重置同一行的另一个好处是历史可见：{@code started_at} 与 {@code last_error}
     * 会被下一次执行覆盖，但任务的创建时间保留着，能看出"这份文档被处理了多久"。
     *
     * <p>状态条件限定为 {@code SUCCEEDED} 与 {@code FAILED}：一个正在排队的任务
     * 不需要重置（它马上就要跑），而一个正在跑的任务被重置会让它结束后
     * 又一次执行（同一份字节解析两遍）。
     *
     * @param documentId 文档自增主键
     * @param taskType   任务类型
     * @param now        当前时间
     * @return 影响行数；为 0 表示任务不存在或正处于不该被打断的状态
     */
    @Unscoped(reason = "worker 侧的任务重置由文档路径触发（已通过三层防线），"
            + "而本表无 workspace_id 可供自动过滤")
    int resetForRetry(@Param("documentId") long documentId,
                      @Param("taskType") DocumentTaskType taskType,
                      @Param("now") Instant now);

    /**
     * 按主键重读任务。
     *
     * <h2>为什么领取之后必须重读一次</h2>
     * {@code lockDispatchable} 返回的是领取<b>之前</b>的快照，而
     * {@code markRunning} 会把 {@code attempt_count} 加一、写入租约与开始时间。
     * 执行方需要准确的 {@code attempt_count} 才能回答"这是最后一次尝试吗" ——
     * 那个问题的答案决定失败后是重排还是终止，而用旧值判断会让任务
     * 比配置的次数多跑一轮或少跑一轮。
     *
     * @param id 任务主键
     * @return 任务；不存在时为空
     */
    @Unscoped(reason = "worker 领取后按主键重读任务；调用发生在没有调用者身份的后台线程，"
            + "且任务行已被本次领取的租约持有")
    Optional<DocumentTask> findById(@Param("id") long id);

    /**
     * 查询某个文档的某类任务。
     *
     * @param documentId 文档自增主键
     * @param taskType   任务类型
     * @return 任务；不存在时为空
     */
    @Unscoped(reason = "worker 与诊断路径按 (document_id, task_type) 定位任务；"
            + "本表无 workspace_id，且调用方已经过文档层的授权判定")
    Optional<DocumentTask> find(@Param("documentId") long documentId,
                                @Param("taskType") DocumentTaskType taskType);

    /**
     * 统计某状态的任务数，用于观测队列积压。
     *
     * <p>它存在的原因是具体的：数据库队列相对 MQ 的主要缺点是"看不见积压"，
     * 而 Phase 09 的性能工程需要这个数字来判断轮询间隔是否够用、
     * 是否需要引入真正的消息中间件。因此它不是装饰性的指标。
     *
     * @param status 状态名
     * @return 条数
     */
    @Unscoped(reason = "队列观测是全局指标，不带调用者身份，也无法按空间有意义地切分")
    long countByStatus(@Param("status") String status);
}
