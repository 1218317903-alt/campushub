package ai.camphub.workspace.app;

import ai.camphub.workspace.config.WorkspaceProperties;
import ai.camphub.workspace.domain.ChunkDraft;
import ai.camphub.workspace.domain.DocumentChunker;
import ai.camphub.workspace.domain.ParsedDocument;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 解析编排：按内容类型分派到具体解析器，再交给切分器分块。
 *
 * <h2>为什么"分派"值得单独一层</h2>
 * 它同时是两个<b>启动期约束</b>的落点，而这两条约束在别处都不成立：
 *
 * <ol>
 *   <li><b>同一类型不能被多个解析器声明支持。</b>
 *       若两个实现都声明支持 {@code application/pdf}，那么"哪一份代码在跑"
 *       取决于容器注入 {@code List} 的顺序 —— 一个不报错、只表现为
 *       "抽取结果有时候不一样"的不确定性。构造时检查并直接让应用起不来，
 *       是把这件事从"运行期的偶然"变成"部署期的必然"。</li>
 *   <li><b>解析器声明的类型必须都能被上传。</b>
 *       上传白名单（{@code app.workspace.documents.allowed-types}）决定哪些类型
 *       允许被<b>存下来</b>，而这里决定哪些类型能被<b>读懂</b>。
 *       白名单刻意更宽（压缩包、Office 文档、图片都能存能下载，只是当前读不懂），
 *       因此两处只需要保证一个方向：<b>能被读懂的，必须能被存下来</b>。
 *       违反时的表现是那个解析器成为死代码 —— 它支持的内容永远传不进来，
 *       而"系统支持 Markdown"这句话就成了谎话。这条由
 *       {@code DocumentParsingRegistryTest} 在构建期断言。</li>
 * </ol>
 *
 * <h2>这个类不碰数据库，也不碰存储</h2>
 * 它的输入是字节与类型，输出是段落与分块。所有事务、状态更新、审计都发生在
 * {@link DocumentTaskProcessor} 里。这条边界让解析逻辑可以被单独测试
 * （构造几个解析器 + 一个切分器即可），也让"解析时改了一行数据"在结构上不可能。
 */
@Service
public class DocumentParsingService {

    private final Map<String, DocumentParser> parsersByMimeType;
    private final DocumentChunker chunker;

    /**
     * 构造编排器，并在启动期校验解析器注册表。
     *
     * @param parsers    容器中全部解析器实现
     * @param properties 空间模块配置（分块上界）
     * @throws IllegalStateException 同一内容类型被多个解析器声明支持时
     */
    public DocumentParsingService(List<DocumentParser> parsers, WorkspaceProperties properties) {
        Map<String, DocumentParser> byType = new HashMap<>();
        for (DocumentParser parser : parsers) {
            for (String mimeType : SupportedTypes.probe(parser)) {
                DocumentParser previous = byType.put(mimeType, parser);
                if (previous != null) {
                    throw new IllegalStateException(
                            "内容类型 " + mimeType + " 被两个解析器同时声明支持："
                                    + previous.getClass().getName() + " 与 "
                                    + parser.getClass().getName()
                                    + "。这会让实际生效的解析器取决于注入顺序。");
                }
            }
        }
        this.parsersByMimeType = Map.copyOf(byType);
        this.chunker = new DocumentChunker(properties.parsing().chunkMaxChars());
    }

    /**
     * 已注册的内容类型集合。
     *
     * <p>上传路径用它校验"白名单里的类型是否真的都有解析器"。
     *
     * @return 类型集合
     */
    public Set<String> supportedTypes() {
        return parsersByMimeType.keySet();
    }

    /**
     * 解析并分块。
     *
     * @param content     完整内容
     * @param mimeType    内容类型，大小写不敏感
     * @param documentId  所属文档自增主键，写入分块
     * @param workspaceId 所属空间自增主键，写入分块
     * @return 解析与切分的产出
     * @throws DocumentParseException 类型不支持，或解析失败
     */
    public ParseOutcome parse(byte[] content, String mimeType, long documentId, long workspaceId) {
        String normalized = normalize(mimeType);
        DocumentParser parser = parsersByMimeType.get(normalized);
        if (parser == null) {
            throw DocumentParseException.permanent(
                    "暂不支持解析这种类型的文件",
                    "无解析器注册：mimeType=" + normalized
                            + " registered=" + parsersByMimeType.keySet(),
                    null);
        }
        ParsedDocument parsed = parser.parse(content);
        List<ChunkDraft> chunks = chunker.chunk(documentId, workspaceId, parsed.sections());
        return new ParseOutcome(parsed, chunks);
    }

    /**
     * 归一化内容类型：小写、去掉参数。
     *
     * @param mimeType 原始类型
     * @return 归一化结果
     */
    private static String normalize(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        return mimeType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    /**
     * 探测一个解析器声明支持哪些内容类型。
     *
     * <h2>为什么要"探测"而不是让端口直接返回集合</h2>
     * 端口的 {@code supports(String)} 形式对单个实现的写法更自然
     * （它就是一次字符串比较），而且判断逻辑与"支持哪些"用的是同一份代码 ——
     * 若端口改成返回集合，实现就得两处各写一份类型清单，
     * 而它们迟早会不一致（{@code supports} 说支持、集合里没有）。
     *
     * <p>代价是启动期只能靠试。这里用一份覆盖当前全部类型的小清单去问每个实现，
     * 命中即为它声明的类型。清单需要随新增格式一起扩充，
     * 而"忘了扩充"的表现是启动时抛异常（某个白名单类型没有解析器），
     * 不是静默失效。
     */
    private static final class SupportedTypes {

        /** 当前需要识别的类型。新增格式时在这里加一项。 */
        private static final List<String> CANDIDATES = List.of(
                "text/plain",
                "text/markdown",
                "application/pdf");

        private SupportedTypes() {
        }

        /**
         * 探测一个解析器声明的类型。
         *
         * @param parser 解析器
         * @return 它声明支持的类型
         */
        static List<String> probe(DocumentParser parser) {
            return CANDIDATES.stream().filter(parser::supports).toList();
        }
    }
}
