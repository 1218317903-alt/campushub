package ai.camphub.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块边界与分层规则的自动化校验。
 *
 * <h2>为什么必须有这个测试</h2>
 * "模块化单体"是架构决策，但决策本身不会自动维持。项目推进几个月后，一次"先这样调用一下"
 * 的临时跨模块访问，就会让边界名存实亡 —— 而那时候再想拆已经来不及。
 * 把边界写成构建期的断言，意味着**越界会直接让构建失败**，而不是靠代码评审时有人恰好记得。
 *
 * <p>这里不做"完美分层"的教条检查（那会逼出为满足规则而写的空壳类），只拦截
 * 真正会造成长期损害的四类越界：分层反向依赖、控制器直连数据层、domain 被框架污染、
 * 以及依赖注入方式退化。
 *
 * <h2>规则会随模块增加自动生效</h2>
 * 包名模式（{@code ..api..} / {@code ..domain..} / {@code ..infrastructure..}）是通配的，
 * Phase 02 起新增 identity / community 等模块时无需修改本测试，规则自动覆盖。
 */
@AnalyzeClasses(
        packages = "ai.camphub",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    /**
     * 业务域模块清单（与 docs/00-工程规约.md §1 的业务域一致）。
     *
     * <p>集中声明在这里有两个作用：一是让 {@link #commonMustNotDependOnAnyBusinessModule()} 能精确表达
     * "common 是底层、不许引用任何一个业务域"；二是这份清单本身就是"本项目有哪些模块"的
     * 单一事实来源，避免文档里写了、代码里没建。
     */
    private static final String[] BUSINESS_MODULES = {
            "ai.camphub.identity..",
            "ai.camphub.workspace..",
            "ai.camphub.community..",
            "ai.camphub.discover..",
            "ai.camphub.ingestion..",
            "ai.camphub.search..",
            "ai.camphub.ai..",
            "ai.camphub.notification..",
            "ai.camphub.moderation..",
            "ai.camphub.admin..",
            "ai.camphub.platform..",
            "ai.camphub.system.."
    };

    /**
     * 模块之间不得形成循环依赖。
     *
     * <p>循环依赖是"模块化"退化为"大泥球"的最早信号：一旦 A↔B，二者实际上已经是一个模块，
     * 只是被拆成了两个包。此时任何一侧的改动都会波及另一侧，隔离收益归零。
     */
    @ArchTest
    static final ArchRule modulesMustNotHaveCyclicDependencies =
            com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices()
                    .matching("ai.camphub.(*)..")
                    .should().beFreeOfCycles()
                    .because("模块间循环依赖会让边界失效，并使后续任何拆分都无从下手");

    /**
     * common 是共享内核，必须位于依赖图最底层，不得反向依赖任何业务域。
     *
     * <p>违反它意味着 common 里混入了业务逻辑，之后每个模块都被迫依赖 common 中
     * 与自己无关的业务代码 —— 这正是"common 变成万能垃圾桶"的典型路径。
     */
    @ArchTest
    static final ArchRule commonMustNotDependOnAnyBusinessModule =
            noClasses()
                    .that().resideInAPackage("ai.camphub.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(BUSINESS_MODULES)
                    .because("common 是共享内核，只能被依赖、不能依赖业务模块");

    /**
     * Controller 不得直接访问数据层。
     *
     * <p>控制器直连 Mapper 会把"权限校验、事务边界、业务规则"这三件事从链路中抹掉。
     * 权限校验发生在应用服务层，跳过它就意味着接口天然缺少鉴权 —— 这是 IDOR 类漏洞的常见成因。
     */
    @ArchTest
    static final ArchRule controllersMustNotAccessPersistenceLayer =
            noClasses()
                    .that().resideInAPackage("..api..")
                    .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..")
                    .because("控制器必须通过应用服务访问数据，否则会绕过权限与事务边界");

    /**
     * domain 层必须保持纯净：不依赖任何 Spring 组件。
     *
     * <p>领域模型一旦开始用 {@code @Component}、{@code @Transactional} 之类的注解，
     * 就无法脱离 Spring 容器被单独测试与复用，领域逻辑会逐渐被框架细节渗透。
     */
    @ArchTest
    static final ArchRule domainMustStayFrameworkFree =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                    .because("领域模型应可在不启动 Spring 的情况下被直接测试与复用");

    /**
     * 所有 REST 控制器必须位于 {@code ..api..} 包内。
     *
     * <p>否则上述"控制器不得访问数据层"等规则会因为包名不匹配而形同虚设 ——
     * 规则的有效性依赖命名一致性。
     */
    @ArchTest
    static final ArchRule restControllersMustResideInApiPackage =
            classes()
                    .that().areAnnotatedWith(RestController.class)
                    .should().resideInAPackage("..api..")
                    .because("控制器统一放置，边界规则才能被可靠匹配");

    /**
     * 组装点（{@code bootstrap}）只能依赖业务模块，不能被业务模块依赖。
     *
     * <p>它位于依赖图的最顶端：它的职责是把各模块的公开能力组装成一个可运行的应用
     * （如生成演示数据）。一旦某个业务模块反过来依赖它，就等于业务代码依赖了
     * "应用如何被组装起来"这一事实 —— 那个模块从此无法被单独理解，
     * 而且立刻形成循环依赖的前置条件。
     *
     * <p>这条断言同时也是"别把业务逻辑写进 bootstrap"的守卫：它里面的类
     * 一旦被别处引用，构建就会失败。
     *
     * <p>排除 {@code ai.camphub} 这一层是因为启动类与配置类在那里，
     * 它们属于组装的一部分而不是业务模块。ArchUnit 的包匹配不含子包，
     * 因此这一条只排除恰好位于 {@code ai.camphub} 包下的类。
     */
    @ArchTest
    static final ArchRule bootstrapMustNotBeDependedOnByBusinessModules =
            noClasses()
                    .that().resideOutsideOfPackages("ai.camphub.bootstrap..", "ai.camphub")
                    .should().dependOnClassesThat().resideInAPackage("ai.camphub.bootstrap..")
                    .because("组装点位于依赖图顶端，被业务模块依赖即为反向依赖");

    /**
     * 禁止字段注入。
     *
     * <p>字段注入隐藏了依赖关系、使对象无法在容器外被构造（测试只能靠反射或多起一个 Spring 上下文）、
     * 并让依赖可以被随意改写。构造器注入让这些问题消失。
     */
    @ArchTest
    static final ArchRule noFieldInjection =
            noFields()
                    .that().areDeclaredInClassesThat().resideInAPackage("ai.camphub..")
                    .should().beAnnotatedWith(Autowired.class)
                    .because("构造器注入能让依赖显式可见、对象可测试，且可声明为 final");
}
