package ai.camphub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import ai.camphub.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 合成演示数据生成器的端到端测试。
 *
 * <h2>为什么要用一个独立的、开着小规模种子的 Spring 上下文</h2>
 * 生成器是一个 {@code ApplicationRunner}，它在上下文启动时就跑完。因此验证它
 * 不能靠"调用某个方法看返回值"，而必须真的启动一次应用、让它自己选种，
 * 再从 HTTP 接口上观察结果 —— 这也正是它未来被使用的唯一方式。
 *
 * <p>规模压到 4 位作者 / 12 条帖子 / 20 条评论：完整的 12/60/240 只是慢，
 * 多出来的行数不会多验证任何一条规则。而它独立成一个 Spring 上下文
 * （因为 {@code @TestPropertySource} 与其它测试类不同）意味着它有自己的
 * Testcontainers 容器，因此这些演示数据不会污染其它测试类的断言 ——
 * 这一点很关键：一旦演示数据进到公共容器里，"列表应该有 N 条"这类断言
 * 就会变成对种子规模的隐式依赖。
 *
 * <h2>生成的数据必须能通过真实登录验证</h2>
 * 只断言"帖子的作者字段非空"是不够的：那条数据可能是生成器绕过应用服务
 * 直接写进去的。这里用生成出来的账号**真的登录一次** —— 它能成功，就证明
 * 密码被真正哈希过、账号状态与角色都是服务层建立的，
 * 而不是一批"看起来像用户"的行。
 */
@TestPropertySource(properties = {
        "app.demo-seed.enabled=true",
        "app.demo-seed.author-count=4",
        "app.demo-seed.post-count=12",
        "app.demo-seed.comment-count=20"
})
class DemoSeedIT extends AbstractIntegrationTest {

    @Autowired
    private DemoSeedRunner demoSeedRunner;

    @Autowired
    private DemoSeedProperties demoSeedProperties;

    @Test
    @DisplayName("首次启动后社区里有内容：板块、标签、帖子、评论与互动都能读到")
    void shouldSeedReadableContent() throws Exception {
        HttpResponse<String> list = get("/api/v1/community/posts?size=50");

        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(list.body(), "$.total")).isEqualTo(12);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.items[*].title"))
                .doesNotContainNull()
                .allSatisfy(title -> assertThat(title).isNotBlank());
        // 演示数据要让每个板块都有内容：轮转分配板块就是为了这件事，
        // 否则演示时点进某个板块会是空页面
        assertThat(JsonPath.<List<String>>read(list.body(), "$.items[*].categorySlug"))
                .contains("tech", "study-notes", "campus", "qa", "resources");
        assertThat(JsonPath.<List<String>>read(list.body(), "$.items[*].author.nickname")).doesNotContainNull();
    }

    @Test
    @DisplayName("评论散布到每一帖：否则按时间倒序的首页会是一屏零评论")
    void commentsShouldBeSpreadAcrossEveryPost() throws Exception {
        HttpResponse<String> list = get("/api/v1/community/posts?size=50");
        List<Integer> commentCounts = JsonPath.read(list.body(), "$.items[*].commentCount");

        // 规模：12 帖 / 20 条评论上限。旧实现是"从第一帖开始逐帖填满再停"，
        // 于是评论只落在最旧的 5 帖上、最新 7 帖一条都没有 —— 而帖数与评论数都对。
        // 这里断言的是分布形状，正是那类"数量对、位置错"的缺陷唯一能被挡住的地方
        assertThat(commentCounts)
                .as("每一帖都应有评论：社区首页按发布时间倒序，零评论的那一段恰好是最显眼的")
                .hasSize(12)
                .allSatisfy(count -> assertThat(count).isPositive());
        assertThat(commentCounts.stream().mapToInt(Integer::intValue).sum())
                .as("内联计数列的累加应等于评论上限，说明生成的每条评论都被计数了")
                .isEqualTo(20);
    }

    @Test
    @DisplayName("帖子详情里的正文是已渲染并净化的 HTML，摘要不含 Markdown 标记")
    void shouldSeedRenderedContent() throws Exception {
        String publicId = JsonPath.read(get("/api/v1/community/posts?size=1").body(),
                "$.items[0].publicId");

        HttpResponse<String> detail = get("/api/v1/community/posts/" + publicId);

        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(detail.body(), "$.bodyHtml")).contains("<p>");
        assertThat(JsonPath.<String>read(detail.body(), "$.summary")).doesNotContain("##");
        assertThat(JsonPath.<String>read(detail.body(), "$.bodyMd")).isNotBlank();
    }

    @Test
    @DisplayName("演示账号可以真正登录：证明密码经服务层哈希、角色与状态都由服务层建立")
    void demoAccountShouldBeAbleToLogIn() throws Exception {
        HttpResponse<String> login = sendJson("POST", "/api/v1/auth/login",
                toJson(body("identifier", DemoContentLibrary.username(0),
                        "password", demoSeedProperties.password(),
                        "device", "演示数据生成器测试")),
                null, fromIp(nextIp()));

        assertThat(login.statusCode())
                .as("演示账号应能登录，实际响应：%s", login.body())
                .isEqualTo(200);
        assertThat(JsonPath.<String>read(login.body(), "$.accessToken")).isNotBlank();
    }

    @Test
    @DisplayName("账号存在而内容已存在时重跑是空操作，不会新增数据也不会撞唯一键")
    void rerunShouldBeNoOp() throws Exception {
        int totalBefore = JsonPath.read(get("/api/v1/community/posts?size=1").body(), "$.total");

        // 直接调用执行器，模拟"应用又启动了一次"。
        // 它内部会先判断有没有内容，再决定是否生成 —— 这条路径就是启动时的路径
        demoSeedRunner.run(null);

        int totalAfter = JsonPath.read(get("/api/v1/community/posts?size=1").body(), "$.total");
        assertThat(totalAfter).isEqualTo(totalBefore);
    }
}
