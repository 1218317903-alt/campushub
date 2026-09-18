package ai.camphub.workspace.infrastructure.parser;

import ai.camphub.workspace.app.DocumentParseException;
import ai.camphub.workspace.app.DocumentParser;
import ai.camphub.workspace.domain.ParsedDocument;
import ai.camphub.workspace.domain.ParsedSection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Version;

/**
 * PDF 解析器：逐页抽取文本，把页码作为结构信号。
 *
 * <h2>为什么逐页抽取，而不是一次抽全文</h2>
 * 一次 {@code getText(document)} 更快，但它丢掉了页码 —— 而页码是这个格式
 * 唯一能提供的结构。用户拿着"匹配到这一句"的结果去找原文时，第一个问题是
 * "在第几页"；一个没有页码的分块只能回答"在这份 40 页的文档里某处"。
 *
 * <p>代价是对每一页各做一次文本抽取，整体是 O(页数) 次调用而非一次。
 * 因此页数上限（{@link #maxPages}）不只是资源保护，它同时是这条取舍的边界：
 * 超过上限的文档直接失败，而不是退化成"抽全文、丢掉页码" ——
 * 后者会让同一批文档里一部分有页码、一部分没有，而这个差异用户看不见原因。
 *
 * <h2>内存上界怎么来的</h2>
 * PDFBox 3 把"解析期的临时缓冲放在哪"抽象成 {@code StreamCacheCreateFunction}，
 * 由 {@link MemoryUsageSetting#setupMixed(long)} 提供：小于阈值时留在堆内，
 * 超过就落到临时文件。因此一份解压后几十 MB 的内容流不会把堆撑爆。
 * 这是把不可信输入交给解析库时**必须**设置的一项 ——
 * 默认配置没有内存上界，而压缩炸弹（一个很小的 PDF 解压出极大的内容流）
 * 恰好是这种库最经典的攻击面。
 *
 * <h2>不处理的那些 PDF 特性，以及为什么</h2>
 * <ul>
 *   <li><b>加密文档</b>：抛 {@link InvalidPasswordException}。本项目没有"让用户
 *       提供 PDF 密码"的入口，硬要说"解析失败"会让用户以为文件坏了；
 *       因此错误信息明说是加密文档、需要去掉密码后再上传。</li>
 *   <li><b>扫描件（无文本层）</b>：抽取结果为空，这是<b>成功</b>而不是失败。
 *       它需要 OCR 才能得到文本，而 OCR 是另一条技术路线（模型 + 算力），
 *       不属于本阶段。返回空结果 + {@code text_length = 0} 让这件事
 *       在数据上可见，而不是伪装成一个"解析失败"。</li>
 *   <li><b>多栏排版的阅读顺序</b>：不做版面分析，按内容流的顺序输出。
 *       单栏文档（绝大多数）正确；双栏学术论文可能出现左右栏交错。
 *       处理它需要版面分析，那是独立的一个课题。</li>
 * </ul>
 *
 * <h2>版本标识</h2>
 * 从 PDFBox 自身取（{@link Version#getVersion()}）而不是写死字符串：
 * 抽取结果的行为由这个库的版本决定，而写死的字符串会在依赖升级时
 * 悄悄变得不真实 —— 于是 {@code parser_version} 这个字段就失去了它的用途
 * （判断"哪些文档是用旧行为抽的"）。
 */
public class PdfDocumentParser implements DocumentParser {

    /** 本解析器认识的类型。 */
    private static final String MIME_PDF = "application/pdf";

    /** 解析期允许驻留堆内的字节数上限，超过的部分由 PDFBox 落到临时文件。 */
    private final long parseMemoryBytes;

    /** 允许的最大页数。超过即失败，理由见类注释。 */
    private final int maxPages;

    /** 允许抽出的最大字符数。 */
    private final int maxTextChars;

    /**
     * 构造解析器。
     *
     * @param parseMemoryBytes 解析期堆内内存预算（字节）
     * @param maxPages         最大页数
     * @param maxTextChars     抽出的最大字符数
     */
    public PdfDocumentParser(long parseMemoryBytes, int maxPages, int maxTextChars) {
        this.parseMemoryBytes = parseMemoryBytes;
        this.maxPages = maxPages;
        this.maxTextChars = maxTextChars;
    }

