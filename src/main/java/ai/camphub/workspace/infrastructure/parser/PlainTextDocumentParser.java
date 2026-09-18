package ai.camphub.workspace.infrastructure.parser;

import ai.camphub.workspace.app.DocumentParseException;
import ai.camphub.workspace.app.DocumentParser;
import ai.camphub.workspace.domain.ParsedDocument;
import ai.camphub.workspace.domain.ParsedSection;
import java.util.List;

/**
 * 纯文本解析器。
 *
 * <h2>它几乎是空的，这是对的</h2>
 * 纯文本没有结构可言，所以这里只做两件事：解码（{@link TextDecoding}）
 * 与把整篇交给切分器。没有标题层级可提取，因此 {@code heading} 一律为 null ——
 * 而不是编造一个"第 1 段"之类的假标题：假标题会进入用户的检索结果，
 * 让"这一段的标题是什么"变成一句需要解释的话。
 *
 * <h2>全是空白的内容不算失败</h2>
 * 解出来一个字都没有时，本解析器返回<b>成功的空结果</b>而不是抛异常。
 * 这与 PDF 的情形是同一条规则：{@code READY} 描述的是"处理流程走完了"，
 * 不是"一定有内容"。真正的失败（读不了、格式不对）另有其形。
 * 把空内容当失败会有个很糟的后果：一份扫描件 PDF（有页面、无文本层）
 * 会让用户看到"解析失败"，而他手上那份文件其实完全正常。
 *
 * <p>区分这两件事的证据落在 {@code document.text_length} 上：
 * 它为 0 而字节数不为 0，就是"没有可提取的文本"。
 */
public class PlainTextDocumentParser implements DocumentParser {

    /** 本解析器认识的类型。 */
    private static final String MIME_TEXT_PLAIN = "text/plain";

    /** 版本标识。纯文本抽取的行为在 v1 之后就固定了，改动它意味着改动解码策略。 */
    private static final String VERSION = "plain-v1";

    @Override
    public boolean supports(String mimeType) {
        return MIME_TEXT_PLAIN.equals(mimeType);
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        if (content.length == 0) {
            throw DocumentParseException.permanent(
                    "文件是空的，没有内容可以读取",
                    "空字节流：length=0",
                    null);
        }
        TextDecoding.DecodedText decoded = TextDecoding.decode(content);
        String text = decoded.text();
        if (text.isBlank()) {
            return new ParsedDocument(List.of(), VERSION);
        }
        return new ParsedDocument(List.of(new ParsedSection(null, text)), VERSION);
    }

    @Override
    public String version() {
        return VERSION;
    }
}
