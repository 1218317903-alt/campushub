package ai.camphub.workspace.app;

import ai.camphub.workspace.config.WorkspaceProperties;
import ai.camphub.workspace.domain.DocumentTask;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文档处理任务的轮询 worker。
 *
 * <h2>为什么是"轮询"而不是消息中间件</h2>
 * Phase 05 的计划明确"暂不强制 MQ，先找出现有异步方案的瓶颈"。
 * 轮询的代价是可量化的：一个任务从入队到开始处理，平均要等半个轮询间隔。
 * 这个数字被 {@code app.workspace.worker.poll-interval-ms} 直接决定，
 * 因此它是一个<b>可以调的旋钮</b>，而不是一个只能抱怨的缺陷。
 *
 * <p>换 MQ 的触发条件是明确的：当轮询造成的延迟成为用户可感知的问题，
 * 或者当"每次轮询都要查一次库"的开销在实例数增长后变得显著。
 * 那时会有压测数据支撑这个决定 —— 而不是现在凭"MQ 更专业"来选。
 *
 * <h2>这个类只做三件事</h2>
 * 回收过期租约、领取一批、逐个交给执行器。它不碰事务，也不碰业务规则 ——
 * 那些分别在 {@link DocumentTaskRunner} 与 {@link DocumentTaskProcessor} 里。
 * 保持它这么薄的理由是：定时任务是最难被单元测试覆盖的一层，
 * 因此逻辑越少越好，而所有值得验证的逻辑都放在可以被直接调用的类里。
 *
 * <h2>整轮包在 try/catch 里</h2>
 * {@code @Scheduled} 的方法抛出异常时，Spring 默认只记一条日志、然后继续下一次调度 ——
 * 但异常会让本轮剩余任务全部跳过。显式捕获并记完整堆栈，
 * 保证"数据库抖了一下"不会让这一轮的任务静默消失。
 */
@Component
public class DocumentTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentTaskWorker.class);

    private final DocumentTaskRunner runner;
    private final DocumentTaskProcessor processor;
    private final WorkspaceProperties properties;

    /**
     * 本实例的标识，写入任务租约。
     *
     * <p>它必须能区分同一台机器上的不同进程（滚动重启时新旧进程会短暂共存），
     * 因此带上 PID。只写主机名会让"两个进程同时认为自己持有同一个任务"
     * 在日志里看起来像是同一个人在重复劳动。
     */
    private final String leaseOwner;

    /**
     * 构造注入。
     *
     * @param runner     事务边界
     * @param processor  单任务执行
     * @param properties 空间模块配置
     */
    public DocumentTaskWorker(DocumentTaskRunner runner,
                              DocumentTaskProcessor processor,
                              WorkspaceProperties properties) {
        this.runner = runner;
        this.processor = processor;
        this.properties = properties;
        this.leaseOwner = leaseOwnerOf();
    }

    /**
     * 轮询一次。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：前者在上一次执行<b>结束</b>后
     * 再等一个间隔，因此一次慢处理不会让它后面堆积起一串要补跑的调度；
     * 后者按固定频率触发，慢处理会让任务排队等待调度线程 ——
     * 而那正是"为什么日志里有一堆同时开始的任务"的成因。
     */
    @Scheduled(fixedDelayString = "${app.workspace.worker.poll-interval-ms}")
    public void poll() {
        if (!properties.worker().enabled()) {
            // 测试环境关掉它，好让"哪一次解析真的发生了"完全由测试决定。
            return;
        }
        try {
            // 回收放在领取之前：一个任务可能因为上一位持有者崩溃而留在 RUNNING，
            // 先回收它，本轮就能立刻重新派发，而不必再等一个轮询周期。
            runner.recoverExpiredLeases();

            List<DocumentTask> tasks = runner.claim(properties.worker().batchSize(), leaseOwner);
            for (DocumentTask task : tasks) {
                // 逐个处理，且每个任务自己吞掉可预期的异常 ——
                // 一个任务失败不应该让同一批里的其余任务全部被跳过。
                processor.process(task);
            }
        } catch (DocumentTaskRunner.LeaseLostException ex) {
            // 已在 processor 层处理过；这里只是兜底（claim 阶段也可能抛）。
            log.warn("任务租约失效：{}", ex.getMessage());
        } catch (Exception ex) {
            log.error("任务轮询失败，本轮跳过", ex);
        }
    }

    /**
     * 生成本实例标识。
     *
     * @return 主机名 + 进程号；取不到主机名时退化为进程号
     */
    private static String leaseOwnerOf() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            // 取不到主机名不影响运行，只是租约标识少一段信息。
            log.debug("取主机名失败，租约标识只使用进程号", ex);
            host = "unknown-host";
        }
        return host + "-" + ProcessHandle.current().pid();
    }
}
