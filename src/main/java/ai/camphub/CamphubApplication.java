package ai.camphub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * CampusHub AI 应用入口。
 *
 * <p>当前形态为 Modular Monolith 的单一可部署单元（见 docs/adr/0001-modular-monolith.md）。
 * 业务模块以包为单位隔离，边界由 ArchUnit 在构建期强制校验，而非仅靠约定。
 *
 * <p>运行角色（api / doc-worker / ai-worker / scheduler）将在后续 Phase 需要用独立进程时，
 * 通过 Spring Profile 区分，而不是提前拆成多个 Maven 模块 —— 理由见同一份 ADR。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("ai.camphub.**.infrastructure")
public class CamphubApplication {

    public static void main(String[] args) {
        SpringApplication.run(CamphubApplication.class, args);
    }
}
