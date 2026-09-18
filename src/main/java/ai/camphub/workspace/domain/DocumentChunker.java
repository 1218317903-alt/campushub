package ai.camphub.workspace.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把解析出的段落切成可检索的分块。
 *
 * <h2>为什么只按字符数装箱，不做语义切分</h2>
 * 这一层唯一的输入是"带标题路径的段落"，因此它能做的语义判断只有一条：
 * <b>不跨越标题合并</b>。再往下的切分粒度需要知道句子与句子的关系，
 * 而那是嵌入模型的事（本阶段没有，Phase 08 也已被移出计划）。
 * 在这里引入一个"按语义相似度切分"的启发式，只会得到一个无法解释、
 * 也无法回归的切分结果 —— 换个参数，同样的文档切出完全不同的块，
 * 而没人能说清哪个是对的。
 *
 * <h2>块之间不做重叠</h2>
 * 重叠（overlap）的价值在于：一个被切断的句子在相邻两块里都完整出现，
 * 从而提高向量检索的召回。代价是存储与检索结果的重复 —— 同一段文字
 * 会在结果列表里出现两次。<b>本阶段没有向量检索消费者</b>，
 * 为它预先付出存储与去重成本，属于给一个还不存在的需求提前买单。
 * 真正接检索时（Phase 07），重叠是一个需要按召回效果调参的决定，
 * 那时它会带着压测数据进来。
 *
 * <h2>为什么上界是"装箱"而不是"定长切开"</h2>
 * 定长切开会无视段落边界 —— 一个列表会被从中间截断，而列表的后半段
 * 到了下一块就失去了它的标题上下文。按段落累积到接近上界再切，
 * 切点永远落在段落之间，代价是块大小有波动（最后一个段落有多长，
 * 上一个块就可能比上界小多少）。对于一个"给人看检索结果"的场景，
 * 保住完整性比保住整齐更值得。
 */
public final class DocumentChunker {

    /**
     * 句子结束标点。超长段落只能按它们切开 —— 一个 3000 字的段落
     * 若不切开，会单独占用一个远超上界的块。
     */
    private static final String SENTENCE_ENDS = "。！？；!?;.\n";

    /** 分块字符数上界。 */
    private final int maxChunkChars;

    /**
     * 构造切分器。
     *
     * @param maxChunkChars 分块字符数上界，必须为正
     * @throws IllegalArgumentException 上界非正时
     */
    public DocumentChunker(int maxChunkChars) {
        if (maxChunkChars <= 0) {
            throw new IllegalArgumentException("分块上界必须为正：" + maxChunkChars);
        }
        this.maxChunkChars = maxChunkChars;
    }

    /**
     * 执行切分。
     *
     * <h2>ordinal 在这里连续生成，不由调用方传入</h2>
     * 块的序号有一个硬约束：自 0 起连续、无空洞、无重复 ——
     * 因为它同时是 {@code uk_document_chunk_ordinal} 的一半，
     * 而"删掉中间某块再重排"这种操作一旦发生，空洞就会永久留在数据里。
     * 由本方法一次生成到底是唯一能让那个约束成立的做法。
     *
     * @param documentId  所属文档自增主键
     * @param workspaceId 所属空间自增主键
     * @param sections    解析出的段落，按文档顺序
     * @return 分块列表，可能为空（原文没有任何文本时）
     */
    public List<ChunkDraft> chunk(long documentId, long workspaceId, List<ParsedSection> sections) {
        List<ChunkDraft> chunks = new ArrayList<>();
        Accumulator accumulator = new Accumulator();

        for (ParsedSection section : sections) {
            String heading = section.heading();
            for (String paragraph : paragraphsOf(section.text())) {
                // 标题变了就不跨过去合并：标题是作者画出的语义边界，
                // 把它切开或跨过它合并，都会让一个块的内容超出它自己的标题所述。
                if (!accumulator.isEmpty() && !Objects.equals(accumulator.heading, heading)) {
                    accumulator.flushInto(chunks, documentId, workspaceId);
                }
                if (paragraph.length() > maxChunkChars) {
                    accumulator.flushInto(chunks, documentId, workspaceId);
                    for (String piece : splitOversized(paragraph)) {
                        accumulator.append(piece, heading);
                        accumulator.flushInto(chunks, documentId, workspaceId);
                    }
                    continue;
                }
                if (accumulator.length() + paragraph.length() + 1 > maxChunkChars) {
                    accumulator.flushInto(chunks, documentId, workspaceId);
                }
                accumulator.append(paragraph, heading);
            }
        }
        accumulator.flushInto(chunks, documentId, workspaceId);
        return chunks;
    }

