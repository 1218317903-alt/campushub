package ai.camphub.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.app.WorkspaceScopeContext;
import ai.camphub.workspace.infrastructure.DocumentMapper;
import ai.camphub.workspace.infrastructure.NoteMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 跨用户越权与三层防线的端到端验证。
 *
 * <h2>为什么这些用例是本阶段最重要的交付物</h2>
 * 空间模块的功能（建空间、邀请、写笔记、传文档）都是"顺利路径"，
 * 写错了会有明显的手感问题。而这个模块里真正会造成伤害的是另一类缺陷：
 * <b>某个接口在某个身份下返回了不该返回的数据</b>。这类缺陷在功能测试里
 * 完全看不出来 —— 用自己造的数据怎么测都正常，因为在自己造的数据里
 * "我是唯一的相关人"。
 *
 * <p>因此这里的每个用例都显式地扮演一个攻击者或一个越界的合法用户，
 * 并且断言的是<b>拒绝的方式</b>，而不只是"被拒绝了"：
 * 不可见返回 404（不泄漏资源存在），可见但无权限返回 403。
 *
 * <h2>三层防线各有一条独立的用例</h2>
 * <ol>
 *   <li>第一层（身份能力）由"未登录访问"覆盖 —— 落在 {@code anyRequest().authenticated()} 上。</li>
 *   <li>第二层（资源级）由下面所有的越权用例覆盖。</li>
 *   <li>第三层（数据范围）由 {@link ThirdLineOfDefence} 覆盖 —— 它<b>刻意绕过服务层</b>
 *       直接调用 Mapper，因为经过服务层时第二层已经先行拒绝，
 *       测到的是第二层而不是第三层。</li>
 * </ol>
 */
@DisplayName("空间模块 · 越权与防线")
class WorkspaceAuthorizationIT extends WorkspaceTestSupport {

    @Autowired
    private NoteMapper noteMapper;

    @Autowired
    private DocumentMapper documentMapper;

    /**
     * 不可见的资源一律表现为"不存在"。
     *
     * <p>这一组是本模块最重要的语义断言：404 与 403 的区别不是风格问题，
     * 而是"能不能用接口枚举出别人有哪些资源"。
     */
    @Nested
    @DisplayName("不可见即不存在（404）")
    class InvisibleMeansNotFound {

        @Test
        @DisplayName("陌生人对空间的所有操作都返回 404，包括那些本来需要更高权限的")
        void strangerSeesNothing() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());

            // 读取：404（不是 403 —— 403 会承认这个空间存在）
            assertThat(getWithToken("/api/v1/workspaces/" + workspace, stranger.tokens().access()).statusCode())
                    .isEqualTo(404);
            assertThat(getWithToken("/api/v1/workspaces/" + workspace + "/members", stranger.tokens().access())
                    .statusCode())
                    .isEqualTo(404);
            assertThat(getWithToken("/api/v1/workspaces/" + workspace + "/notes", stranger.tokens().access())
                    .statusCode())
                    .isEqualTo(404);
            assertThat(getWithToken("/api/v1/workspaces/" + workspace + "/documents", stranger.tokens().access())
                    .statusCode())
                    .isEqualTo(404);

