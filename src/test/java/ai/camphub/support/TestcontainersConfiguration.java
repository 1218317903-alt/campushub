package ai.camphub.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试的依赖容器配置。
 *
 * <h2>为什么用 Testcontainers 而不是 H2</h2>
 * H2 与 MySQL 在语法、字符集、排序规则、锁行为上都有差异。用 H2 跑"通过"的测试，
 * 只能证明代码在 H2 上没问题 —— 而生产跑的是 MySQL。本项目后续要做慢 SQL 分析、
 * utf8mb4 排序、并发写一致性验证，这些恰恰是 H2 无法代表的部分。所以宁可启动真实
 * MySQL 容器，慢一点也要测对。
 *
 * <h2>镜像为什么固定为 mysql:8.4</h2>
 * 与 docs/11-开发环境.md 记录的本机镜像一致，也与 docker-compose.yml 一致，
 * 避免"测试用一套版本、开发用另一套"导致的偶发差异。可通过
 * {@code -Dtestcontainers.mysql.image=...} 覆盖。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** 默认镜像。刻意不用 Testcontainers 的默认 tag，保证与本机/编排文件一致。 */
    private static final String DEFAULT_MYSQL_IMAGE = "mysql:8.4";

    /** 测试库名，与生产 default 值区分，避免误连。 */
    private static final String TEST_DATABASE = "camphub";

    /** 测试账号名。 */
    private static final String TEST_USERNAME = "camphub";

    /**
     * 测试库密码。
     *
     * <p>这是<b>测试专用的一次性容器</b>密码，容器随测试生命周期创建与销毁，
     * 不对应任何真实环境，因此写在代码里是安全的。这与"禁止把真实凭据写进代码"
     * 并不冲突 —— 区别在于它不能被用来访问任何有价值的东西。
     */
    private static final String TEST_PASSWORD = "camphub-test-only";

    /**
     * 提供 MySQL 容器，并把连接信息以 {@code @ServiceConnection} 的方式
     * 自动注入 Spring 的数据源配置，无需手写 JDBC URL。
     *
     * @param image 镜像名，可由 testcontainers.mysql.image 属性覆盖
     * @return MySQL 容器
     */
    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer(
            @Value("${testcontainers.mysql.image:" + DEFAULT_MYSQL_IMAGE + "}") String image) {
        return new MySQLContainer(DockerImageName.parse(image))
                .withDatabaseName(TEST_DATABASE)
                .withUsername(TEST_USERNAME)
                .withPassword(TEST_PASSWORD)
                // 让容器时区与开发环境一致，避免"测试里时间是 UTC、开发是 +08:00"的隐性差异。
                // 注意：这里只统一容器时区；应用侧仍应在连接串中显式指定 connectionTimeZone。
                .withEnv("TZ", "Asia/Shanghai");
    }
}
