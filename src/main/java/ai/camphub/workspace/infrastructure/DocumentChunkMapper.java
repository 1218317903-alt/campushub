package ai.camphub.workspace.infrastructure;

import ai.camphub.workspace.domain.ChunkDraft;
import ai.camphub.workspace.domain.DocumentChunk;
import ai.camphub.workspace.domain.ScopedTable;
import ai.camphub.workspace.domain.Unscoped;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * {@code document_chunk} 表访问接口。SQL 定义在
 * {@code resources/mapper/workspace/DocumentChunkMapper.xml}。
 *
 * <h2>这张表带 {@code workspace_id}，因此读取路径可以接受第三层防线</h2>
 * 与 {@code document_task} 相反：分块表的<b>读取</b>查询都标 {@link ScopedTable}。
 * 那份 {@code workspace_id} 是刻意冗余出来的（文档不跨空间移动，因此不会失同步），
 * 目的就是让这道防线能直接压在这张表上 ——
 * 将来 Phase 07 做"空间内检索"时，那条查询不需要 JOIN 一次 {@code document}
 * 才知道自己能看哪些行，而"忘了 JOIN"是一个不会报错的疏漏。
 *
 * <h2>但写入路径不能过滤，而且这一点曾经写错过</h2>
 * {@link #deleteByDocument} 的唯一调用者是解析 worker：重跑解析前先清旧块。
 * 它在<b>没有调用者身份</b>的线程里运行，因此那一次标注 {@code @ScopedTable}
 * 是错的 —— 授权集合为空时 SQL 会被改写成 {@code 1 = 0}，
 * 删除<b>命中 0 行且不报错</b>。后果不是"旧块没删"，而是接下来插入新块时撞上
 * {@code uk_document_chunk_ordinal}，于是每次重新解析都必然失败，
 * 而失败信息指向唯一键冲突，与真正的原因（这一层防线在 worker 里没有身份可用）
 * 相距很远。
 *
 * <p>它现在标 {@link Unscoped}：worker 只处理任务表里已经存在的任务，
 * 而任务只能由已经过三层防线的请求路径创建 —— 与
 * {@code DocumentMapper#findByIdForProcessing} 是同一套论据。
 */
public interface DocumentChunkMapper {

    /**
     * 批量插入分块。
     *
     * <h2>为什么调用方必须自己保证列表非空</h2>
     * MyBatis 的 {@code <foreach>} 在空集合上会生成
     * {@code INSERT INTO ... VALUES}（后面什么都不跟）—— 一条语法错误的 SQL。
     * 这个失败是明确的（报错而不是静默写错数据），但把判断留在调用方更简单：
     * "没有任何分块"在业务上是一个正常结果（扫描件 PDF），
     * 而不是一次需要靠捕获 SQL 异常来处理的边缘情况。
     *
     * <p>INSERT 不标注任何空间过滤注解 —— 它会被拦截器原样放行，
     * 标注只会制造"已被保护"的错觉。安全性来自另一处：
     * {@code workspace_id} 由服务端从文档行取，而不是从请求参数来。
     *
     * @param chunks 待插入的分块，必须非空
     * @return 影响行数
     */
    int insertBatch(@Param("chunks") List<ChunkDraft> chunks);

    /**
     * 删除某个文档的全部分块。
     *
     * <h2>它是重试幂等的工具，也是删除路径的一部分</h2>
     * 两个调用者，都在 worker 线程里：
     * <ul>
     *   <li><b>重跑解析之前先清旧块</b>，再插入新块。少了这一步，重试会在
     *       {@code uk_document_chunk_ordinal} 上撞唯一键 —— 于是"重试"变成
     *       "第一次失败之后永远失败"，而那正是重试机制要解决的问题本身。</li>
     *   <li><b>文档被删除后的清理</b>（CLEANUP 任务）。软删除不会触发外键的级联，
     *       因此分块必须被显式清掉，否则一份"已删除"的文档的内容仍然完整地
     *       躺在表里。</li>
     * </ul>
     *
     * <p>标 {@link Unscoped} 而不是 {@link ScopedTable}：这两个调用都发生在
     * 没有调用者身份的后台线程里，而授权集合为空时这条 DELETE 会被改写成
     * {@code 1 = 0} —— <b>命中 0 行且不报错</b>，然后表现为唯一键冲突。
     * 详见接口注释。
     *
     * @param documentId 文档自增主键
     * @return 影响行数
     */
    @Unscoped(reason = "worker 以系统身份清理分块（重跑解析前清旧块、文档删除后清残留），"
            + "调用发生在没有调用者身份的后台线程；任务行只提供 document_id，"
            + "而任务只能由已经过三层防线的请求路径创建")
    int deleteByDocument(@Param("documentId") long documentId);

    /**
     * 统计某个文档的分块数。
     *
     * @param documentId 文档自增主键
     * @return 条数
     */
    @ScopedTable
    long countByDocument(@Param("documentId") long documentId);

    /**
     * 分页读取某个文档的分块。
     *
     * <p>用途是<b>让人能核对解析质量</b>：一份 PDF 抽出的是不是它该有的内容、
     * 标题路径是否合理，只有把分块拿出来看才能回答。没有这条查询，
     * "解析成功"就只是一个没人验证过的状态位。
     *
     * @param documentId 文档自增主键
     * @param limit      页大小
     * @param offset     偏移
     * @return 分块，按序号升序
     */
    @ScopedTable
    List<DocumentChunk> findByDocument(@Param("documentId") long documentId,
                                       @Param("limit") int limit,
                                       @Param("offset") long offset);
}
