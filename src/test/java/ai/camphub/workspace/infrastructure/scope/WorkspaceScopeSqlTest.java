package ai.camphub.workspace.infrastructure.scope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 第三层防线 SQL 改写的逐形状覆盖。
 *
 * <h2>为什么这一层要按"语句形状"而不是按"业务场景"测</h2>
 * 这是一个纯函数，它的输入空间是 SQL 的<b>文本形状</b>，而不是业务语义。
 * 而它最容易出错的地方恰好是形状的组合：语句以 {@code ORDER BY} 结尾、
 * 带 {@code LIMIT}、根本没有 {@code WHERE}、以分号结尾、带 {@code GROUP BY}。
 * 用业务场景去测，覆盖到的永远是那几个"恰好写到过的形状"。
 *
 * <h2>它最需要被覆盖的一条：空集</h2>
 * {@link WorkspaceScopeSql#apply} 里最容易写错、后果也最严重的分支是
 * "授权集合为空"。空集在 SQL 里的自然表达 {@code IN ()} 是语法错误，
 * 而为了绕开语法错误而"集合为空就不追加条件"，会让防线在
 * <b>调用者一个空间都没有时精确地失效</b> —— 那恰好是攻击者期望的状态
 * （令牌被撤销、成员被移出之后）。这里把 {@code 1 = 0} 钉成断言。
 */
class WorkspaceScopeSqlTest {

    /** 绝大多数查询用的列。 */
    private static final String COLUMN = "workspace_id";

    /**
     * 空集与 INSERT。
     */
    @Nested
    @DisplayName("边界")
    class Boundaries {

        @Test
        @DisplayName("授权集合为空时追加 1 = 0，而不是不追加 —— 前者查不到任何行，后者不设限")
        void emptySetBecomesNeverTrue() {
            String rewritten = WorkspaceScopeSql.apply(
                    "SELECT * FROM note WHERE deleted_at IS NULL", COLUMN, Set.of());

            assertThat(rewritten)
                    .contains("AND 1 = 0")
                    .doesNotContain("IN ()");
        }

        @Test
        @DisplayName("没有 WHERE 时用 WHERE 而不是 AND")
        void usesWhereWhenThereIsNone() {
            String rewritten = WorkspaceScopeSql.apply(
                    "SELECT COUNT(*) FROM workspace", COLUMN, List.of(7L));

            assertThat(rewritten).isEqualTo("SELECT COUNT(*) FROM workspace WHERE workspace_id IN (7) ");
        }

        @Test
        @DisplayName("已有 WHERE 时用 AND")
        void usesAndWhenWhereExists() {
            String rewritten = WorkspaceScopeSql.apply(
                    "SELECT * FROM note WHERE deleted_at IS NULL", COLUMN, List.of(7L));

            assertThat(rewritten).isEqualTo(
                    "SELECT * FROM note WHERE deleted_at IS NULL AND workspace_id IN (7) ");
        }

        @Test
        @DisplayName("从句中的 WHERE 也算数 —— 子查询里的 WHERE 属于整条语句，用 WHERE 会拼出两句 WHERE")
        void whereInsideSubqueryCounts() {
            String sql = "SELECT w.id FROM workspace w "
                    + "WHERE w.owner_id = ? OR EXISTS (SELECT 1 FROM workspace_member m WHERE m.user_id = ?)";
            String rewritten = WorkspaceScopeSql.apply(sql, "w.id", List.of(3L));

            assertThat(rewritten).endsWith("AND w.id IN (3) ");
            // 关键断言：整条语句里 WHERE 只出现两次（外层一次 + 子查询一次），没有第三次
            assertThat(rewritten.split("(?i)\\bwhere\\b", -1)).hasSize(3);
        }

        @Test
        @DisplayName("INSERT 原样返回 —— 它没有可追加条件的位置")
        void insertIsPassedThrough() {
            String sql = "INSERT INTO note (title) VALUES (?)";

            assertThat(WorkspaceScopeSql.apply(sql, COLUMN, List.of(1L))).isEqualTo(sql);
            // 空集时也必须原样放行，否则新建数据会凭空失败
            assertThat(WorkspaceScopeSql.apply(sql, COLUMN, Set.of())).isEqualTo(sql);
        }

        @Test
        @DisplayName("null 与空白原样返回，不抛异常")
        void nullAndBlankAreSafe() {
            assertThat(WorkspaceScopeSql.apply(null, COLUMN, Set.of())).isNull();
            assertThat(WorkspaceScopeSql.apply("   ", COLUMN, Set.of())).isEqualTo("   ");
        }
    }

    /**
     * 插入位置：必须落在会改变语句结构的尾部子句之前。
     */
    @Nested
    @DisplayName("插入位置")
    class InsertionPoint {

        @Test
        @DisplayName("条件落在 ORDER BY 之前 —— 放在之后会变成语法错误")
        void beforeOrderBy() {
            String rewritten = WorkspaceScopeSql.apply(
                    "SELECT * FROM note WHERE deleted_at IS NULL ORDER BY updated_at DESC", COLUMN, List.of(2L));

            assertThat(rewritten).isEqualTo(
                    "SELECT * FROM note WHERE deleted_at IS NULL AND workspace_id IN (2) ORDER BY updated_at DESC");
        }

        @Test
        @DisplayName("条件落在 LIMIT 之前")
        void beforeLimit() {
            String rewritten = WorkspaceScopeSql.apply(
                    "SELECT * FROM note LIMIT ? OFFSET ?", COLUMN, List.of(2L));

            assertThat(rewritten).isEqualTo(
                    "SELECT * FROM note WHERE workspace_id IN (2) LIMIT ? OFFSET ?");
        }

        @Test
        @DisplayName("ORDER BY 与 LIMIT 同时存在时落在最靠前的那个之前")
        void beforeTheEarliestTrailingClause() {
            String rewritten = WorkspaceScopeSql.apply(
                    "SELECT * FROM note ORDER BY id DESC LIMIT ? OFFSET ?", COLUMN, List.of(2L));

            assertThat(rewritten).isEqualTo(
                    "SELECT * FROM note WHERE workspace_id IN (2) ORDER BY id DESC LIMIT ? OFFSET ?");
        }

        @Test
        @DisplayName("GROUP BY 与 HAVING 同样被视为尾部子句")
        void beforeGroupByAndHaving() {
            String sql = "SELECT workspace_id, COUNT(*) FROM note GROUP BY workspace_id HAVING COUNT(*) > 1";
            String rewritten = WorkspaceScopeSql.apply(sql, COLUMN, List.of(2L));

            assertThat(rewritten).isEqualTo(
                    "SELECT workspace_id, COUNT(*) FROM note WHERE workspace_id IN (2) "
                            + "GROUP BY workspace_id HAVING COUNT(*) > 1");
        }

        @Test
        @DisplayName("FOR UPDATE 之前 —— 放在之后会让锁的范围与过滤的范围不一致")
        void beforeForUpdate() {
            String rewritten = WorkspaceScopeSql.apply(
                    "SELECT * FROM workspace WHERE id = ? FOR UPDATE", COLUMN, List.of(2L));

            assertThat(rewritten).isEqualTo(
                    "SELECT * FROM workspace WHERE id = ? AND workspace_id IN (2) FOR UPDATE");
        }

        @Test
        @DisplayName("以分号结尾时插在分号之前")
        void beforeSemicolon() {
            String rewritten = WorkspaceScopeSql.apply(
                    "DELETE FROM note WHERE id = ?;", COLUMN, List.of(2L));

            assertThat(rewritten).isEqualTo("DELETE FROM note WHERE id = ? AND workspace_id IN (2) ;");
        }

        @Test
        @DisplayName("head 末尾不是空白时补一个空格 —— 否则会拼出 ?AND 这种靠词法巧合成立的语句")
        void insertsSeparatorWhenHeadHasNoTrailingWhitespace() {
            String rewritten = WorkspaceScopeSql.apply(
                    "SELECT * FROM note WHERE id = ?", COLUMN, List.of(2L));

            // 不是 "?AND"：两者都是合法记号，但那样写等于依赖 MySQL 的词法规则
            assertThat(rewritten).doesNotContain("?AND");
            assertThat(rewritten).isEqualTo("SELECT * FROM note WHERE id = ? AND workspace_id IN (2) ");
        }
    }

    /**
     * 取值的内联方式。
     */
    @Nested
    @DisplayName("取值")
    class Values {

        @Test
        @DisplayName("多个空间主键用逗号连接")
        void multipleIdsAreCommaSeparated() {
            String rewritten = WorkspaceScopeSql.apply(
                    "SELECT * FROM note", COLUMN, List.of(3L, 11L, 42L));

            assertThat(rewritten).isEqualTo("SELECT * FROM note WHERE workspace_id IN (3, 11, 42) ");
        }

        @Test
        @DisplayName("列可以带表限定符")
        void columnSupportsTableQualifier() {
            String rewritten = WorkspaceScopeSql.apply(
                    "SELECT w.id FROM workspace w", "w.id", List.of(5L));

            assertThat(rewritten).isEqualTo("SELECT w.id FROM workspace w WHERE w.id IN (5) ");
        }

        @Test
        @DisplayName("取值来自库内主键因此直接内联 —— 这条断言是'不存在注入面'这个前提的守卫")
        void valuesAreInlinedNumbersOnly() {
            // 输入只可能是 Long（编译期约束）。这里断言的是输出形状：
            // 若将来有人把入参改成字符串，这条断言会立刻变红，
            // 因为那时 `IN ('a')` 里的引号会出现在结果里。
            String rewritten = WorkspaceScopeSql.apply("SELECT * FROM note", COLUMN, List.of(1L));

            assertThat(rewritten).doesNotContain("'");
        }
    }

    /**
     * 语句类型识别。
     */
    @Nested
    @DisplayName("语句类型")
    class StatementType {

        @Test
        @DisplayName("小写与前置空白不影响识别")
        void caseAndLeadingWhitespaceInsensitive() {
            assertThat(WorkspaceScopeSql.apply("\n   update note set title = ?", COLUMN, List.of(1L)))
                    .startsWith("\n   update note set title = ? WHERE workspace_id IN (1)");
            assertThat(WorkspaceScopeSql.apply("delete from note", COLUMN, List.of(1L)))
                    .isEqualTo("delete from note WHERE workspace_id IN (1) ");
        }

        @Test
        @DisplayName("DDL 原样返回")
        void ddlIsPassedThrough() {
            String sql = "ALTER TABLE note ADD COLUMN foo INT";

            assertThat(WorkspaceScopeSql.apply(sql, COLUMN, List.of(1L))).isEqualTo(sql);
        }
    }
}
