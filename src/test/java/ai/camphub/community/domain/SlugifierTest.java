package ai.camphub.community.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * slug 规范化的单元测试。
 *
 * <p>这组测试的重点有两个：一是"同一个概念的不同写法必须落到同一个 slug"
 * （否则标签会被大小写与空格拆成好几份），二是"中文标签不能被规范化成空串"
 * （本平台的标签大量是纯中文词，这是最容易踩的坑）。
 */
class SlugifierTest {

    @ParameterizedTest
    @CsvSource({
            "Java, java",
            "JAVA, java",
            "  java  , java",
            "Spring Boot, spring-boot",
            "spring   boot, spring-boot",
            "Node.js, node-js",
            "C_Sharp, c-sharp",
            "a--b, a-b"
    })
    @DisplayName("大小写、空白与标点被归一化到同一个 slug")
    void shouldNormalizeToSameSlug(String raw, String expected) {
        assertThat(Slugifier.toSlug(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "C++, c-plus-plus",
            "C, c",
            "C#, c-sharp"
    })
    @DisplayName("C++ / C# 不与 C 合并：这两个符号是语言名的一部分，不是分隔符")
    void shouldKeepPlusAndSharpDistinct(String raw, String expected) {
        assertThat(Slugifier.toSlug(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"考研", "期末复习", "操作系统", "数据结构", "线性代数"})
    @DisplayName("纯中文标签保留原字符，不会被规范成空串")
    void shouldKeepCjkCharacters(String raw) {
        assertThat(Slugifier.toSlug(raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("全角字母数字被折叠成半角（NFKC 规范化）")
    void shouldNormalizeFullWidthCharacters() {
        assertThat(Slugifier.toSlug("ＪＡＶＡ")).isEqualTo("java");
        assertThat(Slugifier.toSlug("ＭｙＳＱＬ")).isEqualTo("mysql");
    }

    @Test
    @DisplayName("中英混排按同一套规则处理")
    void shouldHandleMixedScript() {
        assertThat(Slugifier.toSlug("C语言 程序设计")).isEqualTo("c语言-程序设计");
    }

    @ParameterizedTest
    @ValueSource(strings = {"!!!", "。。。", "  ", "-", "///", "🙂🙂"})
    @DisplayName("不含字母数字的输入返回空串，由调用方决定如何提示用户")
    void shouldReturnEmptyForNonAlphanumericInput(String raw) {
        assertThat(Slugifier.toSlug(raw)).isEmpty();
    }

    @Test
    @DisplayName("null 返回空串而不抛异常")
    void shouldReturnEmptyForNull() {
        assertThat(Slugifier.toSlug(null)).isEmpty();
    }

    @Test
    @DisplayName("超长输入被截断到列宽上限（按码点计，与 MySQL VARCHAR 的计数口径一致）")
    void shouldTruncateToMaxLength() {
        String slug = Slugifier.toSlug("a".repeat(200));

        assertThat(slug).hasSize(Slugifier.MAX_SLUG_LENGTH);
    }

    @Test
    @DisplayName("截断按码点进行，不产生被切断的代理对（否则会写入非法字符串）")
    void shouldTruncateByCodePoint() {
        // U+20000 是扩展 B 区的汉字，属于「字母」且 NFKC 规范化后仍是自身，
        // 每个字符占两个 UTF-16 码元。若按 String.length() 截断，
        // 会在第 48 个码元处把一个字符切成孤立的高位代理。
        String raw = "\uD840\uDC00".repeat(60);

        String slug = Slugifier.toSlug(raw);

        assertThat(slug.codePointCount(0, slug.length())).isEqualTo(Slugifier.MAX_SLUG_LENGTH);
        assertThat(slug).matches("[\\x{20000}-\\x{2A6DF}]+");
    }

    @Test
    @DisplayName("截断后不留下悬空连字符")
    void shouldNotEndWithDashAfterTruncation() {
        // 第 48 个码点正好落在连字符上
        String raw = "a".repeat(47) + " " + "b";

        assertThat(Slugifier.toSlug(raw)).doesNotEndWith("-");
    }

    @Test
    @DisplayName("首尾分隔符不产生悬空连字符")
    void shouldNotLeaveDanglingDashes() {
        assertThat(Slugifier.toSlug("---java---")).isEqualTo("java");
        assertThat(Slugifier.toSlug(" java ")).isEqualTo("java");
    }
}
