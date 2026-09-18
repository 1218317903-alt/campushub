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
 * 社区讨论与互动的端到端测试：评论、两级回复、点赞、收藏、浏览计数。
 *
 * <h2>这一组用例的共同点：都在验证"幂等"与"层级"这两条容易漏的约束</h2>
 * <ul>
 *   <li><b>幂等</b>：点赞、取消点赞、收藏、取消收藏都必须是幂等的。用户连点两下、
 *       网络重试、前端乐观更新后再发一次 —— 都是常态。这类缺陷不会在手工点一次时出现，
 *       只会在并发或重试下表现为"计数比实际多了一"。</li>
 *   <li><b>层级</b>："回复只能挂顶层评论"这条规则数据库表达不了（外键只能保证
 *       父评论存在，不能保证它是顶层）。一旦漏检查就会悄悄产生第三层，
 *       而那些内容永远查不出来 —— 库里有、界面上没有。</li>
 * </ul>
 */
class CommunityDiscussionIT extends AbstractIntegrationTest {

    /**
     * 发布一条帖子并返回它的对外标识。
     *
     * @param accessToken 作者令牌
     * @param title       标题
     * @return 新帖的 publicId
     */
    private String createPost(String accessToken, String title)
            throws IOException, InterruptedException {
        HttpResponse<String> response = sendJson("POST", "/api/v1/community/posts",
                toJson(body("categorySlug", "tech", "title", title,
                        "bodyMd", "正文内容", "tagNames", List.of("java"))), accessToken);
        assertThat(response.statusCode())
                .as("发布应返回 201，实际响应：%s", response.body())
                .isEqualTo(201);
        return JsonPath.read(response.body(), "$.publicId");
    }

    /**
     * 发表一条评论或回复。
     *
     * @param accessToken    作者令牌
     * @param postPublicId   帖子对外标识
     * @param text           评论正文
     * @param parentPublicId 父评论对外标识，顶层评论传 null
     * @return 响应
     */
    private HttpResponse<String> comment(String accessToken, String postPublicId,
                                         String text, String parentPublicId)
            throws IOException, InterruptedException {
        Map<String, Object> payload = parentPublicId == null
                ? body("body", text)
                : body("body", text, "parentId", parentPublicId);
        return sendJson("POST", "/api/v1/community/posts/" + postPublicId + "/comments",
                toJson(payload), accessToken);
    }

    @Nested
    @DisplayName("两级评论")
    class Comments {

        @Test
        @DisplayName("帖子删除后，已知评论 ID 也不能读取其回复")
        void deletedPost_hidesRepliesByCommentId() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String post = createPost(author.tokens().access(), "删除帖子后的回复隔离");
            String parent = JsonPath.read(comment(author.tokens().access(), post,
                    "父评论", null).body(), "$.publicId");
            assertThat(comment(author.tokens().access(), post, "回复正文", parent).statusCode())
                    .isEqualTo(201);
            String path = "/api/v1/community/comments/" + parent + "/replies";
            assertThat(get(path).statusCode()).isEqualTo(200);
            assertThat(sendJson("DELETE", "/api/v1/community/posts/" + post,
                    null, author.tokens().access()).statusCode()).isEqualTo(204);
            assertThat(get(path).statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("最大整数页码不溢出为负数，返回空页")
        void hugePage_returnsEmptyPage() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String post = createPost(author.tokens().access(), "分页边界");
            String parent = JsonPath.read(comment(author.tokens().access(), post,
                    "父评论", null).body(), "$.publicId");
            for (String path : List.of(
                    "/api/v1/community/posts",
                    "/api/v1/community/posts/" + post + "/comments",
                    "/api/v1/community/comments/" + parent + "/replies",
                    "/api/v1/community/me/favorites")) {
                var response = getWithToken(path + "?page=2147483647&size=50", author.tokens().access(), Map.of());
                assertThat(response.statusCode()).as(path).isEqualTo(200);
                assertThat(JsonPath.<List<?>>read(response.body(), "$.items")).isEmpty();
            }
        }

        @Test
        @DisplayName("顶层评论与回复都能发表，评论总数含回复")
        void shouldCreateTopLevelCommentAndReply() throws Exception {
            TestAccount author = registerAccount(nextIp());
            TestAccount commenter = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "评论测试");

            HttpResponse<String> topLevel = comment(commenter.tokens().access(), publicId,
                    "写得很清楚，收藏了。", null);
            assertThat(topLevel.statusCode()).isEqualTo(201);
            String commentPublicId = JsonPath.read(topLevel.body(), "$.publicId");
            assertThat(JsonPath.<Integer>read(topLevel.body(), "$.replyCount")).isZero();

