package ai.camphub.workspace.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 切分器的不变量。
 *
 * <h2>它测的四件事都不是"功能"，而是约束</h2>
 * 分块逻辑本身很容易写得"看起来对"—— 真正会出问题的是几条硬约束：
 * <ol>
 *   <li><b>ordinal 自 0 连续、无空洞</b>。它是 {@code uk_document_chunk_ordinal} 的一半，
 *       而一旦出现空洞，那个唯一键就从"防止重复写"变成了"防止重排"，
 *       于是重试路径会撞键失败。</li>
 *   <li><b>每一块都不超过上界</b>。超界的块不会被任何东西拒绝（列宽够大），
 *       它只会在检索时表现为"这个块怎么这么长"。</li>
 *   <li><b>不跨标题合并</b>。跨越之后，一块内容里会混进不属于它标题的东西，
 *       而这种现象没有任何断言会自动发现。</li>
 *   <li><b>超长段落的切点落在句子之间</b>（在上界窗口内存在句末标点时）。
 *       这条曾经是坏的：旧写法"累积到超过上界再切"会让片段本身已经超界，
 *       于是它只能被再硬切一次，句末标点被绕过而无人察觉。</li>
 * </ol>
 *
 * <h2>为什么用 ASCII 而不是中文做输入</h2>
 * 断言里要算字符数（{@code maxChunkChars} 的边界），而中文在断言的可读性上
 * 不占优势：{@code "aaaa"} 一眼能数出 4，{@code "内容内容"} 不能。
 * 唯一需要中文的地方是"标题路径"这类字符串比较，那里不涉及长度。
 *
 * <h2>一条<b>测不出来</b>的规则，如实记在这里</h2>
 * 切分器按<b>空行</b>拆段而不是按单个换行拆段（理由见 {@code DocumentChunker#paragraphsOf}：
 * PDF 抽出的文本几乎每行都是硬换行，按单换行拆会让每段都不足以装满一块）。
 * 但这条规则<b>无法从本类的公开输出上观察到</b>：段落之间用单个 {@code \n} 拼回，
 * 因此"按空行拆成 2 段"与"按单换行拆成 3 段"在足够大的上界下会输出完全相同的字节。
 * 需要更小的上界才可能分开，而那些情形下两者的装箱结果又恰好一致。
 * 与其写一个断言最后得到"其实测的是别的东西"，不如把这件事写清楚：
 * 它由 {@code paragraphsOf} 自身的注释与超长段落用例间接覆盖。
 */
class DocumentChunkerTest {

    /** 固定的主键，用来验证它们被原样带进每一块。 */
    private static final long DOCUMENT_ID = 7L;

    /** 同上。 */
    private static final long WORKSPACE_ID = 3L;

    /**
     * 切分。
     *
     * @param maxChunkChars 上界
     * @param sections      段落
     * @return 分块
     */
    private static List<ChunkDraft> chunk(int maxChunkChars, ParsedSection... sections) {
        return new DocumentChunker(maxChunkChars).chunk(DOCUMENT_ID, WORKSPACE_ID, List.of(sections));
    }

    /**
     * 造一个无标题的段落。
     *
     * @param text 文本
     * @return 段落
     */
    private static ParsedSection section(String text) {
        return new ParsedSection(null, text);
    }

    @Test
    @DisplayName("上界必须为正 —— 否则第一段就会永远装不下")
    void boundMustBePositive() {
        assertThatThrownBy(() -> new DocumentChunker(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ordinal 自 0 连续，且主键被带进每一块")
    void ordinalsAreContiguousAndKeysArePropagated() {
        // 上界 5 而每段 4 字符：任何两段都装不到一起（4 + 1 + 4 > 5），
        // 因此这里得到的是"每段一块"。用更大的上界会让装箱把它们合起来 ——
        // 那是对的行为，只是无法用来数 ordinal。
        List<ChunkDraft> chunks = chunk(5, section("aaaa"), section("bbbb"), section("cccc"));

        assertThat(chunks).extracting(ChunkDraft::ordinal)
                .containsExactly(0, 1, 2);
        assertThat(chunks).allSatisfy(item -> {
            assertThat(item.documentId()).isEqualTo(DOCUMENT_ID);
            assertThat(item.workspaceId()).isEqualTo(WORKSPACE_ID);
        });
    }

    @Test
    @DisplayName("段落装箱：装得下就装，装不下就换下一块")
    void paragraphsArePackedUpToTheBound() {
        // 8 + 1 + 8 = 17 ≤ 20，再加一段 8 就是 26 > 20，因此应切成 2 块。
        List<ChunkDraft> chunks = chunk(20, section("aaaaaaaa"), section("bbbbbbbb"), section("cccccccc"));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().content()).isEqualTo("aaaaaaaa\nbbbbbbbb");
        assertThat(chunks.get(1).content()).isEqualTo("cccccccc");
    }

    @Test
    @DisplayName("标题变了就换块 —— 哪怕还有空间也不跨过去合并")
    void headingChangeFlushesEvenWhenThereIsRoom() {
        List<ChunkDraft> chunks = chunk(100,
                new ParsedSection("部署", "aaaa"),
                new ParsedSection("环境要求", "bbbb"));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(ChunkDraft::heading)
                .containsExactly("部署", "环境要求");
    }

    @Test
    @DisplayName("超长段落被切开，且每一块都不超过上界")
    void oversizedParagraphIsSplitAndStaysWithinTheBound() {
        // 100 个 a，一个句末标点都没有 —— 走的是"硬切"那一级兜底。
        // 少了那一级时，这里会得到"一个 100 字符的块"，而它不会触发任何报错。
        String paragraph = "a".repeat(100);
        List<ChunkDraft> chunks = chunk(20, section(paragraph));

        assertThat(chunks).hasSize(5);
        assertThat(chunks).allSatisfy(item ->
                assertThat(item.content().length()).isLessThanOrEqualTo(20));
        assertThat(chunks.stream().map(ChunkDraft::content).reduce("", String::concat))
                .as("切开之后内容不能被丢掉任何一段")
                .isEqualTo(paragraph);
    }

    @Test
    @DisplayName("超长段落的切点落在句末标点之后，而不是句子中间")
    void oversizedParagraphBreaksAtSentenceBoundary() {
        // 三段话：7 + 5 + 5 = 17 字符，上界 10。
        // 窗口 [0,10) 里最后一个句末标点在索引 6，因此第一块切在它之后（7 字符）。
        //
        // 这条用例正是用来钉住那个已修掉的缺陷：旧写法要等累积长度 ≥ 10 才切，
        // 而那一刻累积已经到 12 字符（越过第二个句号），于是只能再硬切一次 ——
        // 结果是 "aaaaaa。xxx" 加 "x。"，句末标点被完全绕过。
        String paragraph = "aaaaaa。xxxx。yyyy。";
        List<ChunkDraft> chunks = chunk(10, section(paragraph));

        assertThat(chunks).extracting(ChunkDraft::content)
                .containsExactly("aaaaaa。", "xxxx。yyyy。");
        assertThat(chunks).allSatisfy(item ->
                assertThat(item.content().length()).isLessThanOrEqualTo(10));
    }

    @Test
    @DisplayName("空段落与纯空白不产生任何块")
    void blankTextProducesNoChunks() {
        assertThat(chunk(20, section("   \n\t "), section(""))).isEmpty();
        assertThat(chunk(20)).isEmpty();
    }

    @Test
    @DisplayName("charCount 与内容长度一致 —— 它是冗余列，必须自洽")
    void charCountMatchesContent() {
        List<ChunkDraft> chunks = chunk(20, section("hello"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().charCount()).isEqualTo(chunks.getFirst().content().length());
    }

    @Nested
    @DisplayName("段落处理")
    class Paragraphs {

        @Test
        @DisplayName("段首尾的空白被去掉，但段内的换行被保留")
        void stripsParagraphEdges() {
            List<ChunkDraft> chunks = chunk(100, section("\n\n  aaa\nbbb  \n\n"));

            assertThat(chunks).hasSize(1);
            assertThat(chunks.getFirst().content()).isEqualTo("aaa\nbbb");
        }

        @Test
        @DisplayName("段内的单个换行被保留 —— 否则 PDF 的段落会被打散")
        void keepsSoftLineBreaks() {
            // PDF 抽出的文本几乎每一行都是硬换行。若在段落内把换行抹掉，
            // "第几行"这个信息就没了，而用户拿分块回原文定位时正需要它。
            List<ChunkDraft> chunks = chunk(100, section("line one\nline two\nline three"));

            assertThat(chunks).hasSize(1);
            assertThat(chunks.getFirst().content()).isEqualTo("line one\nline two\nline three");
        }

        @Test
        @DisplayName("空行不是内容，而是分隔符 —— 多段之间用单个换行拼回")
        void blankLinesAreConsumedAsSeparators() {
            // 这条用例固定当前行为：块内容是规范化后的形态，不是原始字节。
            // 对"回原文定位"这个用途来说，少一个空行没有影响。
            List<ChunkDraft> chunks = chunk(100, section("第一段\n第二行\n\n第三段"));

            assertThat(chunks).hasSize(1);
            assertThat(chunks.getFirst().content()).isEqualTo("第一段\n第二行\n第三段");
        }
    }
}
