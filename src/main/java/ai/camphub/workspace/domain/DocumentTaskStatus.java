package ai.camphub.workspace.domain;

/**
 * 文档任务状态。
 *
 * <h2>为什么是四个状态而不是三个</h2>
 * {@code PENDING → SUCCEEDED / FAILED} 三个状态看起来已经够用，
 * 但它有一个无法表达的情形：<b>任务已经被某个 worker 领走、正在执行</b>。
 * 没有 RUNNING 时，"正在跑"与"还在排队"共用 PENDING，
 * 于是一个卡死的 worker 与一个满满当当的队列在数据库里长得一模一样 ——
 * 排查者无法区分"没人领"和"领了但没跑完"。
 *
 * <p>RUNNING 同时是崩溃恢复的锚点：只有处于 RUNNING 的任务才持有租约，
 * 因此也只有它们会在租约过期时被回收（见 {@code DocumentTask#leaseExpiresAt}）。
 * 回收一个 PENDING 任务是没意义的 —— 它本来就在队列里等着。
 */
public enum DocumentTaskStatus {

    /** 等待被领取。{@code next_attempt_at} 到达后才可见（失败重试的退避就落在这里）。 */
    PENDING,

    /** 已被某个 worker 领取并持有租约。 */
    RUNNING,

    /** 成功。终态，不再变动。 */
    SUCCEEDED,

    /**
     * 失败且已用尽重试次数。终态，只能由人工重新入队（用户点"重试"）打破。
     *
     * <p>与"失败但还会再试一次"共用 FAILED 会让两者的含义混在一起：
     * 界面上无法回答"这个失败是暂时的还是最终的"，而用户据此决定要不要手动重试。
     * 当前这次尝试失败但还有余量时，任务回到 PENDING 并抬高 {@code next_attempt_at}，
     * 因此<b>不存在</b>"FAILED 但还会自动重试"这种中间态。
     */
    FAILED
}
