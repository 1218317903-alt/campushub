package ai.camphub.workspace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import ai.camphub.workspace.domain.ScopedTable;
import ai.camphub.workspace.domain.Unscoped;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 强制"每一条碰私有数据的查询都必须显式回答一个问题"：
 * <b>它是否接受第三层防线的自动空间过滤？</b>
 *
 * <h2>它挡的是哪一种失误</h2>
 * 第三层防线（{@code WorkspaceScopeInterceptor}）的规则是"只改写被标注的方法"。
 * 于是"作者忘了标注"与"作者有意不标注"在代码里长得一模一样 ——
 * 都会让那条查询不受过滤，而区别只存在于作者的脑子里。
 * 手工评审无法可靠地区分这两者，因为一个涉及私有表的 Mapper 方法不被过滤
 * <b>不会引起任何编译警告或运行错误</b>，它只会安静地返回别人的数据。
 *
 * <p>因此规则被改成二选一：涉及 {@code workspace} / {@code workspace_member} /
 * {@code workspace_invite} / {@code document} / {@code note} 这五张表的
 * {@code select} / {@code update} / {@code delete} 语句，其 Mapper 方法
 * <b>必须</b>标注 {@link ScopedTable} 或 {@link Unscoped}（后者要写理由）。
 * 忘记标注 = 构建失败。
 *
 * <h2>为什么还要断言"语句与方法一一对应"</h2>
 * 这一层防线靠方法上的注解生效，而注解靠方法名与 XML 里的语句 id 对应。
 * 两边只要有一边多出来，就会产生一类安静的问题：
 * <ul>
 *   <li><b>XML 里有语句、接口里没有方法</b>：那条语句永远不会被执行
 *       （或者反过来，接口里有方法、XML 里没有 id，启动时才会报
 *       "Invalid bound statement"）。前者更危险 —— 它看起来是一段实现，
 *       实际上是一段死代码。</li>
 *   <li>本测试在编写过程中就抓到过一次这种不一致：{@code findRolesOf} 的语句
 *       已经写进 XML，而接口方法与它的 resultMap 都还不存在。</li>
 * </ul>
 *
 * <h2>为什么 INSERT 必须"没有注解"</h2>
 * {@code WorkspaceScopeSql} 会原样放行 INSERT（它没有可追加条件的位置），
 * 因此给 INSERT 标 {@link ScopedTable} 是一个<b>看起来被保护、实际什么都不做</b>的标注。
 * 把它断言成失败，是为了不让"我加了注解所以它是安全的"这种误判留在代码里。
 * INSERT 的安全性来自另一处：空间主键由服务端从上下文取，而不是从请求参数来。
 */
class WorkspaceScopeCoverageTest {

    /** 私有域的表。凡是碰这些表的语句都必须显式回答是否接受自动过滤。 */
    private static final Set<String> PRIVATE_TABLES = Set.of(
            "workspace", "workspace_member", "workspace_invite", "document", "note");

    /** 需要被空间过滤的语句类型。INSERT 不在其中，理由见类注释。 */
    private static final Set<String> RESTRICTABLE_TYPES = Set.of("select", "update", "delete");

    /** Mapper 接口与其 XML 的对应关系。 */
    private static final Map<String, String> MAPPERS = Map.of(
            "ai.camphub.workspace.infrastructure.WorkspaceMapper", "mapper/workspace/WorkspaceMapper.xml",
            "ai.camphub.workspace.infrastructure.WorkspaceMemberMapper",
            "mapper/workspace/WorkspaceMemberMapper.xml",
            "ai.camphub.workspace.infrastructure.WorkspaceInviteMapper",
            "mapper/workspace/WorkspaceInviteMapper.xml",
            "ai.camphub.workspace.infrastructure.NoteMapper", "mapper/workspace/NoteMapper.xml",
            "ai.camphub.workspace.infrastructure.DocumentMapper",
            "mapper/workspace/DocumentMapper.xml");

    /** 匹配 {@code <select id="x">} 这类开始标签，同时取出语句类型与 id。 */
    private static final Pattern STATEMENT = Pattern.compile(
            "<(select|insert|update|delete)\\b[^>]*\\bid=\"([^\"]+)\"");

