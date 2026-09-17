package ai.camphub.community;

import static org.assertj.core.api.Assertions.assertThat;

import ai.camphub.common.error.ErrorCode;
import ai.camphub.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 社区内容主链路的端到端测试：发布 → 详情 → 列表 → 编辑 → 删除。
 *
 * <h2>为什么这些用例必须跑真实 HTTP + 真实 MySQL</h2>
 * 本阶段引入的每一条规则都同时牵涉三个层次，任何一层单独测都会漏掉真正的失败模式：
 * <ul>
 *   <li><b>安全过滤链</b>：读公开、写必须登录。这条规则写在 {@code SecurityConfig} 里，
 *       用 MockMvc 或直接调 service 都验证不到它 —— 只有真的发一次匿名请求才知道。</li>
 *   <li><b>MyBatis 构造器映射</b>：{@code _long} / {@code _int} 别名写错时编译期毫无提示，
 *       要到真正查询才抛 {@code NoSuchMethodException}。</li>
 *   <li><b>渲染与净化</b>：{@code bodyHtml} 是服务端产物，只有落库再读出来，
 *       才能确认存进去的和读出来的是同一份、且危险内容确实被处理掉了。</li>
 * </ul>
 *
 * <h2>断言写在"对外表现"这一层</h2>
 * 例如"非作者不能改帖"这条，本类断言的是 <b>404</b>，而不是"抛了某个异常"。
 * 因为真正要守住的约束是"不泄漏资源是否存在"，而不是"用了哪种实现方式"。
 */
class CommunityContentIT extends AbstractIntegrationTest {

    /** 发帖的最小合法请求体。 */
    private static Map<String, Object> postBody(String title, String bodyMd, List<String> tags) {
        return body(
                "categorySlug", "tech",
                "title", title,
                "bodyMd", bodyMd,
                "tagNames", tags);
    }

    /**
     * 发布一条帖子并返回它的对外标识。
     *
     * @param accessToken 作者令牌
     * @param title       标题
     * @param bodyMd      正文
     * @param tags        标签
     * @return 新帖的 publicId
     */
    private String createPost(String accessToken, String title, String bodyMd, List<String> tags)
            throws IOException, InterruptedException {
        HttpResponse<String> response = sendJson("POST", "/api/v1/community/posts",
                toJson(postBody(title, bodyMd, tags)), accessToken);
        assertThat(response.statusCode())
                .as("发布应返回 201，实际响应：%s", response.body())
                .isEqualTo(201);
        return JsonPath.read(response.body(), "$.publicId");
    }

    /**
     * 读取某个标签当前的帖子数。
     *
     * <p>刻意在 Java 里挑出目标标签，而不是写 {@code $[?(@.slug=='x')].postCount[0]}
     * 这样的路径：过滤器表达式的返回值形状会随匹配数量变化（单个匹配时是标量、
     * 多个匹配时是数组），写死 {@code [0]} 的断言会时对时错 ——
     * 而"时对时错"的测试比没有测试更糟。
     *
     * @param slug 标签对外标识
     * @return 该标签的帖子数；标签不存在时抛错（而不是返回 0，避免把"没找到"当成"计数为零"）
     */
    private int tagPostCount(String slug) throws Exception {
        List<Map<String, Object>> tags = JsonPath.read(get("/api/v1/community/tags").body(), "$");
        return tags.stream()
                .filter(tag -> slug.equals(tag.get("slug")))
                .mapToInt(tag -> ((Number) tag.get("postCount")).intValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("标签入口里没有找到 slug=" + slug));
    }

    @Nested
    @DisplayName("发布与读取")
    class PublishAndRead {

