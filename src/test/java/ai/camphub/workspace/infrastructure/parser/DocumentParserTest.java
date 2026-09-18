package ai.camphub.workspace.infrastructure.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.camphub.workspace.app.DocumentParseException;
import ai.camphub.workspace.app.DocumentParser;
import ai.camphub.workspace.domain.ParsedDocument;
import ai.camphub.workspace.domain.ParsedSection;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 三个解析器的行为，重点是<b>失败的分类</b>。
 *
 * <h2>为什么断言的重点不是"抽出了什么"，而是"失败时怎么归类"</h2>
 * 抽取结果错了，用户看得见（内容不对）；而失败归类错了，用户看不见任何东西 ——
 * 只表现为"这份文件在那台机器上多试了三次"，或者"一次网络抖动就告诉我文件坏了"。
 * 因此每个失败用例都断言两件事：{@code retryable()} 与 {@code userMessage()}。
 * 前者决定 worker 会不会重试（不可重试的失败连第二次读取都不会发生），
 * 后者会直接显示在界面上，因此它<b>一个字都不该提内部细节</b>。
 *
 * <h2>PDF 为什么用真实字节而不是一份固定样本</h2>
 * 用 PDFBox 自己生成一份 PDF 再解析它，验证的是"我们调用的那几个 API 真的能工作"
 * ——这恰恰是本阶段风险最大的地方（PDFBox 3 相对 2.x 有破坏性变更，
 * 内存策略与加载入口都换了）。一份固化在测试资源里的 PDF 只能证明"这一份能解析"，
 * 而它无法证明代码里的 API 用法正确。
 */
class DocumentParserTest {

    /** 解析器版本取自 PDFBox 自身，因此这里只能断言前缀。 */
    private static final String PDF_VERSION_PREFIX = "pdfbox-";

    /** 小块内容用得上限，避免测试依赖真实的配置值。 */
    private static final long PARSE_MEMORY_BYTES = 8L * 1024 * 1024;

    /**
     * 造一个 PDF 解析器。
     *
     * @param maxPages     页数上限
     * @param maxTextChars 文本长度上限
     * @return 解析器
     */
    private static PdfDocumentParser pdfParser(int maxPages, int maxTextChars) {
        return new PdfDocumentParser(PARSE_MEMORY_BYTES, maxPages, maxTextChars);
    }

