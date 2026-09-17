package ai.camphub.platform.audit.infrastructure;

import ai.camphub.platform.audit.domain.AuditEntry;
import org.apache.ibatis.annotations.Param;

/**
 * {@code audit_log} 表的写入接口。
 *
 * <p>本阶段<b>刻意只提供写入</b>，不提供查询。原因：审计日志的读取面属于后台能力
 * （Phase 10 的 Admin），那时才需要分页、按动作/时间/操作者过滤等查询语义。
 * 现在先写一个"看起来完整"的查询接口，只会得到一批没有真实调用者、
 * 因而也没有被真实需求打磨过的代码。
 */
public interface AuditLogMapper {

    /**
     * 追加一条审计记录。
     *
     * @param entry 审计记录
     * @return 影响行数
     */
    int insert(@Param("entry") AuditEntry entry);
}
