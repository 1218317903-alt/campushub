package ai.camphub.workspace.app;

import static org.assertj.core.api.Assertions.assertThat;

import ai.camphub.workspace.config.WorkspaceConfig;
import ai.camphub.workspace.config.WorkspaceProperties;
import ai.camphub.workspace.infrastructure.parser.MarkdownDocumentParser;
import ai.camphub.workspace.infrastructure.parser.PdfDocumentParser;
import ai.camphub.workspace.infrastructure.parser.PlainTextDocumentParser;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 解析器注册表与上传白名单的一致性。
 *
 * <h2>它挡的是两类都不报错的缺陷</h2>
 * <ol>
 *   <li><b>解析器支持的类型不在上传白名单里</b>。
 *       那么那个解析器是<b>死代码</b> —— 它支持的内容永远传不进来，
 *       而"系统支持解析 Markdown"这句话从此是一句谎话。
 *       它不会报错，只会表现为"我上传了 md 文件，系统说不支持这种类型"。</li>
 *   <li><b>新增了格式，但忘了扩充 {@code SupportedTypes.CANDIDATES}</b>。
 *       {@code DocumentParsingService} 靠"用一份候选清单去问每个实现"
 *       来建立注册表（理由见该类注释），因此清单没跟上时，
 *       新解析器<b>不会</b>被注册。后果与上一条相反但同样安静：
 *       文件能传进来，然后解析失败说"暂不支持"。</li>
 * </ol>
 *
 * <h2>为什么不要求反方向也成立</h2>
 * "白名单里的每种类型都必须有解析器"是<b>刻意不成立</b>的。
 * 白名单回答的是"哪些内容允许被存下来"，它比"能被读懂"更宽：
 * 压缩包、Office 文档、图片都能存能下载，只是当前读不懂。
 * 要求两者相等，会把白名单变成"能解析什么"的副本 ——
 * 用户连一份 xlsx 都存不进来，而"存下来"本身是有价值的。
 *
 * <h2>为什么这里要数一遍 Bean 方法</h2>
 * 本测试自己构造解析器，因此在"配置里加了第四个解析器"时，
 * 前面的断言<b>不会</b>失败（它压根不知道有第四个）——
 * 一份会随实现漂移而继续通过的测试，比没有测试更糟：
 * 它给人"这里有覆盖"的印象。数一次 {@link WorkspaceConfig} 里
 * 返回 {@link DocumentParser} 的 {@code @Bean} 方法，把这个缺口补上。
 */
class DocumentParsingRegistryTest {

    /** 与 {@link WorkspaceConfig} 里那三个解析器 {@code @Bean} 逐字一致的构造方式。 */
    private static final List<DocumentParser> PARSERS = List.of(
            new PlainTextDocumentParser(),
            new MarkdownDocumentParser(),
            new PdfDocumentParser(4L * 1024 * 1024, 50, 100_000));

    /** 本阶段真正能读懂的格式。新增格式时这里必须一起改 —— 改了才有人被迫回答下面两个问题。 */
    private static final Set<String> PARSEABLE = Set.of(
            "text/plain", "text/markdown", "application/pdf");

    /**
     * 注册表的内容必须恰好是预期的那几种，且每一种都能被上传。
     *
     * @throws IOException application.yml 读不出来
     */
    @Test
    @DisplayName("解析器注册表与上传白名单一致：能读懂的格式都必须能上传")
    void registryAndUploadWhitelistMustAgree() throws IOException {
        DocumentParsingService service = new DocumentParsingService(PARSERS, properties());

        assertThat(service.supportedTypes())
                .as("""
                        注册表与预期不一致。多了一种：可能是 SupportedTypes.CANDIDATES 里
                        加了类型但没有对应的解析器；少了一种：很可能是新增了解析器却
                        忘了往 CANDIDATES 里加它的类型 —— 那种情况下解析器不会被注册。""")
                .containsExactlyInAnyOrderElementsOf(PARSEABLE);

        assertThat(allowedUploadTypes())
                .as("下面这些类型有解析器、却不在 app.workspace.documents.allowed-types 里，"
                        + "因此用户永远传不进来 —— 那些解析器是死代码")
                .containsAll(service.supportedTypes());
    }

    /**
     * {@link WorkspaceConfig} 声明的解析器个数必须与本测试构造的一致。
     */
    @Test
    @DisplayName("配置里声明的解析器个数与本测试覆盖的一致")
    void everyDeclaredParserMustBeCoveredHere() {
        long declared = Arrays.stream(WorkspaceConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> DocumentParser.class.isAssignableFrom(method.getReturnType()))
                .count();

        assertThat(declared)
                .as("WorkspaceConfig 里多了一个解析器 @Bean，而本测试没有覆盖它。"
                        + "请在这里补上它的构造，并回答：它支持的类型在 PARSEABLE 里吗？"
                        + "在 allowed-types 里吗？")
                .isEqualTo(PARSERS.size());
    }

    /**
     * 构造只带解析配置的属性对象。
     *
     * <p>其余组件传 {@code null}：{@code DocumentParsingService} 只读
     * {@code parsing().chunkMaxChars()}，用一个"只有它不为空"的对象能让这件事
     * 在测试里也看得出来。
     *
     * @return 属性对象
     */
    private static WorkspaceProperties properties() {
        return new WorkspaceProperties(null, null, null, null, null,
                new WorkspaceProperties.Parsing(1200, 300, 2_000_000, 16L * 1024 * 1024),
                null, null);
    }

    /**
     * 从 {@code application.yml} 读出上传白名单。
     *
     * @return 归一化后的类型集合
     * @throws IOException 配置文件读不出来
     */
    private static Set<String> allowedUploadTypes() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertThat(sources).as("application.yml 应当能被解析").isNotEmpty();

        PropertySource<?> root = sources.getFirst();
        // 列表在属性源里按下标展开，因此要一个一个取。用"取不到就停"而不是取固定长度：
        // 白名单变长或变短都不该让本测试失败 —— 它关心的是"包含关系"，不是数量。
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        for (int index = 0; ; index++) {
            Object value = root.getProperty("app.workspace.documents.allowed-types[" + index + "]");
            if (value == null) {
                break;
            }
            types.add(value.toString().strip().toLowerCase(java.util.Locale.ROOT));
        }
        assertThat(types).as("app.workspace.documents.allowed-types 应当至少有一项").isNotEmpty();
        return types;
    }
}
