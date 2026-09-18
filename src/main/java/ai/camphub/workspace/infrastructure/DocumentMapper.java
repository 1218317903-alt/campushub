package ai.camphub.workspace.infrastructure;

import ai.camphub.workspace.domain.DocumentDraft;
import ai.camphub.workspace.domain.ScopedTable;
import ai.camphub.workspace.domain.WorkspaceDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code document} 表访问接口。SQL 定义在
 * {@code resources/mapper/workspace/DocumentMapper.xml}。
 *
 * <h2>元数据与字节是两条链路，因此存储调用不在这里</h2>
 * 本阶段（Phase 04）已经落地了本地磁盘存储，上传与下载都能真正跑通；
 * 但那份实现属于 {@code infrastructure.storage}，通过
 * {@code ObjectStorage} 端口被应用层使用 —— <b>不经过 Mapper</b>。
 *
 * <p>这不是形式上的分层洁癖。把存储调用写进 Mapper 会让"这次调用会不会访问外部系统"
 * 变成每个方法都要重新回答的问题：数据库事务不会回滚磁盘写入，
 * 而一个被 {@code @Transactional} 包着的读写方法一旦同时改数据库与磁盘，
 * 它的失败语义（谁先回滚、孤儿怎么清）就无法从方法签名看出来。
 * 两条链路各自可见，是让那种复杂度停留在应用层能看到的地方。
 *
 * <h2>{@link #softDelete} 是第三层防线的真实用例</h2>
 * 它按主键定位，语句里没有 {@code workspace_id}。若哪天有人在调用它之前
 * 漏掉了"这份文档属于哪个空间"的判定，第三层防线追加的
 * {@code AND workspace_id IN (...)} 就是唯一还能拦住它的东西。
 * 这不是假想的风险：按主键删除是最容易被复制粘贴到别处的写法。
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
}
