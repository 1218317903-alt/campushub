package ai.camphub.workspace.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 权限矩阵的逐格断言。
 *
 * <h2>为什么要为一张"配置表"写测试</h2>
 * {@link WorkspaceAction} 里的角色集合看起来只是数据，不值得测试。
 * 但它是本阶段<b>唯一</b>决定"谁能做什么"的地方 —— 业务代码里没有第二处权限判断
 * （那是刻意的设计）。因此它一旦被改错，改动不会在任何地方引起编译失败，
 * 只会让某个角色多出或少掉一项能力。
 *
 * <p>断言写成"逐格"而不是"抽查几个"，是因为矩阵的失效方式恰恰是<b>单格</b>失效：
 * 有人为了让某个接口能跑通，往 {@code allowedRoles} 里添了一个角色，
 * 而那次改动的理由在两周后无从追溯。这里每加进一格都要同时改断言，
 * 于是"改动矩阵"这件事无法悄悄发生。
 *
 * <h2>它同时是文档的事实来源</h2>
 * {@code docs/resource-authorization.md} 里的那张表与此处的断言必须一致。
 * 两者不一致时以这里为准 —— 因为这里会跑。
 */
class WorkspaceActionTest {

    /**
     * 逐个动作核对允许的角色。
     */
    @Nested
    @DisplayName("角色矩阵")
    class RoleMatrix {

        @Test
        @DisplayName("读取空间：三种身份都可以，NONE 一律拒绝")
        void readWorkspace() {
            assertThat(allowed(WorkspaceAction.READ_WORKSPACE))
                    .containsExactlyInAnyOrder(
                            WorkspaceRoleInContext.OWNER,
                            WorkspaceRoleInContext.ADMIN,
                            WorkspaceRoleInContext.MEMBER);
        }

        @Test
        @DisplayName("修改与删除空间：只有拥有者 —— 改可见性等于改'谁能看到这个空间'")
        void ownerOnlyOnWorkspaceSettings() {
            assertThat(allowed(WorkspaceAction.UPDATE_WORKSPACE))
                    .containsExactly(WorkspaceRoleInContext.OWNER);
            assertThat(allowed(WorkspaceAction.DELETE_WORKSPACE))
                    .containsExactly(WorkspaceRoleInContext.OWNER);
        }

        @Test
        @DisplayName("成员管理（邀请、移除、改角色）：拥有者与管理员")
        void memberManagement() {
            for (WorkspaceAction action : new WorkspaceAction[]{
                    WorkspaceAction.INVITE_MEMBER,
                    WorkspaceAction.REMOVE_MEMBER,
                    WorkspaceAction.UPDATE_MEMBER_ROLE}) {
                assertThat(allowed(action))
                        .as("%s 的允许角色", action)
                        .containsExactlyInAnyOrder(
                                WorkspaceRoleInContext.OWNER,
                                WorkspaceRoleInContext.ADMIN);
            }
        }

        @Test
        @DisplayName("笔记与文档：全体成员都能读写 —— 这是空间存在的意义本身")
        void contentActionsAreOpenToEveryMember() {
            for (WorkspaceAction action : new WorkspaceAction[]{
                    WorkspaceAction.READ_NOTES,
                    WorkspaceAction.CREATE_NOTE,
                    WorkspaceAction.UPDATE_NOTE,
                    WorkspaceAction.DELETE_NOTE,
                    WorkspaceAction.READ_DOCUMENTS,
                    WorkspaceAction.UPLOAD_DOCUMENT,
                    WorkspaceAction.DELETE_DOCUMENT,
                    WorkspaceAction.DOWNLOAD_DOCUMENT}) {
                assertThat(allowed(action))
                        .as("%s 的允许角色", action)
                        .containsExactlyInAnyOrder(
                                WorkspaceRoleInContext.OWNER,
                                WorkspaceRoleInContext.ADMIN,
                                WorkspaceRoleInContext.MEMBER);
            }
        }