    /**
     * 按空行拆段。
     *
     * <h2>为什么只按空行，不按单个换行</h2>
     * 单个换行在真实文档里同时表示"换行"与"新段落"两种意思 ——
     * 前端 Markdown 里一个软换行是折行，而 PDF 抽出来的文本几乎每一行
     * 都是硬换行。若按单换行切段，一份 PDF 会被切成"每行一个段"，
     * 于是装箱逻辑再无用武之地（每个段都短于上界），
     * 结果是每个块只有一行文本。按空行切段让 PDF 的段落得以保留，
     * 因为它们本来就是被空行分开的。
     *
     * @param text 段落文本
     * @return 拆分后的非空段落
     */
    private static List<String> paragraphsOf(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : text.split("\n\\s*\n")) {
            String stripped = paragraph.strip();
            if (!stripped.isEmpty()) {
                paragraphs.add(stripped);
            }
        }
        return paragraphs;
    }

    /**
     * 把一个超长段落切成若干不超过上界的片段，优先切在句末标点之后。
     *
     * <h2>为什么是"窗口内最后一个句末标点"，而不是"累积到超过上界再切"</h2>
     * 后者是一个看起来等价、实际上不成立的写法：等到累积长度超过上界时，
     * 那个片段本身<b>已经超过上界</b>，于是它只能再被硬切一次 ——
     * 句末标点被绕过，切点仍然落在句子中间。结果是这一级"按句子切"永远不起作用，
     * 而它并不会报错，只是让每个长段落的切点都落在随机位置。
     *
     * <p>因此判据改成"在 [起点, 起点+上界) 这个窗口里往回找最后一个句末标点"：
     * 找到就切在它后面（片段必然不超过上界）；一个都没有才硬切在上界处。
     * 后者是必要的兜底 —— 代码块、长 URL 列表、被抽平的表格都会是
     * "一个句末标点都没有的一大段"，少了兜底它们会以"超过上界的单个块"进入数据库。
     *
     * @param paragraph 超长段落
     * @return 片段列表，每个不超过上界且非空
     */
    private List<String> splitOversized(String paragraph) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < paragraph.length()) {
            int windowEnd = Math.min(start + maxChunkChars, paragraph.length());
            if (windowEnd == paragraph.length()) {
                // 剩余部分装得下，直接收尾 —— 它可能比上界短，这正是"装箱"允许的波动。
                pieces.add(paragraph.substring(start));
                break;
            }
            int cut = lastSentenceEndWithin(paragraph, start, windowEnd);
            pieces.add(paragraph.substring(start, cut));
            start = cut;
        }
        return pieces.stream()
                .map(String::strip)
                .filter(piece -> !piece.isEmpty())
                .toList();
    }

    /**
     * 在 {@code [start, windowEnd)} 内找最后一个句末标点的下一个位置。
     *
     * <p>从窗口末尾往回找，因此得到的切点<b>尽可能靠后</b> ——
     * 块要尽量装满，否则一个以短句为主的段落会被切成许多只有几个字的块。
     *
     * @param text      段落全文
     * @param start     起点（含）
     * @param windowEnd 窗口末尾（不含）
     * @return 切点（必然大于 {@code start}）；窗口内没有句末标点时返回 {@code windowEnd}
     */
    private static int lastSentenceEndWithin(String text, int start, int windowEnd) {
        for (int index = windowEnd - 1; index >= start; index--) {
            if (SENTENCE_ENDS.indexOf(text.charAt(index)) >= 0) {
                return index + 1;
            }
        }
        return windowEnd;
    }

    /**
     * 装箱过程中的累积缓冲。
     *
     * <h2>为什么封成一个内部类而不是用两个局部变量</h2>
     * 缓冲的内容与它归属的标题必须一起被清空、一起被写出。用两个局部变量时，
     * "清空正文却忘了清空标题"是一个随时会发生的疏漏 —— 而它的表现是
     * 块的标题来自上一段内容，不报错、只是安静地错。封装之后，那种状态
     * 在类型上就不可能出现。
     */
    private static final class Accumulator {
        private final StringBuilder text = new StringBuilder();
        private String heading;

        /** 当前归属的标题，仅用于比较。 */
        boolean isEmpty() {
            return text.length() == 0;
        }

        int length() {
            return text.length();
        }

        /**
         * 追加一段文本。
         *
         * @param paragraph 段落
         * @param heading   该段所属标题路径
         */
        void append(String paragraph, String heading) {
            if (isEmpty()) {
                this.heading = heading;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(paragraph);
        }

        /**
         * 若缓冲非空则写出一块，并清空。
         *
         * @param out         输出列表
         * @param documentId  所属文档自增主键
         * @param workspaceId 所属空间自增主键
         */
        void flushInto(List<ChunkDraft> out, long documentId, long workspaceId) {
            if (isEmpty()) {
                return;
            }
            out.add(new ChunkDraft(documentId, workspaceId, out.size(),
                    heading, text.toString()));
            text.setLength(0);
            heading = null;
        }
    }
}