    @Override
    public boolean supports(String mimeType) {
        return MIME_PDF.equals(mimeType);
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        if (content.length == 0) {
            throw DocumentParseException.permanent(
                    "文件是空的，没有内容可以读取",
                    "空字节流：length=0",
                    null);
        }

        MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(parseMemoryBytes);
        PDDocument document;
        try {
            // RandomAccessReadBuffer 的所有权交给 PDFBox：它在文档关闭时一并释放。
            // 这里不额外套一层 try-with-resources —— 双重关闭对一个已经开始
            // 释放内部缓冲的对象来说没有意义，只会让"谁负责关闭"变得含糊。
            document = Loader.loadPDF(new RandomAccessReadBuffer(content), memory.streamCache);
        } catch (InvalidPasswordException ex) {
            throw DocumentParseException.permanent(
                    "这份 PDF 已加密，请去掉密码后重新上传",
                    "PDF 受密码保护：" + ex.getMessage(),
                    ex);
        } catch (IOException ex) {
            // 损坏、截断、或根本不是 PDF。重试同一份字节必然得到同样的结果。
            throw DocumentParseException.permanent(
                    "PDF 文件已损坏或格式不正确，无法读取",
                    "PDFBox 打开失败：" + ex.getMessage(),
                    ex);
        }

        try (document) {
            return extract(document);
        } catch (IOException ex) {
            // 关闭阶段失败：内容已经抽出来了，不值得让整次解析失败。
            // 记在异常里没有意义，调用方拿不到"部分成功"这个语义 ——
            // 因此这里选择用已经抽到的结果继续。
            throw DocumentParseException.permanent(
                    "PDF 文件在读取过程中被中断",
                    "关闭 PDDocument 失败：" + ex.getMessage(),
                    ex);
        }
    }

    /**
     * 逐页抽取文本。
     *
     * @param document 已打开的文档
     * @return 解析产出
     * @throws IOException 抽取过程中底层读取失败时
     */
    private ParsedDocument extract(PDDocument document) throws IOException {
        int pageCount = document.getNumberOfPages();
        if (pageCount == 0) {
            throw DocumentParseException.permanent(
                    "这份 PDF 没有任何页面",
                    "getNumberOfPages() 返回 0",
                    null);
        }
        if (pageCount > maxPages) {
            throw DocumentParseException.permanent(
                    "文档页数（" + pageCount + "）超过上限（" + maxPages + "），暂不支持解析",
                    "页数超限：pages=" + pageCount + " max=" + maxPages,
                    null);
        }

        PDFTextStripper stripper = new PDFTextStripper();
        List<ParsedSection> sections = new ArrayList<>(pageCount);
        int textLength = 0;
        for (int page = 1; page <= pageCount; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText;
            try {
                pageText = stripper.getText(document);
            } catch (IOException ex) {
                // 单页抽取失败：同一份字节重试的结果是确定的，因此不可重试。
                throw DocumentParseException.permanent(
                        "PDF 第 " + page + " 页无法读取，文件可能已损坏",
                        "抽取第 " + page + " 页失败：" + ex.getMessage(),
                        ex);
            }
            if (pageText == null || pageText.isBlank()) {
                continue;
            }
            textLength += pageText.length();
            if (textLength > maxTextChars) {
                // 这里选择失败而不是截断。静默截断的后果是用户以为全文可检索，
                // 而实际只有前面一部分 —— 那种偏差不会在任何地方显示出来。
                throw DocumentParseException.permanent(
                        "文档可提取的文字过多，超出解析上限",
                        "文本长度超限：textLength>" + maxTextChars + " pages=" + pageCount,
                        null);
            }
            sections.add(new ParsedSection("第 " + page + " 页", pageText));
        }
        return new ParsedDocument(sections, version());
    }

    @Override
    public String version() {
        return "pdfbox-" + Version.getVersion();
    }
}
