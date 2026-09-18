package ai.camphub.workspace.infrastructure;

import ai.camphub.workspace.domain.DocumentDraft;
import ai.camphub.workspace.domain.ScopedTable;
import ai.camphub.workspace.domain.Unscoped;
import ai.camphub.workspace.domain.WorkspaceDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code document} 表访问接口。SQL 定义在
 * {@code resources/mapper/workspace/DocumentMapper.xml}。
 *
 * <h2>两类方法，两种身份</h2>
 * 这张表上的方法分成两组，它们的调用者完全不同：
 * <ul>
 *   <li><b>请求路径</b>（{@code findByPublicId} / {@code findByWorkspace} /
 *       {@code countByWorkspace} / {@code softDelete}）：调用者是登录用户，
 *       全部标注 {@link ScopedTable}，接受第三层防线的空间过滤。</li>
 *   <li><b>后台路径</b>（{@code findByIdForProcessing} 与几个 {@code mark*}）：
 *       调用者是异步 worker，它在<b>没有调用者身份</b>的线程里跑 ——
 *       任务表只给它一个 {@code document_id}。这些方法标注 {@link Unscoped}
 *       并写明理由；它们的安全性来自另一处：<b>worker 只处理任务表里已经存在的任务</b>，
 *       而任务只能由请求路径（已经过三层防线）创建。</li>
 * </ul>
 * 把这两组显式分开，是为了让"这条查询究竟代表谁在问"在每一行代码上都可读 ——
 * 而不是靠"反正 worker 是我们自己的代码"这种无法被验证的信任。
 *
 * <h2>元数据与字节是两条链路，因此存储调用不在这里</h2>
 * 存储实现属于 {@code infrastructure.storage}，通过 {@code ObjectStorage} 端口
 * 被应用层使用 —— <b>不经过 Mapper</b>。
 *
 * <p>这不是形式上的分层洁癖。把存储调用写进 Mapper 会让"这次调用会不会访问外部系统"
 * 变成每个方法都要重新回答的问题：数据库事务不会回滚磁盘写入，
 * 而一个被 {@code @Transactional} 包着的读写方法一旦同时改数据库与磁盘，
 * 它的失败语义（谁先回滚、孤儿怎么清）就无法从方法签名看出来。
 */
public interface DocumentMapper {

    /**
     * 插入文档元数据。
     *
     * @param document 待插入的元数据
     * @return 影响行数
     */
    int insert(@Param("document") DocumentDraft document);

    /**
     * 按 (空间, 对外标识) 查询文档元数据。
     *
     * @param workspaceId 空间自增主键
     * @param publicId    文档对外标识
     * @return 存在且未被删除时返回
     */
    @ScopedTable
    Optional<WorkspaceDocument> findByPublicId(@Param("workspaceId") long workspaceId,
                                               @Param("publicId") String publicId);

    /**
     * 分页查询空间内文档列表。
     *
     * @param workspaceId 空间自增主键
     * @param limit       页大小
     * @param offset      偏移，用 {@code long} 避免极大页码溢出
     * @return 列表，按上传时间倒序
     */
    @ScopedTable
    List<WorkspaceDocument> findByWorkspace(@Param("workspaceId") long workspaceId,
                                            @Param("limit") int limit,
                                            @Param("offset") long offset);

    /**
     * 统计空间内文档数。
     *
     * @param workspaceId 空间自增主键
     * @return 条数
     */
    @ScopedTable
    long countByWorkspace(@Param("workspaceId") long workspaceId);

    /**
     * 软删除文档。
     *
     * @param id  自增主键
     * @param now 当前时间
     * @return 影响行数
     */
    @ScopedTable
    int softDelete(@Param("id") long id, @Param("now") Instant now);

    // ------------------------------------------------------------------------
    // 后台路径（worker 以系统身份调用）
    // ------------------------------------------------------------------------

