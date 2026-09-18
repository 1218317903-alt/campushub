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
     * <h2>为什么要显式指定连接时区（这是一个真实的红灯换来的）</h2>
     * {@code @ServiceConnection} 生成的 JDBC URL <b>不包含</b> {@code application.yml}
     * 里那几个参数，因此 {@code connectionTimeZone} 会退回驱动默认值 {@code LOCAL} ——
     * 也就是<b>跑测试的那台机器的默认时区</b>。而容器是 {@code TZ=Asia/Shanghai}。
     * 两者一致时一切正常，不一致时的时间差会直接吃掉功能：
     *
     * <ul>
     *   <li>应用写入 {@code next_attempt_at} 等列时传的是 {@link java.time.Instant}，
     *       驱动按 JVM 默认时区转成墙上时间；
     *   <li>而 {@code insertIfAbsent} 不给 {@code next_attempt_at} 赋值，用的是列默认值
     *       {@code CURRENT_TIMESTAMP(3)}，取的是<b>会话时区</b>（容器 = +08:00）。
     * </ul>
     *
     * <p>于是当 JVM 默认时区是 UTC 时（GitHub Actions runner 就是 UTC），
     * 新入队的任务 {@code next_attempt_at} 比应用算出来的"现在"晚 8 小时，
     * 领取语句 {@code next_attempt_at <= ?} 恒不成立 —— <b>没有任何一份文档会被解析</b>。
     * 表现是全部文档流水线用例一起失败，而失败信息指向"分块数为 0"，
     * 与真正的原因相隔很远。本机跑测试恰好是 +08:00，所以这个差异被完整地藏了一整个阶段。
     *
     * <p>补上这两个参数之后，测试与 {@code application.yml} 里的生产配置<b>完全一致</b>，
     * 测试结论因此与"跑在什么时区的机器上"无关 —— 这也正是集成测试该有的性质。
     * 生产侧本来就有这两个参数，所以这不是"为测试放宽条件"，而是让测试终于跑在生产约定上。
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
                .withEnv("TZ", "Asia/Shanghai")
                // 与 application.yml 的 JDBC 串逐字一致：@ServiceConnection 不会带上它们。
                .withUrlParam("connectionTimeZone", "Asia/Shanghai")
                .withUrlParam("forceConnectionTimeZoneToSession", "true");
    }
}
