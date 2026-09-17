package ai.camphub.community.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 名称规范化：把用户输入的标签名转成稳定的对外标识（slug）。
 *
 * <h2>为什么需要它，而不是直接用标签名做标识</h2>
 * 标签页的 URL 与查询参数需要一个<b>稳定</b>的键。用标签名直接做键会遇到：
 * {@code Java} / {@code java} / {@code JAVA } 是三个不同的字符串但显然是同一个标签。
 * 归一化之后它们在 {@code utf8mb4_0900_ai_ci} 的唯一索引上自然撞在一起 ——
 * 这正是期望行为，否则同一个概念会被拆成好几个标签页，每个都只有零星几条内容。
 *
 * <h2>为什么保留中日韩字符</h2>
 * 本平台的标签大量会是「考研」「期末复习」「操作系统」这类纯中文词。若按
 * "只保留 ASCII 字母数字"的常规做法，它们会被规范成空串 —— 于是要么给每个中文标签
 * 编一个无意义的拼音/哈希标识（失去可读性），要么干脆禁止中文标签（不可接受）。
 * 因此这里的规则是<b>保留所有 Unicode 字母与数字</b>（含汉字、假名、谚文），
 * 只把分隔性与标点字符折叠成连字符。URL 中的非 ASCII 会由客户端自动百分号编码，
 * 这是标准行为，不需要特殊处理。
 *
 * <h2>长度与截断</h2>
 * 结果会截断到 {@link #MAX_SLUG_LENGTH}，与 {@code tag.slug VARCHAR(48)} 对齐。
 * 截断按<b>码点</b>而不是 char 进行 —— Java 的 {@code String.length()} 是 UTF-16 码元数，
 * 直接用它截断可能把一个由代理对表示的字符切成两半，产出非法字符串。
 */
public final class Slugifier {

    /** 与 {@code tag.slug VARCHAR(48)} 列宽一致。 */
    public static final int MAX_SLUG_LENGTH = 48;

    private Slugifier() {
    }

    /**
     * 把名称规范化为 slug。
     *
     * <p>规则：Unicode 规范化（NFKC，把全角字母数字折叠成半角）→ 转小写 →
     * 字母数字保留、空白与 {@code - _ . } 折叠为连字符、{@code +} 与 {@code #}
     * 展开为 {@code plus} / {@code sharp}、其余字符丢弃 →
     * 连续连字符合并 → 去掉首尾连字符 → 截断。
     *
     * <p><b>不抛异常</b>：规范化失败（结果为空）由调用方决定如何处理 ——
     * 例如"标签名全是标点"应该是一次可解释的参数校验失败，而不是这里悄悄
     * 返回一个空 slug 让数据库唯一索引去报错。返回值可能为空串，调用方必须判断。
     *
     * @param raw 原始名称，可为 null
     * @return 规范化结果；可能为空串（当输入不含任何字母或数字时）
     */
    public static String toSlug(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip();

        StringBuilder slug = new StringBuilder(normalized.length());
        boolean pendingDash = false;

        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);

            if (Character.isLetterOrDigit(cp)) {
                if (pendingDash && !slug.isEmpty()) {
                    slug.append('-');
                }
                pendingDash = false;
                slug.appendCodePoint(cp);
            } else if (cp == '+' || cp == '#') {
                // 这两个符号在编程语言名里是**名字的一部分**，不是分隔符。
                // 若按普通标点丢弃，"C++" 会被规范成 "c"，与 "C" 撞在同一个唯一键上 ——
                // 两个不同的标签被静默合并，而且这种合并不会有人来报错，只会表现为
                // "我明明建了 C++ 标签，怎么和 C 是同一个"。
                // 展开成 -plus / -sharp 后既保持可读，也保持唯一。
                if (!slug.isEmpty()) {
                    slug.append('-');
                }
                pendingDash = false;
                slug.append(cp == '+' ? "plus" : "sharp");
            } else if (Character.isWhitespace(cp) || cp == '-' || cp == '_' || cp == '.') {
                // 只记下"待插入连字符"，不立即写入：这样连续的分隔符只会产生一个连字符，
                // 且尾部的分隔符不会留下悬空的连字符
                pendingDash = true;
            }
            // 其余字符（含各类标点、emoji、控制字符）直接丢弃
        }
        return truncateByCodePoint(slug.toString());
    }

    /**
     * 按码点截断到列宽上限。
     *
     * <p>必须按码点而不是按 {@code String.length()}：后者是 UTF-16 码元数，
     * 一个 BMP 之外的字符（例如扩展 B 区的汉字）占两个码元，
     * 按码元截断会把它切成孤立的高位代理 —— 那是一个在传给数据库时才暴露的非法字符串。
     *
     * <p>同时要处理截断后落在连字符上的情况：{@code spring-boot} 截到第 7 个码点会变成
     * {@code spring-}，那是一个视觉上不完整、且与 {@code spring} 不等价的 slug。
     *
     * @param slug 已规范化的结果
     * @return 不超过 {@link #MAX_SLUG_LENGTH} 个码点的结果
     */
    private static String truncateByCodePoint(String slug) {
        if (slug.codePointCount(0, slug.length()) <= MAX_SLUG_LENGTH) {
            return slug;
        }
        String truncated = slug.substring(0, slug.offsetByCodePoints(0, MAX_SLUG_LENGTH));
        return truncated.endsWith("-")
                ? truncated.substring(0, truncated.length() - 1)
                : truncated;
    }
}
