package ai.camphub.system.app;

import ai.camphub.common.config.AppProperties;
import ai.camphub.system.api.SystemInfoResponse;
import ai.camphub.system.infrastructure.AppMetadataMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统信息服务：对外描述"当前这套部署实例处在什么状态"。
 *
 * <p>它同时充当 Phase 01 的<b>纵切验证点</b>：一次调用会真实穿过
 * Web 层 → 服务层 → 数据层 → MySQL，从而证明"框架接好了"不是一句空话。
 * 如果只是返回硬编码常量，Flyway、MyBatis、数据源是否真的可用就无法被验证。
 */
@Service
public class SystemInfoService {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoService.class);

    /** 数据库 schema 基线版本在 app_metadata 中的键名。 */
    private static final String KEY_SCHEMA_BASELINE = "schema.baseline";

    /** 查不到基线记录时的占位值，避免接口因数据缺失而 500。 */
    private static final String UNKNOWN = "unknown";

    private final AppProperties appProperties;
    private final Environment environment;
    private final AppMetadataMapper appMetadataMapper;

    /**
     * 构造注入。
     *
     * <p>刻意使用构造器注入而非字段注入：依赖关系显式可见、对象可被测试直接 new 出来、
     * 且能声明为 final 避免被意外替换。ArchUnit 会强制这条约定。
     *
     * @param appProperties     应用配置
     * @param environment       Spring 环境，用于读取生效 profile
     * @param appMetadataMapper 元数据访问
     */
    public SystemInfoService(AppProperties appProperties,
                             Environment environment,
                             AppMetadataMapper appMetadataMapper) {
        this.appProperties = appProperties;
        this.environment = environment;
        this.appMetadataMapper = appMetadataMapper;
    }

    /**
     * 汇总当前实例信息。
     *
     * @return 系统信息
     */
    @Transactional(readOnly = true)
    public SystemInfoResponse describe() {
        String schemaBaseline = appMetadataMapper.findByKey(KEY_SCHEMA_BASELINE)
                .map(metadata -> metadata.metaValue())
                .orElseGet(() -> {
                    // 数据缺失属于可观测信号：记录 warn，但不让接口失败。
                    // 若这里直接抛异常，会把"迁移没跑"伪装成"接口 500"，反而更难定位。
                    log.warn("app_metadata 中缺少 key={}，schema 基线未知", KEY_SCHEMA_BASELINE);
                    return UNKNOWN;
                });

        String profiles = String.join(",", environment.getActiveProfiles());
        return new SystemInfoResponse(
                appProperties.name(),
                appProperties.version(),
                profiles,
                Runtime.version().toString(),
                Instant.now(),
                schemaBaseline
        );
    }
}
