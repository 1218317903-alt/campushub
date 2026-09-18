package ai.camphub.workspace.infrastructure.parser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文本字节到字符串的解码，含编码探测。
 *
 * <h2>为什么不能直接 {@code new String(bytes, UTF_8)}</h2>
 * 那个构造函数对非法字节序列<b>不抛异常</b>，而是静默替换成 U+FFFD（"�"）。
 * 于是一个 GBK 编码的中文文本文件会被解成一串替换字符 ——
 * 上传成功、解析成功、状态 READY、分块落库，只有内容全是乱码。
 * 没有任何一环会报错，而这正是最坏的一种失败：它伪装成成功。
 *
 * <h2>探测顺序，以及为什么是这个顺序</h2>
 * <ol>
 *   <li><b>BOM</b>：字节顺序标记是文档自带的声明，比任何猜测都可靠。UTF-8（EF BB BF）、
 *       UTF-16LE（FF FE）、UTF-16BE（FE FF）三种都识别。</li>
 *   <li><b>严格 UTF-8</b>：无 BOM 时的首选。UTF-8 的字节结构约束很强，
 *       一段合法的 UTF-8 多字节序列几乎不可能由 GBK 文本偶然构成，
 *       因此"能严格解成 UTF-8"是一个可信的判据。</li>
 *   <li><b>GBK</b>：中文环境下最常见的另一种编码。它没有 BOM，字节范围又与 UTF-8 重叠，
 *       无法与 UTF-8 从字节上区分 —— 只能靠"UTF-8 严格解码失败"来排它。
 *       把它排在 UTF-8 之后的理由是：反过来做会让所有 UTF-8 中文文本被误判成 GBK
 *       （GBK 几乎能解任何字节序列，它太宽松了）。</li>
 *   <li><b>宽松 UTF-8</b>：都不成立时用带替换字符的解码兜底。
 *       到这里已经无法判断原编码了，此时<b>保留内容</b>比<b>拒绝文件</b>更合适：
 *       用户至少能看到大部分内容，而乱码的位置会明确显示为 U+FFFD，
 *       比"解析失败，原因未知"更有信息量。</li>
 * </ol>
 *
 * <h2>为什么不用 ICU4J 之类的编码探测库</h2>
 * 它们能给出概率与置信度，对真正的自动探测更有力；但代价是一个几十 MB 的依赖，
 * 换来的收益覆盖的正是上面第 4 步那一小部分。本项目的输入来自中文用户的上传，
 * 前两步已经覆盖绝大多数真实情况。
 */
final class TextDecoding {

    private static final Logger log = LoggerFactory.getLogger(TextDecoding.class);

    /** 中文环境下最可能遇到的非 UTF-8 编码。 */
    private static final Charset GBK = Charset.forName("GBK");

    private TextDecoding() {
    }

    /**
     * 把字节解码成文本，必要时探测编码。
     *
     * @param bytes 原始字节
     * @return 解码结果，同时带上判定的编码名（写入日志，便于排查编码问题）
     */
    static DecodedText decode(byte[] bytes) {
        if (bytes.length == 0) {
            return new DecodedText("", StandardCharsets.UTF_8.name());
        }

        Boms bom = Boms.of(bytes);
        if (bom != null) {
            return new DecodedText(
                    new String(bytes, bom.offset(), bytes.length - bom.offset(), bom.charset()),
                    bom.charset().name());
        }

        String strictUtf8 = strictDecode(bytes, StandardCharsets.UTF_8);
        if (strictUtf8 != null) {
            return new DecodedText(strictUtf8, StandardCharsets.UTF_8.name());
        }

        String gbk = strictDecode(bytes, GBK);
        if (gbk != null) {
            // 这条日志是有价值的：它说明系统里有非 UTF-8 的文本在流动。
            // 如果同一个用户反复触发它，值得去问一句"这个文件是怎么生成的"。
            log.info("文本编码探测结果：非 UTF-8，按 GBK 解码，字节数={}", bytes.length);
            return new DecodedText(gbk, GBK.name());
        }

        log.warn("文本编码探测失败，按 UTF-8 宽松解码（非法字节将显示为替换字符），字节数={}",
                bytes.length);
        return new DecodedText(new String(bytes, StandardCharsets.UTF_8),
                StandardCharsets.UTF_8.name() + "(lenient)");
    }

    /**
     * 严格解码：任一字节序列非法就返回 null，而不是替换成 U+FFFD。
     *
     * @param bytes   字节
     * @param charset 目标编码
     * @return 解出的文本；有非法序列时为 null
     */
    private static String strictDecode(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    /**
     * 解码结果。
     *
     * @param text        文本
     * @param charsetName 判定的编码名
     */
    record DecodedText(String text, String charsetName) {
    }

    /**
     * 已知的字节顺序标记。
     */
    private enum Boms {

        /** UTF-8 BOM。 */
        UTF8(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, StandardCharsets.UTF_8),

        /** UTF-16 小端 BOM。 */
        UTF16_LE(new byte[]{(byte) 0xFF, (byte) 0xFE}, StandardCharsets.UTF_16LE),

        /** UTF-16 大端 BOM。 */
        UTF16_BE(new byte[]{(byte) 0xFE, (byte) 0xFF}, StandardCharsets.UTF_16BE);

        private final byte[] marker;
        private final Charset charset;

        Boms(byte[] marker, Charset charset) {
            this.marker = marker;
            this.charset = charset;
        }

        byte[] marker() {
            return marker;
        }

        Charset charset() {
            return charset;
        }

        int offset() {
            return marker.length;
        }

        /**
         * 识别字节串开头的 BOM。
         *
         * <p>顺序上先查 UTF-16 的两个：它们的标记更短，
         * 而 FF FE 不会被误认成别的标记 —— 这样写是为了避免将来加入
         * 更长的标记时顺序变成一个隐患。
         *
         * @param bytes 字节串
         * @return 匹配到的 BOM；没有则 null
         */
        static Boms of(byte[] bytes) {
            for (Boms candidate : new Boms[]{UTF16_LE, UTF16_BE, UTF8}) {
                if (bytes.length >= candidate.marker().length
                        && Arrays.equals(bytes, 0, candidate.marker().length,
                        candidate.marker(), 0, candidate.marker().length)) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
