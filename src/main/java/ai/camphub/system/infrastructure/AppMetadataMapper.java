package ai.camphub.system.infrastructure;

import ai.camphub.system.domain.AppMetadata;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code app_metadata} 表的访问接口。
 *
 * <p>SQL 定义在 {@code resources/mapper/system/AppMetadataMapper.xml}，不写在注解里。
 * 原因：本项目后续要做慢 SQL 分析与性能优化（Phase 09），SQL 集中放在 XML 中便于统一审查、
 * 加索引提示与比对执行计划；注解里拼 SQL 在复杂查询下会迅速失控。
 */
public interface AppMetadataMapper {

    /**
     * 按键查询单条元数据。
     *
     * @param metaKey 键
     * @return 存在时返回记录
     */
    Optional<AppMetadata> findByKey(@Param("metaKey") String metaKey);
}
