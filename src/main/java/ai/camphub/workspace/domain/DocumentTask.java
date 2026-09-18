package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 文档处理任务（数据库队列中的一行）。
 *
 * <h2>为什么没有 publicId</h2>
 * 本表其余实体（{@code workspace} / {@code document} / {@code note}）都有对外标识，
 * 因为它们会被客户端按标识寻址。任务不会：<b>用户从来不需要知道任务的存在</b>，
 * 他看到的是文档的 {@code parse_status} —— 那是任务在文档上的投影。
 * 给一个不对外暴露的东西加上防遍历标识，只会让"这个字段为什么存在"变成需要解释的问题。
 *
 * <p>任务的对外表达因此只有一处：文档的状态。这也意味着任务表可以自由重构
 * （换列、换实现、将来换成真正的 MQ），只要那份投影的语义不变，对外契约就不变。
 *
 * @param id             自增主键
 * @param documentId     所属文档自增主键
 * @param taskType       任务类型
 * @param status         当前状态
 * @param attemptCount   已尝试次数，包含正在进行的这一次
 * @param maxAttempts    重试上限；达到后停在 {@link DocumentTaskStatus#FAILED}
 * @param nextAttemptAt  下次可见时间；未到时间的 PENDING 任务不会被领取
 * @param leaseOwner     持有租约的 worker 标识，未运行时为 null
 * @param leaseExpiresAt 租约到期时间，未运行时为 null
 * @param lastError      最近一次失败原因，面向运维，不直接展示给用户
 * @param startedAt      最近一次开始执行的时间
 * @param finishedAt     最近一次进入终态的时间
 * @param createdAt      创建时间
 * @param updatedAt      最后修改时间
 */
public record DocumentTask(
        long id,
        long documentId,
        DocumentTaskType taskType,
        DocumentTaskStatus status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        String lastError,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 本次尝试之后是否还有重试余量。
     *
     * <p>判定放在这里而不是让调用方各写一遍 {@code attemptCount < maxAttempts}：
     * 那个表达式看着简单，但它是一个**策略**（"达到上限就不再自动重试"），
     * 而策略在两个地方各写一遍，就迟早只在一处被改 ——
     * 而这次改动恰好就是"为什么不重试了"这一类难查的问题。
     *
     * @return 还能再试一次时为 true
     */
    public boolean canRetry() {
        return attemptCount < maxAttempts;
    }
}
