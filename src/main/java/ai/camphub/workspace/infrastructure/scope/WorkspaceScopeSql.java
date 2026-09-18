package ai.camphub.workspace.infrastructure.scope;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 把一个 SQL 语句改写成"只在调用者被授权的空间范围内执行"的语句。
 *
 * <h2>为什么是一个独立的、纯函数的类</h2>
 * SQL 文本改写是这一类实现里最容易出错、也最难测的部分：它要面对
 * "语句以 ORDER BY 结尾""没有 WHERE""带 LIMIT"这些组合。
 * 把它从拦截器里拆成一个静态纯函数，意味着这些组合可以用普通单元测试逐条覆盖，
 * 而不必每种组合都去起一个数据库。拦截器本身因此只剩一件事：
 * 从 MyBatis 的参数里把需要的东西取出来，然后调用这里。
 *
 * <h2>核心决定：默认注入，例外显式声明</h2>
 * 本类的规则是"<b>只要被调用就追加条件</b>"，而不是"先判断作者有没有写过过滤，
 * 没写才追加"。后一种看起来更聪明，但它有一个致命后果：
 * <b>判断失误时防线静默失效</b> —— 语句照跑、测试照绿，只是不再过滤，
 * 而任何一次"判断逻辑写松了"的回归都不会有人发现。
 *
 * <p>更具体地说，"已经写过过滤了吗"这件事在 SQL 文本上根本无法可靠判断：
 * 以 {@code workspace} 表为例，它要限定的列是 {@code id}，
 * 而 {@code SELECT w.id, w.public_id FROM workspace w} 里当然有 {@code id} ——
 * 把"出现"当成"已限定"，这条查询就会被判定为安全，而它没有任何范围限制。
 * 即使把判据收紧到"比较位置"（{@code = < > IN ( IS}），
 * {@code w.id IN (SELECT ...)} 这种子查询里的比较又会造成误判。
 *
 * <p>因此判据交给人来写、由 {@code @Unscoped} 显式声明，而
 * {@code WorkspaceScopeCoverageTest} 强制"每条碰私有表的查询都必须二选一"。
 * 代价是那些本来就显式限定了 workspace_id 的语句会被追加一条语义重复的条件 ——
 * 这条条件的执行代价是一次主键/首列 IN 判断，而它换来的是
 * "这一层的行为不依赖任何启发式判断"。
 *
 * <h2>改写规则</h2>
 * <ol>
 *   <li>不是 SELECT / UPDATE / DELETE（例如 INSERT）—— 原样返回。
 *       INSERT 没有可追加条件的位置，它的安全性由"空间主键从上下文来"保证。</li>
 *   <li>授权集合为空 —— 追加 {@code 1 = 0}。<b>这是本类最重要的一条</b>：
 *       空集在 SQL 里的自然表达是 {@code IN ()}，而那是语法错误；
 *       若为了避开语法错误而"集合为空就不追加条件"，这条防线在调用者一个空间都没有时
 *       会精确地失效 —— 而那恰好是攻击者期望的状态（令牌被撤销、成员被移出之后）。
 *       {@code 1 = 0} 让"没有授权"落成"查不到任何行"，而不是"不设限"。</li>
 * </ol>
 *
 * <h2>为什么把主键直接内联成数字，而不是绑定参数</h2>
 * 集合元素的绑定需要在 {@code BoundSql} 上做三处反射改写（参数映射列表、
 * 附加参数、以及把 {@code ?} 展开成若干个占位符），其中任何一处写错都会在运行时
 * 产生"参数个数不匹配"这种难定位的错误。这里内联的取值是<b>库内自增主键</b>，
 * 只可能是数字，不是用户输入 —— 因此不存在注入面。
 *
 * <p>这条推理有一个必须写下来的前提：<b>本类的输入永远来自库内主键</b>。
 * 若将来有人想让 {@code column IN (...)} 的取值来自请求参数，必须先改成绑定参数，
 * 否则就是一个直接的 SQL 注入点。
 *
 * <h2>局限（已知且刻意接受）</h2>
 * 插入位置靠"第一个 ORDER BY / GROUP BY / HAVING / LIMIT / FOR UPDATE 之前"这个启发式判断。
 * 它对本项目的全部手写 SQL 都成立（都是单层、无 UNION），
 * 并由 {@code WorkspaceScopeSqlTest} 逐条覆盖这些形状。
 * 一旦出现 UNION 或需要在外层包裹子查询的语句，必须先扩展这里并补测试，
 * 而不是假设它还能用。
 */
public final class WorkspaceScopeSql {

    /** 追加条件必须排在它们之前，否则语句语法错误。 */
    private static final Pattern TRAILING_CLAUSE = Pattern.compile(
            "\\b(order\\s+by|group\\s+by|having|limit|for\\s+update)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 判断语句里是否已经有 WHERE。 */
    private static final Pattern WHERE = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);

    /** 只有这三类语句能被追加条件；其余（INSERT / DDL）原样放行。 */
    private static final Pattern RESTRICTABLE_STATEMENT =
            Pattern.compile("^\\s*(select|update|delete)\\b", Pattern.CASE_INSENSITIVE);

    private WorkspaceScopeSql() {
    }

    /**
     * 追加空间范围条件。
     *
     * @param sql           原始 SQL
     * @param column        用于限定的列，可带表限定符（如 {@code w.id}）
     * @param authorizedIds 调用者被授权的空间主键集合，可为空集合
     * @return 改写后的 SQL
     */
    public static String apply(String sql, String column, Collection<Long> authorizedIds) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        if (!RESTRICTABLE_STATEMENT.matcher(sql).find()) {
            return sql;
        }

        String predicate = authorizedIds.isEmpty()
                ? "1 = 0"
                : column + " IN (" + authorizedIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(", ")) + ")";

        int insertAt = insertionPoint(sql);
        String head = sql.substring(0, insertAt);
        String tail = sql.substring(insertAt);

        // head 末尾不一定是空白：没有尾部子句时它就是整条语句，而语句的最后一个字符
        // 可能是 `?` 或 `)`。直接拼接会写出 `?AND workspace_id IN (...)` 这种文本 ——
        // MySQL 的语法分析能认出 `?` 与 `AND` 是两个记号，所以它**看起来能跑**，
        // 但那是一条只能靠"碰巧的词法规则"成立的语句，而它一旦换了数据库或
        // 被复制到别处就会以语法错误暴露。补一个空白，让这条语句在文本层面也是对的。
        String separator = head.isEmpty() || Character.isWhitespace(head.charAt(head.length() - 1))
                ? ""
                : " ";
        String conjunction = WHERE.matcher(head).find() ? "AND " : "WHERE ";
        return head + separator + conjunction + predicate + " " + tail;
    }

    /**
     * 计算追加条件的位置：第一个尾部子句之前，或语句结束处。
     *
     * @param sql SQL
     * @return 下标
     */
    private static int insertionPoint(String sql) {
        Matcher matcher = TRAILING_CLAUSE.matcher(sql);
        if (matcher.find()) {
            return matcher.start();
        }
        int semicolon = sql.lastIndexOf(';');
        return semicolon >= 0 ? semicolon : sql.length();
    }
}
