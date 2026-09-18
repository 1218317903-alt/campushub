package ai.camphub.workspace.infrastructure.parser;

import ai.camphub.workspace.app.DocumentParseException;
import ai.camphub.workspace.app.DocumentParser;
import ai.camphub.workspace.domain.ParsedDocument;
import ai.camphub.workspace.domain.ParsedSection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 解析器：按标题层级切出带标题路径的段落。
 *
 * <h2>为什么是逐行扫描，而不是用 commonmark 解析成 AST</h2>
 * 本项目已经引了 commonmark（帖子与笔记的渲染用它），因此"用现成的解析器"
 * 看起来是更自然的选择。这里没有用它，理由是<b>本解析器需要的唯一结构信息是
 * "标题行在哪"</b>，而它恰好是 Markdown 里语法最简单、歧义最少的一部分。
 *
 * <p>走 AST 的代价是把分块逻辑与 commonmark 的节点类型体系绑在一起 ——
 * 于是"新增一种需要识别的前置元数据"变成了一个要读懂 visitor 模式的问题，
 * 而收益只是几个罕见写法的兼容。逐行扫描的代价是必须自己处理围栏代码块
 * （唯一会与本解析器冲突的语法），而那是一个布尔状态。
 *
 * <h2>保留 Markdown 标记，不做剥离</h2>
 * 分块里留着 {@code **加粗**} 的星号与 {@code [文本](url)} 的括号。
 * 这看起来像"没洗干净"，但它是一个刻意的选择：
 * <ul>
 *   <li>分块的第一用途是<b>定位回原文</b>。保留原始形态，检索结果可以直接展示，
 *       不需要再回原文取一次。</li>
 *   <li>"哪些标记该剥离"没有唯一答案 —— 代码块里的内容要保留吗？
 *       链接的 URL 呢？表格的竖线呢？任何一种选择都要写一条规则，
 *       而每条规则都会有一个它判断错的真实文档。</li>
 *   <li>噪声的实际影响取决于索引的分词方式，而那是 Phase 07 要测量的事。
 *       在拿到测量数据之前先剥离，属于凭直觉做优化。</li>
 * </ul>
 *
 * <h2>不处理 setext 标题（{@code ===} / {@code ---} 下划线式）</h2>
 * 它在技术文档里罕见，且 {@code ---} 与分隔线、YAML 前置元数据的结束标记
 * 语法相同 —— 要正确处理必须先判断"上一行是不是非空文本"，
 * 而那需要维护额外的状态。ATX（{@code #}）覆盖了绝大多数真实文档。
 */
public class MarkdownDocumentParser implements DocumentParser {

    /** 本解析器认识的类型。 */
    private static final String MIME_MARKDOWN = "text/markdown";

    /** 版本标识。 */
    private static final String VERSION = "markdown-v1";

    /** Markdown 规范允许的最大标题层级。 */
    private static final int MAX_HEADING_DEPTH = 6;

    /**
     * ATX 标题：行首 1~6 个 {@code #}、至少一个空白、标题文字，可选的收尾 {@code #}。
     *
     * <p>必须要求 {@code #} 之后有空白：{@code #话题标签} 不是标题，
     * 而把一个话题标签当成一级标题，会让它之后的所有内容都挂在一个错误的路径下。
     */
    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");

    /** 标题路径的分隔符。 */
    private static final String PATH_SEPARATOR = " > ";

    @Override
    public boolean supports(String mimeType) {
        return MIME_MARKDOWN.equals(mimeType);
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        if (content.length == 0) {
            throw DocumentParseException.permanent(
                    "文件是空的，没有内容可以读取",
                    "空字节流：length=0",
                    null);
        }
        String text = TextDecoding.decode(content).text();
        return new ParsedDocument(splitByHeadings(text), VERSION);
    }

    @Override
    public String version() {
        return VERSION;
    }

    /**
     * 按标题切分，输出带标题路径的段落。
     *
     * @param text 已解码的 Markdown 原文
     * @return 段落列表，按出现顺序
     */
    private static List<ParsedSection> splitByHeadings(String text) {
        List<ParsedSection> sections = new ArrayList<>();
        String[] headingStack = new String[MAX_HEADING_DEPTH];
        StringBuilder buffer = new StringBuilder();
        String currentHeading = null;
        String openFence = null;

        for (String line : text.split("\n", -1)) {
            String fence = fenceOf(line);
            if (openFence != null) {
                // 代码块内的 # 是代码，不是标题 —— 这是本解析器唯一需要状态的地方。
                if (fence != null && fence.equals(openFence)) {
                    openFence = null;
                } else {
                    appendLine(buffer, line);
                }
                continue;
            }
            if (fence != null) {
                openFence = fence;
                continue;
            }

            Matcher matcher = ATX_HEADING.matcher(line);
            if (matcher.matches()) {
                flush(sections, buffer, currentHeading);
                int depth = matcher.group(1).length();
                headingStack[depth - 1] = matcher.group(2).strip();
                // 更深的层级在遇到同级或更浅的标题时失效。
                // 不做这一步的话，`# A` → `## B` → `# C` 之后，
                // `## D` 的路径会错误地变成 "C > B"（B 早就该被丢弃了）。
                for (int deeper = depth; deeper < MAX_HEADING_DEPTH; deeper++) {
                    headingStack[deeper] = null;
                }
                currentHeading = pathOf(headingStack);
                continue;
            }
            appendLine(buffer, line);
        }
        flush(sections, buffer, currentHeading);
        return sections;
    }

    /**
     * 判断一行是否是围栏代码块的边界。
     *
     * @param line 行
     * @return {@code ```} 或 {@code ~~~}；不是边界时为 null
     */
    private static String fenceOf(String line) {
        String stripped = line.stripLeading();
        if (stripped.startsWith("```")) {
            return "```";
        }
        if (stripped.startsWith("~~~")) {
            return "~~~";
        }
        return null;
    }

    /**
     * 追加一行到缓冲。
     *
     * @param buffer 缓冲
     * @param line   行
     */
    private static void appendLine(StringBuilder buffer, String line) {
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(line);
    }

    /**
     * 若缓冲里有实际内容，输出为一段并清空缓冲。
     *
     * @param sections 输出列表
     * @param buffer   缓冲
     * @param heading  该段所属的标题路径
     */
    private static void flush(List<ParsedSection> sections, StringBuilder buffer, String heading) {
        String text = buffer.toString().strip();
        buffer.setLength(0);
        if (!text.isEmpty()) {
            sections.add(new ParsedSection(heading, text));
        }
    }

    /**
     * 把标题栈拼成路径。
     *
     * @param headingStack 标题栈，索引 0 对应一级标题
     * @return 路径，如 {@code "部署 > 环境要求"}；栈全空时为 null
     */
    private static String pathOf(String[] headingStack) {
        StringBuilder path = new StringBuilder();
        for (String heading : headingStack) {
            if (heading == null || heading.isEmpty()) {
                continue;
            }
            if (path.length() > 0) {
                path.append(PATH_SEPARATOR);
            }
            path.append(heading);
        }
        return path.length() == 0 ? null : path.toString();
    }
}