            // 修改与删除：同样是 404，而不是 403
            assertThat(sendJson("PUT", "/api/v1/workspaces/" + workspace,
                    toJson(body("name", "改名")), stranger.tokens().access()).statusCode())
                    .isEqualTo(404);
            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace, stranger.tokens().access()).statusCode())
                    .isEqualTo(404);

            // 成员管理：404
            assertThat(sendJson("POST", "/api/v1/workspaces/" + workspace + "/invites",
                    toJson(body("username", owner.username())), stranger.tokens().access()).statusCode())
                    .isEqualTo(404);
            assertThat(getWithToken("/api/v1/workspaces/" + workspace + "/invites",
                    stranger.tokens().access()).statusCode())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("陌生人的列表里看不到别人的空间")
        void strangersListDoesNotContainOthersWorkspace() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());

            var listed = json(getWithToken("/api/v1/workspaces", stranger.tokens().access()));
            assertThat(listed.path("items").toString()).doesNotContain(workspace);
            assertThat(listed.path("total").asLong()).isZero();
        }

        @Test
        @DisplayName("知道笔记的标识也读不到：空间不可见时那条请求根本到不了笔记")
        void knowingTheNoteIdIsNotEnough() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            String note = createNote(owner.tokens().access(), workspace, "机密笔记");

            assertThat(getWithToken("/api/v1/workspaces/" + workspace + "/notes/" + note,
                    stranger.tokens().access()).statusCode())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("把别人的笔记标识放进自己的空间路径也读不到 —— (空间, 笔记) 是成对定位的")
        void noteIsAddressedByWorkspaceAndId() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String ownerWorkspace = createWorkspace(owner.tokens().access());
            String ownWorkspace = createWorkspace(stranger.tokens().access());
            String otherNote = createNote(owner.tokens().access(), ownerWorkspace, "别人的笔记");

            // 路径里的空间是自己的（可见），但笔记属于别人的空间 —— 必须 404
            assertThat(getWithToken("/api/v1/workspaces/" + ownWorkspace + "/notes/" + otherNote,
                    stranger.tokens().access()).statusCode())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("下载接口不能成为绕过元数据权限的旁路")
        void downloadDoesNotBypassMetadataPermission() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            String doc = uploadDocumentOk(owner.tokens().access(), workspace, "secret.txt",
                    "text/plain", "机密内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            var response = downloadDocument(stranger.tokens().access(), workspace, doc);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("机密内容");
        }

        @Test
        @DisplayName("未登录一律 401 —— 私有域没有公开端点")
        void anonymousIsRejected() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());

            assertThat(get("/api/v1/workspaces").statusCode()).isEqualTo(401);
            assertThat(get("/api/v1/workspaces/" + workspace).statusCode()).isEqualTo(401);
            assertThat(get("/api/v1/workspaces/" + workspace + "/notes").statusCode()).isEqualTo(401);
        }
    }

    /**
     * 可见但权限不足：403，而且不泄漏"这个操作对别人是允许的"。
     */
    @Nested
    @DisplayName("可见但无权限（403）")
    class VisibleButDenied {

        @Test
        @DisplayName("普通成员不能改空间设置、不能删空间、不能管理成员")
        void memberCannotAdminister() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(), member.username(), null);

            String memberToken = member.tokens().access();

            // 改设置：403（能看到空间，所以不必假装它不存在）
            assertThat(sendJson("PUT", "/api/v1/workspaces/" + workspace,
                    toJson(body("name", "成员改的名")), memberToken).statusCode())
                    .isEqualTo(403);

            // 删空间：403
            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace, memberToken).statusCode())
                    .isEqualTo(403);

            // 邀请：403
            assertThat(sendJson("POST", "/api/v1/workspaces/" + workspace + "/invites",
                    toJson(body("username", owner.username())), memberToken).statusCode())
                    .isEqualTo(403);

            // 移除成员：403
            // 路径里的标识用什么都不影响结论 —— 判定在解析目标用户之前就已经否决了，
            // 因此这里写一个明显不是标识的字符串，反而让"它没有被用到"变得可见。
            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace + "/members/not-a-real-id",
                    memberToken).statusCode())
                    .isEqualTo(403);

            // 改角色：403
            assertThat(sendJson("PUT", "/api/v1/workspaces/" + workspace + "/members/"
                    + "not-a-real-id/role", toJson(body("role", "ADMIN")), memberToken).statusCode())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("成员能做的事仍然是能做的 —— 403 不是因为'成员身份不受信任'")
        void memberCanStillUseTheWorkspace() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(), member.username(), null);

            String memberToken = member.tokens().access();

            // 读得到空间、成员、笔记
            assertThat(getWithToken("/api/v1/workspaces/" + workspace, memberToken).statusCode()).isEqualTo(200);
            assertThat(getWithToken("/api/v1/workspaces/" + workspace + "/members", memberToken).statusCode())
                    .isEqualTo(200);
            // 写得了笔记、传得了文档
            assertThat(sendJson("POST", "/api/v1/workspaces/" + workspace + "/notes",
                    toJson(body("title", "成员写的", "bodyMd", "正文")), memberToken).statusCode())
                    .isEqualTo(201);
            assertThat(uploadDocument(memberToken, workspace, "member.txt", "text/plain",
                    "x".getBytes(java.nio.charset.StandardCharsets.UTF_8)).statusCode())
                    .isEqualTo(201);
        }
    }

    /**
     * 删除的归属维度：协作内容开放编辑，但删除需要正当性。
     */
    @Nested
    @DisplayName("删除的归属")
    class OwnershipOnDelete {

        @Test
        @DisplayName("成员能删自己写的笔记，删别人的返回 403")
        void memberDeletesOnlyOwnNotes() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(), member.username(), null);

            String ownerNote = createNote(owner.tokens().access(), workspace, "拥有者的笔记");
            String memberNote = createNote(member.tokens().access(), workspace, "成员的笔记");
            String memberToken = member.tokens().access();

            // 删别人的：403。此前用 READ_NOTES 取得了可见性，所以这里不是 404
            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace + "/notes/" + ownerNote, memberToken)
                    .statusCode())
                    .isEqualTo(403);

            // 删自己的：204
            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace + "/notes/" + memberNote, memberToken)
                    .statusCode())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("拥有者与管理员能删别人的内容 —— '收拾烂摊子'正是这个角色的职责")
        void ownerCanDeleteAnyNote() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(), member.username(), null);
            String memberNote = createNote(member.tokens().access(), workspace, "成员的笔记");

            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace + "/notes/" + memberNote,
                    owner.tokens().access()).statusCode())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("成员能删自己上传的文档，删别人的返回 403")
        void memberDeletesOnlyOwnDocuments() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(), member.username(), null);

            byte[] content = "content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String ownerDoc = uploadDocumentOk(owner.tokens().access(), workspace, "owner.txt", "text/plain", content);
            String memberDoc = uploadDocumentOk(member.tokens().access(), workspace, "member.txt", "text/plain", content);
            String memberToken = member.tokens().access();

            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace + "/documents/" + ownerDoc, memberToken)
                    .statusCode())
                    .isEqualTo(403);
            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace + "/documents/" + memberDoc, memberToken)
                    .statusCode())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("附带 deletableByMe 与真实权限一致 —— 不显示一个点下去会 403 的按钮")
        void deletableFlagMatchesActualPermission() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(), member.username(), null);

            String ownerNote = createNote(owner.tokens().access(), workspace, "拥有者的笔记");
            String memberNote = createNote(member.tokens().access(), workspace, "成员的笔记");
            String memberToken = member.tokens().access();

            assertThat(json(getWithToken("/api/v1/workspaces/" + workspace + "/notes/" + ownerNote, memberToken))
                    .path("deletableByMe").asBoolean()).isFalse();
            assertThat(json(getWithToken("/api/v1/workspaces/" + workspace + "/notes/" + memberNote, memberToken))
                    .path("deletableByMe").asBoolean()).isTrue();
            // 拥有者看到的两条都可以删
            assertThat(json(getWithToken("/api/v1/workspaces/" + workspace + "/notes/" + ownerNote,
                    owner.tokens().access())).path("deletableByMe").asBoolean()).isTrue();
        }
    }

    /**
     * 成员与邀请的越权。
     */
    @Nested
    @DisplayName("成员与邀请")
    class MembershipAndInvites {

        @Test
        @DisplayName("成员列表里有拥有者，角色是 OWNER —— 否则界面会显示'这个空间没有管理员'")
        void ownerAppearsInMemberList() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(), member.username(), null);

            var members = json(getWithToken("/api/v1/workspaces/" + workspace + "/members",
                    member.tokens().access()));
            assertThat(members.isArray()).isTrue();
            assertThat(members.get(0).path("role").asString()).isEqualTo("OWNER");
            assertThat(members.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("拥有者不能退出自己的空间 —— 那会留下一个没人能管理、也无人能删除的孤儿")
        void ownerCannotLeave() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());

            var response = deleteWithToken("/api/v1/workspaces/" + workspace + "/members/me",
                    owner.tokens().access());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(errorCodeOf(response)).isEqualTo(ErrorCode.WORKSPACE_OWNER_CANNOT_LEAVE.code());
        }

        @Test
        @DisplayName("成员退出后立刻失去访问权 —— 授权集合是按请求算的，没有缓存残留")
        void leavingRevokesAccessImmediately() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(), member.username(), null);
            String memberToken = member.tokens().access();

            assertThat(getWithToken("/api/v1/workspaces/" + workspace, memberToken).statusCode()).isEqualTo(200);
            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace + "/members/me", memberToken).statusCode())
                    .isEqualTo(204);
            assertThat(getWithToken("/api/v1/workspaces/" + workspace, memberToken).statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("被移除后立刻失去访问权")
        void removalRevokesAccessImmediately() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(), member.username(), null);

            // 按角色挑出成员那一行，而不是按下标取：下标依赖"拥有者排第一"这条约定，
            // 而这条约定本身由 ownerAppearsInMemberList 单独断言。两处都依赖它，
            // 一旦它变了会有两条测试同时失败，看不出是哪一处的问题。
            String memberPublicId = memberPublicIdOf(workspace, member.tokens().access());
            String memberToken = member.tokens().access();

            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace + "/members/" + memberPublicId,
                    owner.tokens().access()).statusCode())
                    .isEqualTo(204);
            assertThat(getWithToken("/api/v1/workspaces/" + workspace, memberToken).statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("拥有者不能被移除、也不能被改角色 —— 它不在成员表里，所以是 404")
        void ownerIsNotAMemberRow() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            // 拥有者的对外标识从成员列表里取（它被合成在第一行），而不是另调一个接口 ——
            // 这样这条测试只依赖本模块的契约
            String ownerPublicId = json(getWithToken("/api/v1/workspaces/" + workspace + "/members",
                    owner.tokens().access())).get(0).path("userPublicId").asString();

            String ownerToken = owner.tokens().access();
            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace + "/members/" + ownerPublicId,
                    ownerToken).statusCode())
                    .isEqualTo(404);
            assertThat(sendJson("PUT", "/api/v1/workspaces/" + workspace + "/members/" + ownerPublicId + "/role",
                    toJson(body("role", "ADMIN")), ownerToken).statusCode())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("邀请码只有被邀请人能兑换：别人拿到同一串码返回 404，不泄漏'这个码存在'")
        void inviteCodeIsDirectional() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount invitee = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            String code = invite(owner.tokens().access(), workspace, invitee.username(), null);

            var stolen = sendJson("POST", "/api/v1/workspace-invites/" + code + "/accept", "{}",
                    stranger.tokens().access());
            assertThat(stolen.statusCode()).isEqualTo(404);
            assertThat(json(stolen).path("message").asString())
                    .as("拒绝文案不能暗示这个码确实存在")
                    .isEqualTo(ErrorCode.NOT_FOUND.defaultMessage());

            // 真正的受邀人仍然可以兑换
            acceptInvite(invitee.tokens().access(), code);
        }

        @Test
        @DisplayName("已兑换的码不能再用 —— 用别人的账号再试一次也是 404")
        void redeemedCodeIsGone() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount invitee = registerAccount(nextIp());
            TestAccount stranger = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            String code = invite(owner.tokens().access(), workspace, invitee.username(), null);
            acceptInvite(invitee.tokens().access(), code);

            assertThat(sendJson("POST", "/api/v1/workspace-invites/" + code + "/accept", "{}",
                    stranger.tokens().access()).statusCode())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("已撤销的码不能兑换")
        void revokedCodeIsGone() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount invitee = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            String code = invite(owner.tokens().access(), workspace, invitee.username(), null);

            assertThat(deleteWithToken("/api/v1/workspaces/" + workspace + "/invites/" + code,
                    owner.tokens().access()).statusCode())
                    .isEqualTo(204);
            assertThat(sendJson("POST", "/api/v1/workspace-invites/" + code + "/accept", "{}",
                    invitee.tokens().access()).statusCode())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("过期的码返回 40020 而不是 404 —— 兑换人已经证明了他就是受邀人")
        void expiredCodeYieldsItsOwnError() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount invitee = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            String code = invite(owner.tokens().access(), workspace, invitee.username(), null);

            // 直接把到期时间推到过去，而不是等真实时间流逝
            jdbcTemplate.update("UPDATE workspace_invite SET expires_at = ? WHERE code = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(60)), code);

            var response = sendJson("POST", "/api/v1/workspace-invites/" + code + "/accept", "{}",
                    invitee.tokens().access());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(errorCodeOf(response)).isEqualTo(ErrorCode.INVITE_CODE_INVALID.code());
        }

        @Test
        @DisplayName("已经是成员时重复兑换（另一条仍然有效的邀请）返回 409")
        void alreadyMemberYieldsConflict() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount invitee = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());

            String first = invite(owner.tokens().access(), workspace, invitee.username(), null);
            String second = invite(owner.tokens().access(), workspace, invitee.username(), null);
            acceptInvite(invitee.tokens().access(), first);

            var response = sendJson("POST", "/api/v1/workspace-invites/" + second + "/accept", "{}",
                    invitee.tokens().access());
            assertThat(response.statusCode()).isEqualTo(409);
        }

        @Test
        @DisplayName("邀请不存在的人返回 404；邀请已在空间内的人返回 409")
        void inviteTargetMustExistAndBeOutside() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount member = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            addMember(owner.tokens().access(), workspace, member.tokens().access(), member.username(), null);
            String ownerToken = owner.tokens().access();

            assertThat(sendJson("POST", "/api/v1/workspaces/" + workspace + "/invites",
                    toJson(body("username", "no-such-user-9f2a1b")), ownerToken).statusCode())
                    .isEqualTo(404);
            assertThat(sendJson("POST", "/api/v1/workspaces/" + workspace + "/invites",
                    toJson(body("username", member.username())), ownerToken).statusCode())
                    .isEqualTo(409);
            // 邀请自己也是 409
            assertThat(sendJson("POST", "/api/v1/workspaces/" + workspace + "/invites",
                    toJson(body("username", owner.username())), ownerToken).statusCode())
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("撤销邀请必须落在路径里的那个空间上：换个自己也在里面的空间不生效")
        void revokeIsScopedToThePathWorkspace() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            TestAccount invitee = registerAccount(nextIp());
            String workspaceA = createWorkspace(owner.tokens().access());
            String workspaceB = createWorkspace(owner.tokens().access());
            String code = invite(owner.tokens().access(), workspaceA, invitee.username(), null);

            // 关键前提：同一个人同时拥有 A 与 B，因此他的授权集合是 {A, B} ——
            // 第三层防线追加的 `workspace_id IN (A, B)` **拦不住**这次越权。
            // 能拦住的只有语句里写死的那个 workspace_id。这条测试的存在意义就在于此：
            // 它验证的是"范围被收窄到路径上的那一个"，而不是"防线碰巧挡住了"。
            assertThat(deleteWithToken("/api/v1/workspaces/" + workspaceB + "/invites/" + code,
                    owner.tokens().access()).statusCode())
                    .isEqualTo(404);
            // A 的那条邀请必须仍然可用
            acceptInvite(invitee.tokens().access(), code);
        }

        /**
         * 从成员列表里取出成员那一行的用户对外标识。
         *
         * @param workspaceId 空间对外标识
         * @param token       能读到成员列表的令牌
         * @return 成员的用户对外标识
         * @throws IOException          网络异常
         * @throws InterruptedException 被中断
         */
        private String memberPublicIdOf(String workspaceId, String token)
                throws IOException, InterruptedException {
            var members = json(getWithToken("/api/v1/workspaces/" + workspaceId + "/members", token));
            for (var member : members) {
                if ("MEMBER".equals(member.path("role").asString())) {
                    return member.path("userPublicId").asString();
                }
            }
            throw new AssertionError("成员列表里没有角色为 MEMBER 的行：" + members);
        }
    }

    /**
     * 第三层防线：绕过服务层直查数据。
     *
     * <h2>为什么必须绕过服务层来测</h2>
     * 经过服务层时，第二层（{@code AuthorizationService}）会先拒绝，请求根本走不到 Mapper。
     * 那样测到的是第二层 —— 而第三层要回答的是另一个问题：
     * <b>假如将来有人写了一条忘了判定的代码路径，数据层还有没有最后一道闸门？</b>
     *
     * <p>这里的做法是手工绑定一个错误的授权范围，然后直接调用 Mapper。
     * 它与真实请求的唯一区别是"授权范围是谁算的"，而防线关心的正是这个范围。
     */
    @Nested
    @DisplayName("第三层防线：数据范围")
    class ThirdLineOfDefence {

        @Test
        @DisplayName("授权集合为空时，按主键的查询也查不到任何东西（追加的是 1 = 0，不是不加限制）")
        void emptyScopeYieldsNothing() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            String note = createNote(owner.tokens().access(), workspace, "被保护的内容");
            long workspaceId = workspaceIdOf(workspace);

            try {
                WorkspaceScopeContext.bind(Set.of());
                assertThat(noteMapper.findDetail(workspaceId, note))
                        .as("授权集合为空时不应查到任何行")
                        .isEmpty();
                assertThat(noteMapper.findSummaries(workspaceId, 10, 0L)).isEmpty();
                assertThat(noteMapper.countByWorkspace(workspaceId)).isZero();
            } finally {
                WorkspaceScopeContext.clear();
            }

            // 关掉过滤后同一行是存在的 —— 证明上面查不到是因为防线，而不是数据本来就没有
            assertThat(WorkspaceScopeContext.unscoped(() -> noteMapper.findDetail(workspaceId, note)))
                    .as("这一行确实存在；查不到的唯一原因是空间过滤")
                    .isPresent();
        }

        @Test
        @DisplayName("授权范围指向别的空间时，按主键的写操作影响 0 行")
        void wrongScopeCannotWrite() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            String note = createNote(owner.tokens().access(), workspace, "待保护");
            long workspaceId = workspaceIdOf(workspace);
            long noteId = noteIdOf(workspace, owner.username(), "待保护");
            long otherWorkspaceId = workspaceIdOf(createWorkspace(owner.tokens().access()));

            try {
                // 绑定一个"我确实有权、但不是这一个"的范围：
                // 这正是"调用方把空间标识弄错"或"传了别人的空间主键"时的样子
                WorkspaceScopeContext.bind(Set.of(otherWorkspaceId));
                assertThat(noteMapper.softDelete(noteId, Instant.now()))
                        .as("跨空间的按主键删除必须影响 0 行")
                        .isZero();
            } finally {
                WorkspaceScopeContext.clear();
            }

            assertThat(WorkspaceScopeContext.unscoped(() -> noteMapper.findDetail(workspaceId, note)))
                    .as("笔记不应被删除")
                    .isPresent();
        }

        @Test
        @DisplayName("文档的按主键软删除同样被拦住")
        void wrongScopeCannotDeleteDocument() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            String doc = uploadDocumentOk(owner.tokens().access(), workspace, "keep.txt", "text/plain",
                    "keep".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long workspaceId = workspaceIdOf(workspace);
            long docId = jdbcTemplate.queryForObject(
                    "SELECT id FROM document WHERE public_id = ?", Long.class, doc);
            long otherWorkspaceId = workspaceIdOf(createWorkspace(owner.tokens().access()));

            try {
                WorkspaceScopeContext.bind(Set.of(otherWorkspaceId));
                assertThat(documentMapper.softDelete(docId, Instant.now())).isZero();
            } finally {
                WorkspaceScopeContext.clear();
            }

            assertThat(WorkspaceScopeContext.unscoped(() -> documentMapper.findByPublicId(workspaceId, doc)))
                    .isPresent();
        }

        @Test
        @DisplayName("unscoped 只关闭过滤、不吞掉异常：它是给防线自身的测试用的")
        void unscopedIsNotAnEscapeHatchForErrors() throws IOException, InterruptedException {
            TestAccount owner = registerAccount(nextIp());
            String workspace = createWorkspace(owner.tokens().access());
            long workspaceId = workspaceIdOf(workspace);

            // 在 unscoped 里抛异常，异常必须原样传出（finally 清理不能掩盖它）
            assertThat(catchThrowableOf(() -> WorkspaceScopeContext.unscoped(() -> {
                throw new IllegalStateException("boom");
            }))).isInstanceOf(IllegalStateException.class);

            // 并且绕过标记必须已经被清掉
            assertThat(WorkspaceScopeContext.isBypassed()).isFalse();
            assertThat(WorkspaceScopeContext.current()).isEmpty();
            assertThat(workspaceId).isPositive();
        }

        /**
         * 执行一段会抛异常的代码并返回它抛出的异常。
         *
         * @param action 待执行的代码
         * @return 抛出的异常
         */
        private Throwable catchThrowableOf(Runnable action) {
            try {
                action.run();
                return null;
            } catch (Throwable thrown) {
                return thrown;
            }
        }
    }
}