    /**
     * 唯一的主断言：语句类型与方法注解必须成对。
     *
     * @throws IOException           语句所属的 XML 读不出来
     * @throws ClassNotFoundException Mapper 接口不在类路径上
     */
    @Test
    @DisplayName("每条碰私有表的查询都必须显式声明是否接受自动空间过滤")
    void everyStatementMustDeclareItsScoping() throws IOException, ClassNotFoundException {
        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, String> entry : MAPPERS.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .toList()) {
            Class<?> mapperType = Class.forName(entry.getKey());
            Map<String, String> statements = readStatements(entry.getValue());

            assertThat(statements)
                    .as("%s 应当至少定义一条语句", entry.getValue())
                    .isNotEmpty();

            // 方向一：XML 里的每条语句都要有对应方法（否则是一段永远不会被执行的实现）
            Set<String> methodNames = declaredMethodNames(mapperType);
            for (String id : statements.keySet()) {
                if (!methodNames.contains(id)) {
                    problems.add(entry.getValue() + " 定义了语句 " + id
                            + "，但 " + mapperType.getSimpleName() + " 里没有同名方法");
                }
            }

            // 方向二：每个方法都要有对应语句（否则启动时才会报 Invalid bound statement）
            for (String name : methodNames) {
                if (!statements.containsKey(name)) {
                    problems.add(mapperType.getSimpleName() + "." + name + " 没有对应的语句定义");
                }
            }

            // 方向三：语句类型与注解必须匹配
            for (Map.Entry<String, String> stmt : statements.entrySet()) {
                String id = stmt.getKey();
                String type = stmt.getValue();
                if (!methodNames.contains(id)) {
                    continue;
                }
                Method method = findMethod(mapperType, id);
                boolean scoped = method.isAnnotationPresent(ScopedTable.class);
                boolean unscoped = method.isAnnotationPresent(Unscoped.class);

                if (RESTRICTABLE_TYPES.contains(type)) {
                    if (!scoped && !unscoped) {
                        problems.add(mapperType.getSimpleName() + "." + id
                                + "（" + type + "）既没有 @ScopedTable 也没有 @Unscoped —— "
                                + "这条查询会绕过第三层防线，而它是否碰私有数据无法从代码上判断");
                    }
                    if (scoped && unscoped) {
                        problems.add(mapperType.getSimpleName() + "." + id
                                + " 同时标注了 @ScopedTable 与 @Unscoped —— 两者互斥，"
                                + "同时存在时执行结果取决于拦截器读注解的顺序");
                    }
                    if (unscoped && method.getAnnotation(Unscoped.class).reason().isBlank()) {
                        problems.add(mapperType.getSimpleName() + "." + id + " 的 @Unscoped 没有写理由");
                    }
                } else if (scoped || unscoped) {
                    problems.add(mapperType.getSimpleName() + "." + id
                            + "（" + type + "）被标注了空间过滤注解，但 " + type
                            + " 语句会被原样放行 —— 这个注解不会产生任何效果，"
                            + "留着它只会让人以为这条语句已经被保护");
                }
            }
        }

        assertThat(problems)
                .as("""
                        第三层防线的覆盖检查未通过。每一条 select / update / delete 都必须二选一：
                          · 接受自动空间过滤 -> 标注 @ScopedTable（列名默认 workspace_id）
                          · 不接受          -> 标注 @Unscoped 并写下理由
                        INSERT 不加任何注解：它会被原样放行，标注只会制造"已被保护"的错觉。""")
                .isEmpty();
    }

    /**
     * 五张私有表都真的出现过 —— 否则上面的检查可能因为语句集合为空而形同虚设。
     *
     * @throws IOException XML 读不出来
     */
    @Test
    @DisplayName("检查本身有效：五张私有表都能在 Mapper XML 里被找到")
    void privateTablesAreActuallyPresent() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String location : MAPPERS.values()) {
            all.append(readResource(location));
        }

        for (String table : PRIVATE_TABLES) {
            assertThat(all.toString())
                    .as("表 %s 应当被至少一条语句从 FROM 中引用；"
                            + "若它已改名，请同步更新本测试的清单 —— "
                            + "否则这条检查会在表被改名后继续通过，而它本来要防的正是那种情况", table)
                    .contains("FROM " + table);
        }
    }

    /**
     * 读出 XML 里定义的语句。
     *
     * @param classpathLocation 类路径位置
     * @return 语句 id → 语句类型（select / insert / update / delete）
     * @throws IOException 读不出来
     */
    private static Map<String, String> readStatements(String classpathLocation) throws IOException {
        String xml = readResource(classpathLocation);
        Map<String, String> statements = new LinkedHashMap<>();
        Matcher matcher = STATEMENT.matcher(xml);
        while (matcher.find()) {
            statements.put(matcher.group(2), matcher.group(1).toLowerCase());
        }
        return statements;
    }

    /**
     * 读类路径上的文本资源。
     *
     * @param classpathLocation 位置
     * @return 内容
     * @throws IOException 读不出来
     */
    private static String readResource(String classpathLocation) throws IOException {
        try (InputStream in = WorkspaceScopeCoverageTest.class.getClassLoader()
                .getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new AssertionError("类路径上找不到资源：" + classpathLocation);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 取接口自己声明的抽象方法名（不含 Object 继承来的与 default 方法）。
     *
     * @param mapperType Mapper 接口
     * @return 方法名集合
     */
    private static Set<String> declaredMethodNames(Class<?> mapperType) {
        Set<String> names = new TreeSet<>();
        for (Method method : mapperType.getDeclaredMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) && !method.isSynthetic()) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /**
     * 按名字取方法。
     *
     * @param mapperType Mapper 接口
     * @param name       方法名
     * @return 方法
     */
    private static Method findMethod(Class<?> mapperType, String name) {
        for (Method method : mapperType.getDeclaredMethods()) {
            if (method.getName().equals(name) && !method.isSynthetic()) {
                return method;
            }
        }
        throw new AssertionError("找不到方法：" + mapperType.getSimpleName() + "." + name);
    }
}