    /**
     * 按主键取文档，<b>不排除已软删除的行</b>。
     *
     * <h2>为什么不过滤 {@code deleted_at}</h2>
     * 两种任务都需要看到已删除的文档：
     * <ul>
     *   <li>{@code CLEANUP}：它的唯一工作就是清理已删除文档的字节 ——
     *       过滤掉删除行会让这个任务永远找不到目标。</li>
     *   <li>{@code PARSE}：用户可能在上传后立刻删除。worker 需要知道
     *       "这份文档已经不在了"，才能跳过解析而不是白跑一遍
     *       （更糟的是：解析完成后把状态改成 READY，而文档已经删除，
     *       那个状态再也没有意义）。</li>
     * </ul>
     *
     * @param id 自增主键
     * @return 文档；不存在时为空
     */
    @Unscoped(reason = "worker 以系统身份按主键取文档，其调用发生在没有调用者身份的后台线程；"
            + "任务行只提供 document_id，且 CLEANUP 必须能看到已软删除的行")
    Optional<WorkspaceDocument> findByIdForProcessing(@Param("id") long id);

    /**
     * 标记为解析中。
     *
     * <p>写入一个非零的起始进度：进度条从 0 跳到 5 的这一步，是"任务已被领走"
     * 这件事唯一的用户可见证据。没有它，一个排队中的文档与一个正在解析的文档
     * 在前端长得完全一样。
     *
     * @param id  自增主键
     * @param now 当前时间
     * @return 影响行数
     */
    @Unscoped(reason = "worker 以系统身份更新解析状态，调用发生在没有调用者身份的后台线程")
    int markProcessing(@Param("id") long id, @Param("now") Instant now);

    /**
     * 标记解析成功。
     *
     * @param id            自增主键
     * @param textLength    抽出的字符数
     * @param chunkCount    分块数
     * @param parserVersion 解析器版本
     * @param parsedAt      完成时间
     * @return 影响行数
     */
    @Unscoped(reason = "worker 以系统身份更新解析结果，调用发生在没有调用者身份的后台线程")
    int markReady(@Param("id") long id,
                  @Param("textLength") int textLength,
                  @Param("chunkCount") int chunkCount,
                  @Param("parserVersion") String parserVersion,
                  @Param("parsedAt") Instant parsedAt);

    /**
     * 标记解析失败。
     *
     * <p>{@code parse_message} 是<b>面向用户</b>的一句话，由
     * {@code DocumentParseException#userMessage()} 提供，不含内部细节。
     * 内部原因写在 {@code document_task.last_error} 里，那是运维看的。
     *
     * @param id           自增主键
     * @param parseMessage 面向用户的失败原因
     * @return 影响行数
     */
    @Unscoped(reason = "worker 以系统身份更新解析状态，调用发生在没有调用者身份的后台线程")
    int markFailed(@Param("id") long id, @Param("parseMessage") String parseMessage);

    /**
     * 把文档放回"等待解析"状态，供重试前使用。
     *
     * <h2>为什么不能让它停在 PROCESSING</h2>
     * 一次失败之后任务会按退避策略重排，中间可能等上几分钟。若文档状态留在
     * {@code PROCESSING}，用户在整段等待里看到的都是"正在解析" ——
     * 而实际上没有任何东西在跑。进度归零并回到 PENDING 才是事实。
     *
     * <p>不清空 {@code parse_message}：上一次失败的原因在重试之前对用户仍有参考价值
     * （"上次失败是因为文件损坏"），而 {@code markReady} 成功时会清掉它。
     *
     * @param id 自增主键
     * @return 影响行数
     */
    @Unscoped(reason = "worker 以系统身份重排解析任务，调用发生在没有调用者身份的后台线程")
    int markAwaitingRetry(@Param("id") long id);

    /**
     * 把存储键置空，表示字节已经从存储上清除。
     *
     * <h2>为什么不在删除请求里顺手置空</h2>
     * 删除请求提交时字节还没被删。若那时就把键置空，一条"字节清理失败"的记录
     * 就再也没有办法重试了 —— 因为没有人知道该删哪个对象。
     * 键保留到清理真正成功为止，是让 CLEANUP 任务可重试的前提。
     *
     * @param id 自增主键
     * @return 影响行数
     */
    @Unscoped(reason = "CLEANUP 任务以系统身份回收已删除文档的字节，"
            + "调用发生在没有调用者身份的后台线程")
    int clearStorageKey(@Param("id") long id);
}