    /**
     * 用 PDFBox 生成一份真实 PDF，每页一行给定文字。
     *
     * @param pageTexts 每页的文字
     * @return PDF 字节
     * @throws IOException 生成失败
     */
    private static byte[] pdfWithPages(String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            return saveToBytes(document, null);
        }
    }

    /**
     * 用 PDFBox 生成一份<b>加密</b>的 PDF。
     *
     * <p>加密文档必须落到 {@code File}：PDFBox 对加密输出要求可随机访问的输出目标，
     * 因此这里经过一次临时文件。它同时也是"加密确实生效了"的一个证据 ——
     * 若保护策略没有生效，下面的用例会因为解析成功而失败。
     *
     * @return PDF 字节
     * @throws IOException 生成失败
     */
    private static byte[] encryptedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("secret");
                content.endText();
            }
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner-password", "user-password", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            return saveToBytes(document, policy);
        }
    }

    /**
     * 把文档存成字节。
     *
     * @param document 文档
     * @param policy   保护策略，为 null 时走内存流
     * @return 字节
     * @throws IOException 保存失败
     */
    private static byte[] saveToBytes(PDDocument document, StandardProtectionPolicy policy)
            throws IOException {
        if (policy == null) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
        Path file = Files.createTempFile("camphub-encrypted-", ".pdf");
        try {
            document.save(file.toFile());
            return Files.readAllBytes(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * 断言某次解析以"不可重试"的方式失败。
     *
     * @param parser              解析器
     * @param content             内容
     * @param expectedUserMessage 期望的面向用户消息
     */
    private static void assertPermanentFailure(DocumentParser parser, byte[] content,
                                               String expectedUserMessage) {
        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(DocumentParseException.class)
                .satisfies(thrown -> {
                    DocumentParseException failure = (DocumentParseException) thrown;
                    assertThat(failure.retryable())
                            .as("同一份字节重跑一次必然是同样的结果，因此不该重试")
                            .isFalse();
                    assertThat(failure.userMessage()).isEqualTo(expectedUserMessage);
                    assertThat(failure.getMessage())
                            .as("日志消息可以带内部细节，但它必须与用户消息不同 —— "
                                    + "两者相同意味着内部细节会显示到界面上")
                            .isNotEqualTo(expectedUserMessage);
                });
    }

    @Nested
    @DisplayName("纯文本")
    class PlainText {

        private final PlainTextDocumentParser parser = new PlainTextDocumentParser();

        @Test
        @DisplayName("只认 text/plain")
        void supportsOnlyPlainText() {
            assertThat(parser.supports("text/plain")).isTrue();
            assertThat(parser.supports("text/markdown")).isFalse();
            assertThat(parser.supports("application/pdf")).isFalse();
        }

        @Test
        @DisplayName("整篇作为一段，标题为 null —— 不编造假标题")
        void producesOneSectionWithoutHeading() {
            String text = "第一行\n\n第二段";
            ParsedDocument parsed = parser.parse(text.getBytes(StandardCharsets.UTF_8));

            assertThat(parsed.sections()).hasSize(1);
            assertThat(parsed.sections().getFirst().heading()).isNull();
            assertThat(parsed.sections().getFirst().text()).isEqualTo(text);
            assertThat(parsed.textLength()).isEqualTo(text.length());
            assertThat(parsed.parserVersion()).isEqualTo("plain-v1");
        }

        @Test
        @DisplayName("GBK 编码的中文能被正确解出来，而不是变成替换字符")
        void decodesGbk() {
            String text = "中文编码测试";
            byte[] gbk = text.getBytes(Charset.forName("GBK"));

            ParsedDocument parsed = parser.parse(gbk);

            assertThat(parsed.sections().getFirst().text())
                    .as("用 UTF-8 宽松解码会得到一串 U+FFFD，而那是静默的乱码")
                    .isEqualTo(text)
                    .doesNotContain("\uFFFD");
        }

        @Test
        @DisplayName("全是空白的内容是成功，不是失败")
        void blankContentSucceedsWithNothing() {
            // 与 PDF 的扫描件是同一条规则：READY 描述"流程走完了"，不是"一定有内容"。
            // 把空内容当失败，会让一份完全正常的文件显示"解析失败"。
            ParsedDocument parsed = parser.parse("   \n\t\n".getBytes(StandardCharsets.UTF_8));

            assertThat(parsed.sections()).isEmpty();
            assertThat(parsed.textLength()).isZero();
        }

        @Test
        @DisplayName("空字节是失败 —— 那不是一个可读的文件")
        void emptyBytesFail() {
            assertPermanentFailure(parser, new byte[0], "文件是空的，没有内容可以读取");
        }
    }

    @Nested
    @DisplayName("Markdown")
    class Markdown {

        private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

        @Test
        @DisplayName("只认 text/markdown")
        void supportsOnlyMarkdown() {
            assertThat(parser.supports("text/markdown")).isTrue();
            assertThat(parser.supports("text/plain")).isFalse();
        }

        @Test
        @DisplayName("标题路径按层级拼接")
        void buildsHeadingPaths() {
            ParsedDocument parsed = parser.parse("""
                    # 标题

                    正文

                    ## 子标题

                    内容
                    """.getBytes(StandardCharsets.UTF_8));

            assertThat(parsed.sections()).extracting(ParsedSection::heading)
                    .containsExactly("标题", "标题 > 子标题");
            assertThat(parsed.sections().get(1).text()).isEqualTo("内容");
        }

        @Test
        @DisplayName("回到浅层标题后，更深的层级被丢弃 —— 路径不会串成别人的子树")
        void deeperLevelsAreDiscardedWhenGoingBackUp() {
            // `# A` → `## B` → `# C` → `## D` 时，D 的路径必须是 "C > D"。
            // 不做这一步清理的实现会给出 "C > B"，而 B 早就该被丢掉了 ——
            // 这种错误不会报错，只是让分块挂在一个不存在的标题下。
            ParsedDocument parsed = parser.parse("""
                    # A

                    a

                    ## B

                    b

                    # C

                    c

                    ## D

                    d
                    """.getBytes(StandardCharsets.UTF_8));

            assertThat(parsed.sections()).extracting(ParsedSection::heading)
                    .contains("A > B", "C", "C > D")
                    .doesNotContain("C > B");
        }

        @Test
        @DisplayName("围栏代码块里的 # 是代码，不是标题")
        void headingsInsideFencesAreNotHeadings() {
            ParsedDocument parsed = parser.parse("""
                    ```
                    # 这不是标题
                    ```

                    正文
                    """.getBytes(StandardCharsets.UTF_8));

            assertThat(parsed.sections()).hasSize(1);
            assertThat(parsed.sections().getFirst().heading()).isNull();
            assertThat(parsed.sections().getFirst().text())
                    .contains("# 这不是标题")
                    .contains("正文");
        }

        @Test
        @DisplayName("#话题标签不是标题 —— 识别它会让之后的内容挂错路径")
        void hashtagIsNotAHeading() {
            ParsedDocument parsed = parser.parse("#标签\n\n正文".getBytes(StandardCharsets.UTF_8));

            assertThat(parsed.sections()).hasSize(1);
            assertThat(parsed.sections().getFirst().heading()).isNull();
        }

        @Test
        @DisplayName("保留 Markdown 标记，不做剥离")
        void keepsMarkup() {
            // 分块的第一用途是定位回原文，因此保留原始形态；
            // "哪些标记该剥离"没有唯一答案（代码块里的内容呢？链接的 URL 呢？）。
            ParsedDocument parsed = parser.parse(
                    "# 标题\n\n**加粗**与[链接](https://example.invalid)".getBytes(StandardCharsets.UTF_8));

            assertThat(parsed.sections().getFirst().text())
                    .contains("**加粗**")
                    .contains("(https://example.invalid)");
        }

        @Test
        @DisplayName("空字节是失败")
        void emptyBytesFail() {
            assertPermanentFailure(parser, new byte[0], "文件是空的，没有内容可以读取");
        }
    }

    @Nested
    @DisplayName("PDF")
    class Pdf {

        private final PdfDocumentParser parser = pdfParser(50, 100_000);

        @Test
        @DisplayName("只认 application/pdf")
        void supportsOnlyPdf() {
            assertThat(parser.supports("application/pdf")).isTrue();
            assertThat(parser.supports("text/plain")).isFalse();
        }

        @Test
        @DisplayName("逐页抽取，页码作为标题路径")
        void extractsEachPageWithPageHeading() throws IOException {
            ParsedDocument parsed = parser.parse(pdfWithPages("First page", "Second page"));

            assertThat(parsed.sections()).hasSize(2);
            assertThat(parsed.sections()).extracting(ParsedSection::heading)
                    .containsExactly("第 1 页", "第 2 页");
            assertThat(parsed.sections().getFirst().text()).contains("First page");
            assertThat(parsed.sections().get(1).text()).contains("Second page");
        }

        @Test
        @DisplayName("没有文本层的页被跳过，而不是产生空段落")
        void pagesWithoutTextAreSkipped() throws IOException {
            // 用空字符串页模拟"有页面、无文本层"（真实场景是扫描件）。
            ParsedDocument parsed = parser.parse(pdfWithPages("", "Only this page has text"));

            assertThat(parsed.sections()).hasSize(1);
            assertThat(parsed.sections().getFirst().heading()).isEqualTo("第 2 页");
        }

        @Test
        @DisplayName("版本号取自 PDFBox 自身，而不是写死的字符串")
        void versionComesFromPdfBox() {
            assertThat(parser.version()).startsWith(PDF_VERSION_PREFIX);
        }

        @Test
        @DisplayName("页数超限即失败 —— 不退化成抽全文丢掉页码")
        void tooManyPagesFail() throws IOException {
            // 退化的后果是同一批文档里一部分有页码、一部分没有，
            // 而这个差异用户看不见原因。
            byte[] twoPages = pdfWithPages("a", "b");

            assertThatThrownBy(() -> pdfParser(1, 100_000).parse(twoPages))
                    .isInstanceOf(DocumentParseException.class)
                    .satisfies(thrown -> {
                        DocumentParseException failure = (DocumentParseException) thrown;
                        assertThat(failure.retryable()).isFalse();
                        assertThat(failure.userMessage()).contains("页数").contains("超过上限");
                    });
        }

        @Test
        @DisplayName("文本超限即失败 —— 静默截断会让全文可检索变成谎话")
        void tooMuchTextFails() throws IOException {
            byte[] onePage = pdfWithPages("Some text on the page");

            assertThatThrownBy(() -> pdfParser(50, 5).parse(onePage))
                    .isInstanceOf(DocumentParseException.class)
                    .satisfies(thrown -> assertThat(((DocumentParseException) thrown).userMessage())
                            .contains("超出解析上限"));
        }

        @Test
    @DisplayName("加密文档明说是加密，而不是一句文件损坏")
    void encryptedPdfIsReportedAsEncrypted() throws IOException {
            assertThatThrownBy(() -> parser.parse(encryptedPdf()))
                    .isInstanceOf(DocumentParseException.class)
                    .satisfies(thrown -> {
                        DocumentParseException failure = (DocumentParseException) thrown;
                        assertThat(failure.retryable()).isFalse();
                        // 说"损坏"会让用户以为文件坏了；说"加密"他才知道自己该做什么。
                        assertThat(failure.userMessage()).contains("加密");
                    });
        }

        @Test
        @DisplayName("根本不是 PDF 的字节被判为损坏，且不可重试")
        void garbageBytesFail() {
            assertPermanentFailure(parser,
                    "this is definitely not a pdf".getBytes(StandardCharsets.UTF_8),
                    "PDF 文件已损坏或格式不正确，无法读取");
        }

        @Test
        @DisplayName("空字节是失败")
        void emptyBytesFail() {
            assertPermanentFailure(parser, new byte[0], "文件是空的，没有内容可以读取");
        }
    }

    @Test
    @DisplayName("三个解析器声明的类型两两不重叠 —— 否则实际生效的取决于注入顺序")
    void declaredTypesDoNotOverlap() {
        List<DocumentParser> parsers = List.of(
                new PlainTextDocumentParser(), new MarkdownDocumentParser(),
                pdfParser(10, 1000));
        List<String> candidates = List.of("text/plain", "text/markdown", "application/pdf");

        for (String candidate : candidates) {
            assertThat(parsers.stream().filter(parser -> parser.supports(candidate)).toList())
                    .as("类型 %s 被多个解析器声明支持", candidate)
                    .hasSize(1);
        }
    }
}
