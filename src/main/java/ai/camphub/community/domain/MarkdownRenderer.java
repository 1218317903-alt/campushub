package ai.camphub.community.domain;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Markdown 渲染与净化。
 *
 * <h2>这个类是本项目唯一一处「不可信文本变成 HTML」的地方</h2>
 * 帖子正文由用户提交，属于<b>不可信输入</b>。它最终会以 HTML 形式出现在别人的浏览器里，
 * 因此这条转换链的每一环都必须能回答"用户能借此执行脚本吗"。
 *
 * <h2>三道处理，缺一不可</h2>
 * <ol>
 *   <li><b>{@code escapeHtml(true)}</b>：Markdown 语法本身允许内嵌原始 HTML
 *       （{@code <script>alert(1)</script>} 是合法 Markdown）。开启转义后，用户写的标签
 *       会以文本形式显示出来，而不是被浏览器执行。</li>
 *   <li><b>{@code sanitizeUrls(true)}</b>：过滤 URL 协议。{@code [点我](javascript:alert(1))}
 *       在没有这一步时会渲染成一个可点击的执行入口 —— 它绕过了第 1 步，因为这里
 *       没有任何 HTML 标签，是 Markdown 自己的链接语法。</li>
 *   <li><b>OWASP 白名单净化</b>：对最终 HTML 再跑一遍独立实现的白名单过滤。
 *       它并不只是冗余 —— 有两条可观测的效果只由它提供：链接被强制加上
 *       {@code rel="nofollow"}，以及 URL 协议被收敛到 {@code http/https/mailto}
 *       （转换器自身的 URL 过滤口径与库版本绑定，不归我们控制）。
 *       更重要的是它对"转换器存在我们没想到的绕过路径"提供了一层实现层面的对冲：
 *       即使转换器漏过某个构造，输出仍被限制在允许的标签与属性集合内。</li>
 * </ol>
 * 三道里任何一道单独存在都不够：第 1 步只能防标签、第 2 步只覆盖 URL、第 3 步
 * 若没有前两步则会把用户直接写的 HTML 也当成"待净化内容"而不是"待转义文本"。
 * 这三条性质都有对应的断言在 {@code MarkdownRendererTest} 里。
 *
 * <h2>为什么放在 domain 层</h2>
 * 它是业务规则（"用户提交的内容必须先被净化才能展示"），不是对某个外部系统的适配。
 * 它不依赖任何 Spring 类型，因此可以被直接 new 出来做单元测试 —— 由 ArchUnit 守护。
 *
 * <h2>线程安全</h2>
 * {@link Parser}、{@link HtmlRenderer}、{@link PolicyFactory} 均为无状态且线程安全，
 * 因此本类可以做成单例 Bean 复用。这里刻意不每次调用都新建 ——
 * 构建 Parser 与 Policy 都有可观的固定开销，而发布帖子是会被压测的写路径。
 */
public final class MarkdownRenderer {

    /**
     * 允许保留的标签：恰好覆盖本类渲染器可能产出的集合。
     *
     * <p>不用 {@code allowCommonInlineFormattingElements()} 之类的大礼包：
     * 白名单的原则是"列出来的才允许"，用大礼包等于把决定权交回给库的默认值，
     * 而默认值会随库版本变化 —— 那意味着安全边界会随一次依赖升级悄悄移动。
     */
    private static final String[] ALLOWED_ELEMENTS = {
            "p", "br", "hr",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "blockquote",
            "ul", "ol", "li",
            "pre", "code",
            "em", "strong", "del",
            "a", "img",
            "table", "thead", "tbody", "tr", "th", "td",
            "sup", "sub"
    };

    private final Parser parser;
    private final HtmlRenderer htmlRenderer;
    private final TextContentRenderer textRenderer;
    private final PolicyFactory sanitizer;

    /**
     * 使用默认（安全）配置构造。
     */
    public MarkdownRenderer() {
        // GFM 表格是唯一引入的扩展。每多一个扩展就多一处链接/属性注入面，
        // 而当前内容体裁用不到 autolink / task-list 之类的能力。
        List<Extension> extensions = List.of(TablesExtension.create());

        this.parser = Parser.builder().extensions(extensions).build();
        this.htmlRenderer = HtmlRenderer.builder()
                .extensions(extensions)
                // 不输出 <div class="..."> 之类的展示性包装：它们会进白名单，
                // 而无意义的 class 只是给未来某次 CSS 改写埋坑
                .escapeHtml(true)
                .sanitizeUrls(true)
                .build();
        this.textRenderer = TextContentRenderer.builder().extensions(extensions).build();
        this.sanitizer = buildSanitizer();
    }

    /**
     * 把 Markdown 渲染为已净化的 HTML。
     *
     * @param markdown 用户提交的 Markdown 原文，可为空
     * @return 可直接写入响应体的 HTML 片段；输入为空时返回空串（不是 null）
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = parser.parse(markdown);
        return sanitizer.sanitize(htmlRenderer.render(document));
    }

    /**
     * 把 Markdown 转成用于列表卡片的纯文本摘要。
     *
     * <p>摘要必须<b>脱离 Markdown</b>：卡片上不会渲染 HTML，若直接把原文截断，
     * 用户会看到 {@code ## 标题 [链接](http://...)} 这样的残留标记。
     * 因此这里走的是"提取文本内容"的渲染器，而不是"渲染成 HTML 再剥标签" ——
     * 后者会把代码块内容与链接地址一并当成正文留下。
     *
     * @param markdown   Markdown 原文
     * @param maxLength  摘要最大字符数
     * @return 单行纯文本，超长时以省略号结尾；输入为空时返回空串
     */
    public String summarize(String markdown, int maxLength) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String plain = textRenderer.render(parser.parse(markdown));
        // 折叠所有空白（含换行）为单个空格：摘要是单行展示
        String collapsed = plain.replaceAll("\\s+", " ").strip();
        if (collapsed.length() <= maxLength) {
            return collapsed;
        }
        // 省略号本身也占一个字符位，必须计入预算，否则会超出列宽被数据库截断
        return collapsed.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    /**
     * 构造白名单策略。
     *
     * @return 策略工厂
     */
    private static PolicyFactory buildSanitizer() {
        HtmlPolicyBuilder builder = new HtmlPolicyBuilder()
                .allowElements(ALLOWED_ELEMENTS)
                // 链接：允许 href/title，限制协议，并强制带上 rel=nofollow。
                // nofollow 不是为了 SEO，而是为了削弱"发垃圾外链"的动机 ——
                // 一个不能被搜索引擎计入的链接，对广告号的吸引力显著下降。
                .allowAttributes("href", "title").onElements("a")
                .allowUrlProtocols("http", "https", "mailto")
                .requireRelNofollowOnLinks()
                // 图片：本阶段不支持上传（Phase 05 才引入对象存储），
                // 因此只可能出现外链图片。alt 必须保留 —— 缺了它对屏幕阅读器是纯损失。
                .allowAttributes("src", "alt", "title").onElements("img")
                // 表格对齐：commonmark 的 GFM 表格会输出 align 属性
                .allowAttributes("align").onElements("th", "td");

        // 代码块的 class 刻意不放行。当前没有语法高亮，放行 class 只会让
        // 用户能写入任意 class 值去试探样式的边界（例如覆盖站点的样式规则）。
        // 将来接入高亮时，应当只放行形如 language-xxx 的固定前缀。
        return builder.toFactory();
    }
}