        @Test
        @DisplayName("发布成功后立刻能在详情与列表里看到，且正文已渲染为净化后的 HTML")
        void publishedPostShouldBeImmediatelyReadable() throws Exception {
            TestAccount author = registerAccount(nextIp());

            String publicId = createPost(author.tokens().access(),
                    "Spring Boot 启动变慢的一次排查",
                    "## 现象\n\n启动时间从 3 秒涨到了 12 秒。\n\n```java\nSystem.out.println(\"hi\");\n```",
                    List.of("java", "spring-boot"));

            HttpResponse<String> detail = get("/api/v1/community/posts/" + publicId);
            assertThat(detail.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<String>read(detail.body(), "$.title"))
                    .isEqualTo("Spring Boot 启动变慢的一次排查");
            assertThat(JsonPath.<String>read(detail.body(), "$.bodyHtml"))
                    .contains("<h2>")
                    .contains("<pre>")
                    .doesNotContain("<h2>## ");
            assertThat(JsonPath.<String>read(detail.body(), "$.author.nickname")).isNotBlank();
            assertThat(JsonPath.<List<String>>read(detail.body(), "$.tags[*].slug"))
                    .containsExactlyInAnyOrder("java", "spring-boot");

            HttpResponse<String> list = get("/api/v1/community/posts?size=50");
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<List<String>>read(list.body(), "$.items[*].publicId"))
                    .contains(publicId);
        }

        @Test
        @DisplayName("列表项的摘要不含 Markdown 标记（列表页不返回正文，摘要即全部可读内容）")
        void summaryShouldBePlainText() throws Exception {
            TestAccount author = registerAccount(nextIp());
            // 用一个唯一标签把这条帖子单独筛出来：直接取列表第一条也是可靠的，
            // 但那依赖"它一定是最新的"这一隐含前提，而筛选是显式的。
            // 标签取 ASCII —— 它要出现在查询串里，非 ASCII 字符需要额外 URL 编码
            String uniqueTag = "summary-test-" + System.nanoTime();
            createPost(author.tokens().access(), "笔记整理", "## 标题\n\n**加粗**的一段话。",
                    List.of(uniqueTag));

            HttpResponse<String> list = get("/api/v1/community/posts?tag=" + uniqueTag);
            assertThat(JsonPath.<Integer>read(list.body(), "$.total")).isEqualTo(1);

            String summary = JsonPath.read(list.body(), "$.items[0].summary");
            assertThat(summary)
                    .doesNotContain("##")
                    .doesNotContain("**")
                    .contains("标题");
        }