        @Test
        @DisplayName("NONE 永远不被任何动作允许 —— 它是'不是成员'，不是一个受限身份")
        void noneIsNeverAllowed() {
            for (WorkspaceAction action : WorkspaceAction.values()) {
                assertThat(action.allows(WorkspaceRoleInContext.NONE))
                        .as("%s 不应允许 NONE", action)
                        .isFalse();
            }
        }

        /**
         * 取某个动作允许的角色集合。
         *
         * @param action 动作
         * @return 角色集合
         */
        private java.util.Set<WorkspaceRoleInContext> allowed(WorkspaceAction action) {
            return action.allowedRoles();
        }
    }

    /**
     * 归属维度的判定。
     *
     * <p>它回答的是"这条资源是不是我创建的"如何参与判定。当前只有删除类动作会用到它，
     * 因此下面所有关于"忽略归属"的断言同时也是"哪些动作不该去看归属"的断言。
     */
    @Nested
    @DisplayName("归属维度")
    class Ownership {

        @Test
        @DisplayName("成员可以删自己创建的，不能删别人的")
        void memberDeletesOnlyOwn() {
            assertThat(WorkspaceAction.DELETE_NOTE.allows(WorkspaceRoleInContext.MEMBER, true)).isTrue();
            assertThat(WorkspaceAction.DELETE_NOTE.allows(WorkspaceRoleInContext.MEMBER, false)).isFalse();
            assertThat(WorkspaceAction.DELETE_DOCUMENT.allows(WorkspaceRoleInContext.MEMBER, true)).isTrue();
            assertThat(WorkspaceAction.DELETE_DOCUMENT.allows(WorkspaceRoleInContext.MEMBER, false)).isFalse();
        }

        @Test
        @DisplayName("拥有者与管理员可以删任何一条 —— '收拾烂摊子'正是这个角色的职责")
        void ownerAndAdminDeleteAny() {
            for (WorkspaceRoleInContext role : new WorkspaceRoleInContext[]{
                    WorkspaceRoleInContext.OWNER, WorkspaceRoleInContext.ADMIN}) {
                for (WorkspaceAction action : new WorkspaceAction[]{
                        WorkspaceAction.DELETE_NOTE, WorkspaceAction.DELETE_DOCUMENT}) {
                    assertThat(action.allows(role, false))
                            .as("%s 对 %s 删除他人内容", role, action)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("不区分归属的动作忽略 ownedBySelf —— 两个取值结论相同")
        void nonDeleteActionsIgnoreOwnership() {
            for (WorkspaceAction action : WorkspaceAction.values()) {
                if (action.isOwnershipSensitive()) {
                    continue;
                }
                for (WorkspaceRoleInContext role : WorkspaceRoleInContext.values()) {
                    assertThat(action.allows(role, true))
                            .as("%s / %s 的归属敏感性不一致", action, role)
                            .isEqualTo(action.allows(role, false));
                }
            }
        }

        @Test
        @DisplayName("只有删除类动作是归属敏感的")
        void onlyDeleteActionsAreOwnershipSensitive() {
            assertThat(java.util.Arrays.stream(WorkspaceAction.values())
                    .filter(WorkspaceAction::isOwnershipSensitive)
                    .toList())
                    .containsExactlyInAnyOrder(
                            WorkspaceAction.DELETE_NOTE,
                            WorkspaceAction.DELETE_DOCUMENT);
        }

        @Test
        @DisplayName("归属不该成为'不是成员'的通行证")
        void ownershipDoesNotGrantAccessToOutsiders() {
            // 这是最容易写错的一格：ownedBySelf=true 时若直接 return true，
            // 一个已被移出空间的作者仍能删掉自己当年写的笔记。
            for (WorkspaceAction action : WorkspaceAction.values()) {
                assertThat(action.allows(WorkspaceRoleInContext.NONE, true))
                        .as("%s 不应因 ownedBySelf=true 而放行 NONE", action)
                        .isFalse();
            }
        }
    }
}
