package ai.camphub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * CampusHub AI 应用入口。
 *
 * <p>当前形态为 Modular Monolith 的单一可部署单元（见 docs/adr/0001-modular-monolith.md）。
 * 业务模块以包为单位隔离，边界由 ArchUnit 在构建期强制校验，而非仅靠约定。
 *
 * <p>运行角色（api / doc-worker / ai-worker / scheduler）将在后续 Phase 需要用独立进程时，
 * 通过 Spring Profile 区分，而不是提前拆成多个 Maven 模块 —— 理由见同一份 ADR。
 *
 * <p><b>为什么排除 {@link UserDetailsServiceAutoConfiguration}</b>：
 * 只要类路径上有 spring-security 而容器里没有 {@code UserDetailsService}，
 * 这个自动配置就会创建一个名为 {@code user} 的内存用户并打印随机密码到启动日志。
 * 本项目不使用 Spring Security 的 UserDetails 认证链路（登录在 identity 模块内
 * 自行完成，只在授权阶段使用 Spring Security），那个用户永远不会被用到 ——
 * 但"存在一个无人知晓用途的可用账号"本身就是不该留在系统里的东西，
 * 它还会在每次启动时打印一段看起来像配置提示的日志，误导后来的人。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@MapperScan("ai.camphub.**.infrastructure")
public class CamphubApplication {

    public static void main(String[] args) {
        SpringApplication.run(CamphubApplication.class, args);
    }
}