        @Test
        @DisplayName("匿名可以浏览列表与详情，但写入一律 401")
        void anonymousCanReadButNotWrite() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "公开的帖子", "正文", List.of());

            assertThat(get("/api/v1/community/posts").statusCode()).isEqualTo(200);
            assertThat(get("/api/v1/community/posts/" + publicId).statusCode()).isEqualTo(200);
            assertThat(get("/api/v1/community/categories").statusCode()).isEqualTo(200);
            assertThat(get("/api/v1/community/tags").statusCode()).isEqualTo(200);
            assertThat(get("/api/v1/community/posts/" + publicId + "/comments").statusCode()).isEqualTo(200);

            HttpResponse<String> anonymousCreate = sendJson("POST", "/api/v1/community/posts",
                    toJson(postBody("匿名发帖", "正文", List.of())), null);
            assertThat(anonymousCreate.statusCode()).isEqualTo(401);
            assertThat(JsonPath.<Integer>read(anonymousCreate.body(), "$.code"))
                    .isEqualTo(ErrorCode.UNAUTHENTICATED.code());

            HttpResponse<String> anonymousDelete = sendJson("DELETE",
                    "/api/v1/community/posts/" + publicId, null, null);
            assertThat(anonymousDelete.statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("匿名浏览时 liked/favorited/ownedByMe 恒为 false，但字段存在（响应形状不随登录态变化）")
        void anonymousResponseKeepsSameShape() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "形状测试", "正文", List.of());
            sendJson("POST", "/api/v1/community/posts/" + publicId + "/like", null, author.tokens().access());

            HttpResponse<String> anonymous = get("/api/v1/community/posts/" + publicId);
            assertThat(JsonPath.<Boolean>read(anonymous.body(), "$.liked")).isFalse();
            assertThat(JsonPath.<Boolean>read(anonymous.body(), "$.favorited")).isFalse();
            assertThat(JsonPath.<Boolean>read(anonymous.body(), "$.ownedByMe")).isFalse();
            assertThat(JsonPath.<Integer>read(anonymous.body(), "$.likeCount")).isEqualTo(1);

            HttpResponse<String> own = getWithToken("/api/v1/community/posts/" + publicId,
                    author.tokens().access());
            assertThat(JsonPath.<Boolean>read(own.body(), "$.liked")).isTrue();
            assertThat(JsonPath.<Boolean>read(own.body(), "$.ownedByMe")).isTrue();
        }
    }

    @Nested
    @DisplayName("Markdown 渲染与注入防护")
    class Sanitization {

        @Test
        @DisplayName("用户写的原始 HTML 标签以文本呈现，不被执行")
        void rawHtmlShouldBeEscaped() throws Exception {
            TestAccount author = registerAccount(nextIp());

            String publicId = createPost(author.tokens().access(), "注入测试",
                    "<script>alert(1)</script>\n\n<img src=x onerror=alert(2)>\n\n正常段落",
                    List.of());

            String bodyHtml = JsonPath.read(get("/api/v1/community/posts/" + publicId).body(), "$.bodyHtml");

            assertThat(bodyHtml).doesNotContain("<script").doesNotContain("onerror=");
            // 转义不等于丢弃：内容仍以文本形式可见，否则作者会以为自己的内容丢了
            assertThat(bodyHtml).contains("&lt;script&gt;");
            assertThat(bodyHtml).contains("正常段落");
        }

        @Test
        @DisplayName("javascript: 协议链接被剥离，http/https 链接被保留并强制 nofollow")
        void dangerousUrlsShouldBeStripped() throws Exception {
            TestAccount author = registerAccount(nextIp());

            String publicId = createPost(author.tokens().access(), "链接测试",
                    "[危险](javascript:alert(1))\n\n[正常](https://example.com/a)",
                    List.of());

            String bodyHtml = JsonPath.read(get("/api/v1/community/posts/" + publicId).body(), "$.bodyHtml");

            assertThat(bodyHtml).doesNotContain("javascript:");
            assertThat(bodyHtml).contains("https://example.com/a");
            assertThat(bodyHtml).contains("rel=\"nofollow\"");
        }

        @Test
        @DisplayName("未经净化的 iframe 与 data: URI 不会出现在输出里")
        void unknownTagsShouldBeRejected() throws Exception {
            TestAccount author = registerAccount(nextIp());

            String publicId = createPost(author.tokens().access(), "标签白名单",
                    "<iframe src=\"https://example.com\"></iframe>\n\n[data](data:text/html;base64,PHNjcmlwdD4=)",
                    List.of());

            String bodyHtml = JsonPath.read(get("/api/v1/community/posts/" + publicId).body(), "$.bodyHtml");

            assertThat(bodyHtml).doesNotContain("<iframe").doesNotContain("data:text/html");
        }
    }

    @Nested
    @DisplayName("筛选、分页与参数校验")
    class Listing {

        @Test
        @DisplayName("按板块与标签筛选，两处口径与总页数一致")
        void shouldFilterByCategoryAndTag() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String accessToken = author.tokens().access();

            HttpResponse<String> created = sendJson("POST", "/api/v1/community/posts",
                    toJson(body("categorySlug", "study-notes", "title", "筛选专用帖",
                            "bodyMd", "正文", "tagNames", List.of("exam-review"))), accessToken);
            assertThat(created.statusCode()).isEqualTo(201);
            String publicId = JsonPath.read(created.body(), "publicId");

            HttpResponse<String> byCategory = get("/api/v1/community/posts?category=study-notes&size=50");
            assertThat(JsonPath.<List<String>>read(byCategory.body(), "$.items[*].publicId"))
                    .contains(publicId);

            HttpResponse<String> byTag = get("/api/v1/community/posts?tag=exam-review&size=50");
            assertThat(JsonPath.<List<String>>read(byTag.body(), "$.items[*].publicId"))
                    .contains(publicId);

            // 空串必须等同于"未指定"。前端的筛选框为空时发的就是 ?category=&tag=，
            // 若不归一化就会变成 WHERE slug = ''，表现为"什么都没选却什么都查不到"
            HttpResponse<String> emptyFilters = get("/api/v1/community/posts?category=&tag=&size=50");
            assertThat(JsonPath.<Integer>read(emptyFilters.body(), "$.total"))
                    .isGreaterThanOrEqualTo(JsonPath.read(byCategory.body(), "$.total"));
        }

        @Test
        @DisplayName("页大小超过服务端上限时按上限截断，而不是报错")
        void pageSizeShouldBeCapped() throws Exception {
            TestAccount author = registerAccount(nextIp());
            createPost(author.tokens().access(), "分页测试", "正文", List.of());

            HttpResponse<String> response = get("/api/v1/community/posts?size=1000");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<Integer>read(response.body(), "$.size")).isEqualTo(50);
        }

        @Test
        @DisplayName("页码小于 1 被修正为 1，而不是报错")
        void pageNumberShouldBeNormalized() throws Exception {
            HttpResponse<String> response = get("/api/v1/community/posts?page=0&size=20");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<Integer>read(response.body(), "$.page")).isEqualTo(1);
        }

        @Test
        @DisplayName("排序参数不区分大小写 —— 前端按 URL 习惯发的是小写")
        void sortShouldBeAcceptedCaseInsensitively() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "排序大小写测试", "正文", List.of());

            // 这里刻意**只断言"被接受"与"结果可达"，不断言全局顺序**：
            // 集成测试共用一个容器，其他用例创建的帖子同样在结果集里，
            // 断言"第一条就是它"会时对时错 —— 而时对时错的测试比没有测试更糟。
            for (String value : List.of("hot", "HOT", "Hot", "latest", "LATEST")) {
                HttpResponse<String> response =
                        get("/api/v1/community/posts?sort=" + value + "&size=50");
                assertThat(response.statusCode())
                        .as("sort=%s 必须被接受：该参数会出现在用户可见的 URL 里，"
                                + "而 Spring 对枚举的默认转换区分大小写", value)
                        .isEqualTo(200);
                assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].publicId"))
                        .contains(publicId);
            }
        }

        @Test
        @DisplayName("无法识别的排序取值被拒绝（40002），不静默退化成默认排序")
        void unknownSortShouldBeRejected() throws Exception {
            HttpResponse<String> response = get("/api/v1/community/posts?sort=trending");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(JsonPath.<Integer>read(response.body(), "$.code")).isEqualTo(40002);
        }

        @Test
        @DisplayName("不存在的板块被拒绝，错误码是社区段的 40030 而不是通用的 40000")
        void unknownCategoryShouldBeRejected() throws Exception {
            TestAccount author = registerAccount(nextIp());

            HttpResponse<String> response = sendJson("POST", "/api/v1/community/posts",
                    toJson(body("categorySlug", "no-such-category", "title", "标题",
                            "bodyMd", "正文", "tagNames", List.of())), author.tokens().access());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(JsonPath.<Integer>read(response.body(), "$.code"))
                    .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND.code());
        }

        @Test
        @DisplayName("正文超过上限被拒绝，且提示里带上实际字数")
        void oversizedBodyShouldBeRejected() throws Exception {
            TestAccount author = registerAccount(nextIp());

            HttpResponse<String> response = sendJson("POST", "/api/v1/community/posts",
                    toJson(body("categorySlug", "tech", "title", "超长正文",
                            "bodyMd", "字".repeat(30001), "tagNames", List.of())),
                    author.tokens().access());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(JsonPath.<Integer>read(response.body(), "$.code"))
                    .isEqualTo(ErrorCode.INVALID_POST_CONTENT.code());
            assertThat(JsonPath.<String>read(response.body(), "$.message")).contains("30001");
        }

        @Test
        @DisplayName("标签数超过上限被拒绝")
        void tooManyTagsShouldBeRejected() throws Exception {
            TestAccount author = registerAccount(nextIp());

            HttpResponse<String> response = sendJson("POST", "/api/v1/community/posts",
                    toJson(postBody("标签过多", "正文",
                            List.of("java", "mysql", "algorithm", "frontend", "english", "contest"))),
                    author.tokens().access());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(JsonPath.<Integer>read(response.body(), "$.code"))
                    .isEqualTo(ErrorCode.INVALID_POST_CONTENT.code());
        }

        @Test
        @DisplayName("标题缺失走字段校验（40000），与业务上限（40031）是两个码")
        void blankTitleShouldFailBeanValidation() throws Exception {
            TestAccount author = registerAccount(nextIp());

            HttpResponse<String> response = sendJson("POST", "/api/v1/community/posts",
                    toJson(body("categorySlug", "tech", "title", "", "bodyMd", "正文",
                            "tagNames", List.of())), author.tokens().access());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(JsonPath.<Integer>read(response.body(), "$.code"))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED.code());
        }
    }

    @Nested
    @DisplayName("标签归一化")
    class Tags {

        @Test
        @DisplayName("Java 与 java 是同一个标签，只出现一次")
        void tagNamesShouldCollapseAfterNormalization() throws Exception {
            TestAccount author = registerAccount(nextIp());

            String publicId = createPost(author.tokens().access(), "标签归一化",
                    "正文", List.of("Java", "java"));

            HttpResponse<String> detail = get("/api/v1/community/posts/" + publicId);
            assertThat(JsonPath.<List<String>>read(detail.body(), "$.tags[*].slug"))
                    .containsExactly("java");
            // 展示名保留首次出现的大小写
            assertThat(JsonPath.<String>read(detail.body(), "$.tags[0].name")).isEqualTo("Java");
        }

        @Test
        @DisplayName("中文标签保留中文字符（不是被规范化成空串），并被自动创建")
        void chineseTagShouldKeepItsCharacters() throws Exception {
            TestAccount author = registerAccount(nextIp());
            // 加一段数字后缀保证唯一：种子里的标签是固定的，而这条用例要验证"新建"
            String chineseTag = "期末冲刺" + System.nanoTime();

            String publicId = createPost(author.tokens().access(), "中文标签", "正文", List.of(chineseTag));

            HttpResponse<String> detail = get("/api/v1/community/posts/" + publicId);
            assertThat(JsonPath.<String>read(detail.body(), "$.tags[0].name")).isEqualTo(chineseTag);
            // 只保留 ASCII 的规范化规则会把「考研」「期末复习」这类标签变成空串，
            // 等于禁止中文标签 —— 而本平台的主要标签就是中文词
            assertThat(JsonPath.<String>read(detail.body(), "$.tags[0].slug")).isEqualTo(chineseTag);

            // 确认它真的被写进了 tag 表，而不是只出现在这条帖子的响应里
            assertThat(get("/api/v1/community/tags").body()).contains(chineseTag);
        }

        @Test
        @DisplayName("标签名不含任何字母数字或文字时被拒绝")
        void meaninglessTagShouldBeRejected() throws Exception {
            TestAccount author = registerAccount(nextIp());

            HttpResponse<String> response = sendJson("POST", "/api/v1/community/posts",
                    toJson(postBody("无效标签", "正文", List.of("!!!"))), author.tokens().access());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(JsonPath.<Integer>read(response.body(), "$.code"))
                    .isEqualTo(ErrorCode.INVALID_TAG.code());
        }
    }

    @Nested
    @DisplayName("编辑与删除的归属校验")
    class Ownership {

        @Test
        @DisplayName("作者可以编辑，正文变更后列表摘要同步更新")
        void authorCanEdit() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "原标题", "原正文", List.of("java"));

            HttpResponse<String> updated = sendJson("PUT", "/api/v1/community/posts/" + publicId,
                    toJson(body("categorySlug", "qa", "title", "新标题", "bodyMd", "新正文",
                            "tagNames", List.of("mysql"))), author.tokens().access());

            assertThat(updated.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<String>read(updated.body(), "$.title")).isEqualTo("新标题");
            assertThat(JsonPath.<String>read(updated.body(), "$.bodyHtml")).contains("新正文");
            assertThat(JsonPath.<String>read(updated.body(), "$.categorySlug")).isEqualTo("qa");
            // 标签被整体重建，旧标签不应残留
            assertThat(JsonPath.<List<String>>read(updated.body(), "$.tags[*].slug"))
                    .containsExactly("mysql");
        }

        @Test
        @DisplayName("非作者编辑或删除一律 404 —— 403 会暴露'这条帖子存在'")
        void nonAuthorShouldGet404() throws Exception {
            TestAccount author = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "别人的帖子", "正文", List.of());

            HttpResponse<String> edit = sendJson("PUT", "/api/v1/community/posts/" + publicId,
                    toJson(body("categorySlug", "tech", "title", "改标题", "bodyMd", "改正文",
                            "tagNames", List.of())), stranger.tokens().access());
            assertThat(edit.statusCode()).isEqualTo(404);
            assertThat(JsonPath.<Integer>read(edit.body(), "$.code"))
                    .isEqualTo(ErrorCode.NOT_FOUND.code());

            HttpResponse<String> delete = sendJson("DELETE", "/api/v1/community/posts/" + publicId,
                    null, stranger.tokens().access());
            assertThat(delete.statusCode()).isEqualTo(404);

            // 确认没有被误删
            assertThat(get("/api/v1/community/posts/" + publicId).statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("作者删除后详情 404，且不再出现在列表、标签筛选结果与标签计数里")
        void authorCanDelete() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "待删除的帖子", "正文",
                    List.of("open-source"));

            // 先记下删除前的计数，用于确认"标签的帖子数"确实跟着收回
            int countBeforeDelete = tagPostCount("open-source");

            HttpResponse<String> deleted = sendJson("DELETE", "/api/v1/community/posts/" + publicId,
                    null, author.tokens().access());
            assertThat(deleted.statusCode()).isEqualTo(204);

            assertThat(get("/api/v1/community/posts/" + publicId).statusCode()).isEqualTo(404);
            assertThat(get("/api/v1/community/posts?size=50").body()).doesNotContain(publicId);

            // 这条断言专门盯住"标签筛选"这条路径：它走的是 post_tag 的 EXISTS 子查询，
            // 与主列表是两处不同的 SQL。软删除只在 post 上打了标记，
            // 若筛选没有一并考虑 deleted_at，标签页就会继续列出已删除的帖子
            HttpResponse<String> byTag = get("/api/v1/community/posts?tag=open-source&size=50");
            assertThat(byTag.body()).doesNotContain(publicId);

            int countAfterDelete = tagPostCount("open-source");
            assertThat(countAfterDelete).isEqualTo(countBeforeDelete - 1);
        }

        @Test
        @DisplayName("对不存在的帖子做任何写操作都是 404，而不是 500")
        void unknownPostShouldReturn404() throws Exception {
            TestAccount account = registerAccount(nextIp());
            String missing = "0000000000000000000xyz";

            HttpResponse<String> edit = sendJson("PUT", "/api/v1/community/posts/" + missing,
                    toJson(body("categorySlug", "tech", "title", "标题", "bodyMd", "正文",
                            "tagNames", List.of())), account.tokens().access());
            assertThat(edit.statusCode()).isEqualTo(404);

            HttpResponse<String> like = sendJson("POST", "/api/v1/community/posts/" + missing + "/like",
                    null, account.tokens().access());
            assertThat(like.statusCode()).isEqualTo(404);
        }
    }
}
