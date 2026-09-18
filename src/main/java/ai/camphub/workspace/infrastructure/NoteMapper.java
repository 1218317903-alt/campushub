package ai.camphub.workspace.infrastructure;

import ai.camphub.workspace.domain.NoteDetail;
import ai.camphub.workspace.domain.NoteDraft;
import ai.camphub.workspace.domain.NoteSummary;
import ai.camphub.workspace.domain.ScopedTable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code note} 表访问接口。SQL 定义在 {@code resources/mapper/workspace/NoteMapper.xml}。
 *
 * <h2>全部方法都接受自动空间过滤</h2>
 * 这是本模块里唯一一张"没有例外"的表，因此它也是解释第三层防线在防什么的例子：
 * <ul>
 *   <li>{@link #softDelete} 形如 {@code WHERE id = ?} —— 没有 workspace_id 谓词。
 *       若调用方在别处漏掉了"这条笔记属于哪个空间"的判定，一条按主键的删除
 *       就会删掉任何空间里的任何笔记。第三层防线在这里追加的条件是这道操作的
 *       最后一道闸门。</li>
 *   <li>{@link #update} 按 (workspace_id, public_id) 定位，已经足够紧，
 *       追加的条件是重复的 —— 仍然保留，为的是让这一层的行为不依赖
 *       "作者这次有没有写对"。</li>
 * </ul>
 *
 * <h2>为什么不按 public_id 单独查</h2>
 * {@code public_id} 是全局唯一的，技术上可以只按它查。但这样一次查询就丢掉了
 * "这条笔记属于路径里的那个空间吗"这个信息，必须由调用方在 Java 里补一次比对 ——
 * 而任何"必须先比对再使用"的返回值，都存在忘记比对的可能。
 * 把空间条件写进 WHERE，让"取错了空间"在查询层面就不可能发生。
 */
public interface NoteMapper {

    /**
     * 插入笔记。
     *
     * @param note 待插入的笔记
     * @return 影响行数
     */
    int insert(@Param("note") NoteDraft note);

    /**
     * 按 (空间, 对外标识) 查询详情。
     *
     * @param workspaceId 空间自增主键
     * @param publicId    笔记对外标识
     * @return 存在且未被删除时返回
     */
    @ScopedTable
    Optional<NoteDetail> findDetail(@Param("workspaceId") long workspaceId,
                                    @Param("publicId") String publicId);

    /**
     * 分页查询空间内笔记列表。
     *
     * <p>刻意不查 {@code body_md} / {@code body_html}：它们是 {@code MEDIUMTEXT}，
     * 一页 20 条会把几百 KB 正文搬进内存再丢掉（ADR 0005）。
     *
     * @param workspaceId 空间自增主键
     * @param limit       页大小
     * @param offset      偏移，用 {@code long}：页码由客户端控制，
     *                    {@code int} 相乘会在极大页码上溢出成负数
     * @return 列表项，按最后编辑时间倒序
     */
    @ScopedTable
    List<NoteSummary> findSummaries(@Param("workspaceId") long workspaceId,
                                    @Param("limit") int limit,
                                    @Param("offset") long offset);

    /**
     * 统计空间内笔记数。
     *
     * @param workspaceId 空间自增主键
     * @return 条数
     */
    @ScopedTable
    long countByWorkspace(@Param("workspaceId") long workspaceId);

    /**
     * 更新笔记内容。
     *
     * <p>{@code author_id} 不在更新列里 —— 它决定删除权限，被改写等于把别人的笔记过户。
     * 这是刻意把它排除在 SQL 之外，而不是靠调用方不去传。
     *
     * @param note 新的内容（使用其 {@code publicId} / {@code workspaceId} 定位）
     * @return 影响行数
     */
    @ScopedTable
    int update(@Param("note") NoteDraft note);

    /**
     * 软删除笔记。
     *
     * @param id  自增主键
     * @param now 当前时间
     * @return 影响行数
     */
    @ScopedTable
    int softDelete(@Param("id") long id, @Param("now") Instant now);
}