            HttpResponse<String> reply = comment(author.tokens().access(), publicId,
                    "谢谢，后面还会补充。", commentPublicId);
            assertThat(reply.statusCode()).isEqualTo(201);

            HttpResponse<String> list = get("/api/v1/community/posts/" + publicId + "/comments");
            assertThat(JsonPath.<Integer>read(list.body(), "$.total")).isEqualTo(1);
            assertThat(JsonPath.<Integer>read(list.body(), "$.items[0].replyCount")).isEqualTo(1);
            assertThat(JsonPath.<String>read(list.body(), "$.items[0].body"))
                    .isEqualTo("写得很清楚，收藏了。");

            HttpResponse<String> replies = get("/api/v1/community/comments/"
                    + commentPublicId + "/replies");
            assertThat(replies.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<Integer>read(replies.body(), "$.total")).isEqualTo(1);
            assertThat(JsonPath.<String>read(replies.body(), "$.items[0].body")).isEqualTo("谢谢，后面还会补充。");

            // post.comment_count 含回复，这是列表页显示的"评论数"
            HttpResponse<String> detail = get("/api/v1/community/posts/" + publicId);
            assertThat(JsonPath.<Integer>read(detail.body(), "$.commentCount")).isEqualTo(2);
        }

        @Test
        @DisplayName("回复一条回复被拒绝：本平台是两层结构")
        void replyToReplyShouldBeRejected() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "层级约束");

            String topLevelId = JsonPath.read(
                    comment(author.tokens().access(), publicId, "顶层评论", null).body(), "$.publicId");
            String replyId = JsonPath.read(
                    comment(author.tokens().access(), publicId, "回复", topLevelId).body(), "$.publicId");

            HttpResponse<String> thirdLevel = comment(author.tokens().access(), publicId,
                    "回复的回复", replyId);

            assertThat(thirdLevel.statusCode()).isEqualTo(400);
            assertThat(JsonPath.<Integer>read(thirdLevel.body(), "$.code"))
                    .isEqualTo(ErrorCode.BAD_REQUEST.code());

            // 被拒绝之后不应留下任何痕迹
            HttpResponse<String> replies = get("/api/v1/community/comments/" + topLevelId + "/replies");
            assertThat(JsonPath.<Integer>read(replies.body(), "$.total")).isEqualTo(1);
        }

        @Test
        @DisplayName("回复挂到别的帖子的评论上被拒绝：否则回复会出现在 A 帖而它回复的内容在 B 帖")
        void replyAcrossPostsShouldBeRejected() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String firstPost = createPost(author.tokens().access(), "第一帖");
            String secondPost = createPost(author.tokens().access(), "第二帖");

            String commentOfFirstPost = JsonPath.read(
                    comment(author.tokens().access(), firstPost, "第一帖下的评论", null).body(), "$.publicId");

            HttpResponse<String> wrongParent = comment(author.tokens().access(), secondPost,
                    "跨帖回复", commentOfFirstPost);

            assertThat(wrongParent.statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("父评论不存在时返回 404 而不是 500")
        void unknownParentShouldReturn404() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "父评论不存在");

            HttpResponse<String> response = comment(author.tokens().access(), publicId,
                    "回复一个不存在的评论", "0000000000000000000xyz");

            assertThat(response.statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("匿名不能发评论，但可以读评论")
        void anonymousCannotComment() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "匿名评论");
            comment(author.tokens().access(), publicId, "已有评论", null);

            assertThat(get("/api/v1/community/posts/" + publicId + "/comments").statusCode()).isEqualTo(200);

            HttpResponse<String> response = sendJson("POST",
                    "/api/v1/community/posts/" + publicId + "/comments",
                    toJson(body("body", "匿名评论")), null);
            assertThat(response.statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("删除顶层评论会连带删除其下回复，并把帖子评论数重算准确")
        void deletingTopLevelCommentCascades() throws Exception {
            TestAccount author = registerAccount(nextIp());
            TestAccount commenter = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "删除级联");

            String topLevelId = JsonPath.read(
                    comment(commenter.tokens().access(), publicId, "顶层评论", null).body(), "$.publicId");
            comment(author.tokens().access(), publicId, "第一条回复", topLevelId);
            comment(author.tokens().access(), publicId, "第二条回复", topLevelId);

            HttpResponse<String> deleted = sendJson("DELETE",
                    "/api/v1/community/comments/" + topLevelId, null, commenter.tokens().access());
            assertThat(deleted.statusCode()).isEqualTo(204);

            // 被删除的评论一律 404，而不是"200 + 空列表"。
            // 这与"已删除的帖子详情返回 404"是同一条规则：软删除的资源对外不存在。
            // 若这里返回空列表，就等于承认"这个 id 曾经存在" ——
            // 那就是一个可用的评论存在性探测器
            HttpResponse<String> replies = get("/api/v1/community/comments/" + topLevelId + "/replies");
            assertThat(replies.statusCode()).isEqualTo(404);

            HttpResponse<String> list = get("/api/v1/community/posts/" + publicId + "/comments");
            assertThat(JsonPath.<Integer>read(list.body(), "$.total")).isZero();

            // 这一条才是"级联删除确实生效"的证明，而且只有它能证明：
            // post.comment_count 由重算得出（COUNT 非删除的评论行），
            // 因此它为零意味着那两条回复也一并被标记删除了。
            // 只断言"顶层评论列表为空"是不够的 —— 那只能说明父评论自身被删了。
            HttpResponse<String> detail = get("/api/v1/community/posts/" + publicId);
            assertThat(JsonPath.<Integer>read(detail.body(), "$.commentCount")).isZero();
        }

        @Test
        @DisplayName("非作者删除评论一律 404")
        void onlyAuthorCanDeleteComment() throws Exception {
            TestAccount author = registerAccount(nextIp());
            TestAccount commenter = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "评论归属");

            String commentId = JsonPath.read(
                    comment(commenter.tokens().access(), publicId, "别人的评论", null).body(), "$.publicId");

            HttpResponse<String> response = sendJson("DELETE",
                    "/api/v1/community/comments/" + commentId, null, stranger.tokens().access());

            assertThat(response.statusCode()).isEqualTo(404);
            HttpResponse<String> list = get("/api/v1/community/posts/" + publicId + "/comments");
            assertThat(JsonPath.<Integer>read(list.body(), "$.total")).isEqualTo(1);
        }

        @Test
        @DisplayName("评论正文为空白时被字段校验拦下")
        void blankCommentShouldBeRejected() throws Exception {
            TestAccount author = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "空白评论");

            HttpResponse<String> response = comment(author.tokens().access(), publicId, "   ", null);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(JsonPath.<Integer>read(response.body(), "$.code"))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED.code());
        }
    }

    @Nested
    @DisplayName("点赞与收藏的幂等性")
    class Reactions {

        @Test
        @DisplayName("重复点赞不报错、不重复计数；取消点赞同样幂等")
        void likeShouldBeIdempotent() throws Exception {
            TestAccount author = registerAccount(nextIp());
            TestAccount reader = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "点赞幂等");
            String token = reader.tokens().access();

            HttpResponse<String> first = sendJson("POST",
                    "/api/v1/community/posts/" + publicId + "/like", null, token);
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<Boolean>read(first.body(), "$.active")).isTrue();
            assertThat(JsonPath.<Integer>read(first.body(), "$.count")).isEqualTo(1);

            HttpResponse<String> second = sendJson("POST",
                    "/api/v1/community/posts/" + publicId + "/like", null, token);
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<Integer>read(second.body(), "$.count")).isEqualTo(1);

            HttpResponse<String> removed = sendJson("DELETE",
                    "/api/v1/community/posts/" + publicId + "/like", null, token);
            assertThat(JsonPath.<Boolean>read(removed.body(), "$.active")).isFalse();
            assertThat(JsonPath.<Integer>read(removed.body(), "$.count")).isZero();

            HttpResponse<String> removedAgain = sendJson("DELETE",
                    "/api/v1/community/posts/" + publicId + "/like", null, token);
            assertThat(removedAgain.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<Integer>read(removedAgain.body(), "$.count")).isZero();
        }

        @Test
        @DisplayName("两个人分别点赞计数累加，列表与详情里的计数一致")
        void likeCountShouldAccumulate() throws Exception {
            TestAccount author = registerAccount(nextIp());
            TestAccount first = registerAccount(nextIp());
            TestAccount second = registerAccount(nextIp());
            // 标签用 ASCII：它要出现在查询串里，而非 ASCII 字符需要额外的 URL 编码，
            // 那会让用例多一个与断言目标无关的失败点
            String uniqueTag = "count-test-" + System.nanoTime();
            HttpResponse<String> created = sendJson("POST", "/api/v1/community/posts",
                    toJson(body("categorySlug", "tech", "title", "计数累加", "bodyMd", "正文",
                            "tagNames", List.of(uniqueTag))), author.tokens().access());
            String publicId = JsonPath.read(created.body(), "publicId");

            sendJson("POST", "/api/v1/community/posts/" + publicId + "/like", null, first.tokens().access());
            sendJson("POST", "/api/v1/community/posts/" + publicId + "/like", null, second.tokens().access());

            assertThat(JsonPath.<Integer>read(get("/api/v1/community/posts/" + publicId).body(), "$.likeCount"))
                    .isEqualTo(2);
            // 列表里的计数与详情必须同源，否则用户会看到"列表说 2、点进去说 1"
            HttpResponse<String> list = get("/api/v1/community/posts?tag=" + uniqueTag);
            assertThat(JsonPath.<Integer>read(list.body(), "$.items[0].likeCount")).isEqualTo(2);
            // 未点赞的人看到 liked=false
            HttpResponse<String> asAuthor = getWithToken("/api/v1/community/posts/" + publicId,
                    author.tokens().access());
            assertThat(JsonPath.<Boolean>read(asAuthor.body(), "$.liked")).isFalse();
        }

        @Test
        @DisplayName("收藏后出现在我的收藏里，取消后消失；匿名访问该接口 401")
        void favoriteShouldAppearInMyFavorites() throws Exception {
            TestAccount author = registerAccount(nextIp());
            TestAccount reader = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "收藏测试");
            String token = reader.tokens().access();

            assertThat(get("/api/v1/community/me/favorites").statusCode()).isEqualTo(401);

            HttpResponse<String> favorited = sendJson("POST",
                    "/api/v1/community/posts/" + publicId + "/favorite", null, token);
            assertThat(favorited.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<Boolean>read(favorited.body(), "$.active")).isTrue();

            HttpResponse<String> favorites = getWithToken("/api/v1/community/me/favorites", token);
            assertThat(favorites.statusCode()).isEqualTo(200);
            assertThat(JsonPath.<List<String>>read(favorites.body(), "$.items[*].publicId"))
                    .contains(publicId);
            assertThat(JsonPath.<Boolean>read(favorites.body(), "$.items[0].favorited")).isTrue();

            // 收藏是账号级数据：别人看到的收藏列表里不应该有这条
            HttpResponse<String> others = getWithToken("/api/v1/community/me/favorites",
                    author.tokens().access());
            assertThat(others.body()).doesNotContain(publicId);

            sendJson("DELETE", "/api/v1/community/posts/" + publicId + "/favorite", null, token);
            HttpResponse<String> afterRemoval = getWithToken("/api/v1/community/me/favorites", token);
            assertThat(afterRemoval.body()).doesNotContain(publicId);
        }
    }

    @Nested
    @DisplayName("浏览计数")
    class ViewCounting {

        @Test
        @DisplayName("同一用户同一天多次浏览只计一次，匿名浏览不计")
        void viewShouldCountOncePerUserPerDay() throws Exception {
            TestAccount author = registerAccount(nextIp());
            TestAccount reader = registerAccount(nextIp());
            String publicId = createPost(author.tokens().access(), "浏览计数");

            HttpResponse<String> firstRead = getWithToken("/api/v1/community/posts/" + publicId,
                    reader.tokens().access());
            assertThat(JsonPath.<Integer>read(firstRead.body(), "$.viewCount")).isEqualTo(1);

            HttpResponse<String> secondRead = getWithToken("/api/v1/community/posts/" + publicId,
                    reader.tokens().access());
            assertThat(JsonPath.<Integer>read(secondRead.body(), "$.viewCount")).isEqualTo(1);

            // 口径是"登录用户的浏览量"：匿名访问照常返回内容，但不计数。
            // 匿名去重要把 IP 变成标识存下来，而 IP 空间很小 —— 那等于明文存 IP 却像脱敏
            assertThat(get("/api/v1/community/posts/" + publicId).statusCode()).isEqualTo(200);
            assertThat(JsonPath.<Integer>read(get("/api/v1/community/posts/" + publicId).body(), "$.viewCount"))
                    .isEqualTo(1);

            // 换一个人看，计数才增加
            HttpResponse<String> anotherReader = getWithToken("/api/v1/community/posts/" + publicId,
                    author.tokens().access());
            assertThat(JsonPath.<Integer>read(anotherReader.body(), "$.viewCount")).isEqualTo(2);
        }
    }
}
