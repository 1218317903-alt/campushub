package ai.camphub.workspace.domain;

import java.util.List;

/**
 * 一次解析的产出。
 *
 * <h2>为什么 parserVersion 必须跟着结果一起走</h2>
 * "这份文档的文本是哪个解析器版本抽出来的"决定了它能不能被信任：
 * 解析器在一个版本里修好了连字（ligature）处理，那么此前的文档里
 * {@code "office"} 是被抽成 {@code "oﬃce"} 的，用它做的检索永远匹配不到。
 * 把版本记在产出上之后，"哪些文档需要重跑"是一个可以查询的问题，
 * 而不是一次需要凭记忆和日期推断的考古。
 *
 * @param sections      解析得到的段落，按文档顺序
 * @param parserVersion 产出这些段落的解析器版本
 */
public record ParsedDocument(List<ParsedSection> sections, String parserVersion) {

    /**
     * 正文总字符数。
     *
     * <p>它与 {@code document.text_length} 对应。之所以给一个"抽出来的文本长度"
     * 单独留一个字段，是因为它零、而文件字节数不为零时，说明了一件很具体的事：
     * <b>这份文件有内容，但它没有文本层</b>（扫描件 PDF 就是典型）——
     * 那不是解析失败，而是"解析成功但什么也没抽到"，两者的处理完全不同。
     *
     * @return 字符数
     */
    public int textLength() {
        return sections.stream().mapToInt(section -> section.text().length()).sum();
    }
}
