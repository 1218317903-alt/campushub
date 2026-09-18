package ai.camphub.common.rendering;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Markdown 渲染与净化的单元测试。
 *
 * <h2>这组测试的真正职责</h2>
 * 它不是"验证 Markdown 能渲染"（那是库自己的事），而是把<b>安全属性</b>钉成断言：
 * 用户提交的任何内容，最终都不能变成可执行的脚本或可点击的 {@code javascript:} 入口。
 * 这些断言必须能在库升级后立刻变红 —— 因为一次依赖升级足以让某条绕过路径重新出现，
 * 而这类变化不会在编译期给出任何提示。
 *
 * <p>刻意<b>不</b>断言具体输出 HTML 的字符串形状（如 {@code "<p>x</p>\n"}）：
 * 那会把测试与 commonmark 的输出细节绑死，一次小版本升级就会产生一堆无意义的红。
 * 断言的是"包含/不包含哪些结构"。
 */
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Nested
    @DisplayName("正常渲染")
    class NormalRendering {

        @Test
        @DisplayName("标题、强调、列表等常规语法被渲染成对应标签")
        void shouldRenderBasicMarkdown() {
            String html = renderer.render("""
                    # 标题

                    这是**加粗**与*斜体*。

                    - 第一项
                    - 第二项
                    """);

            assertThat(html).contains("<h1>").contains("<strong>").contains("<em>");
            assertThat(html).contains("<ul>").contains("<li>");
        }

        @Test
        @DisplayName("GFM 表格被保留（这是引入表格扩展的唯一目的）")
        void shouldRenderTables() {
            String html = renderer.render("""
                    | 科目 | 学分 |
                    | --- | --- |
                    | 数据结构 | 4 |
                    """);

            assertThat(html).contains("<table>").contains("<th>").contains("<td>").contains("数据结构");
        }

        @Test
        @DisplayName("代码块内容被当作文本，其中的尖括号不会被解释成标签")
        void shouldEscapeCodeBlocks() {
            String html = renderer.render("""
                    ```java
                    if (a < b && c > d) { }
                    ```
                    """);

            assertThat(html).contains("<pre>").contains("<code>");
            // 关键：<b> 与 <d> 不能成为真标签
            assertThat(html).contains("&lt;").doesNotContain("<b>");
        }

        @Test
        @DisplayName("空输入返回空串而不是 null（读路径不必到处判空）")
        void shouldReturnEmptyStringForBlankInput() {
            assertThat(renderer.render(null)).isEmpty();
            assertThat(renderer.render("")).isEmpty();
            assertThat(renderer.render("   \n  ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("注入防护")
    class InjectionDefense {

        @Test
        @DisplayName("原始 <script> 标签不会成为可执行标签（escapeHtml 的职责）")
        void shouldNotEmitScriptTag() {
            String html = renderer.render("<script>alert('xss')</script>");

            assertThat(html).doesNotContain("<script");
            // 内容应当以文本形式保留下来 —— 直接删掉会让用户困惑"我写的内容去哪了"
            assertThat(html).contains("alert");
        }

        @Test
        @DisplayName("javascript: 链接不会成为可点击的执行入口（sanitizeUrls 的职责）")
        void shouldStripJavascriptUrl() {
            String html = renderer.render("[点我](javascript:alert('xss'))");

            assertThat(html).doesNotContain("javascript:");
            // 文字保留、链接失效：这是可接受的降级，比整条链接消失更容易理解
            assertThat(html).contains("点我");
        }

        @Test
        @DisplayName("行内原始 HTML 同样被转义，而不是被当作格式标签")
        void shouldEscapeInlineHtml() {
            String html = renderer.render("正常文字 <img src=x onerror=alert(1)> 之后");

            assertThat(html).doesNotContain("onerror=" + "alert");
            assertThat(html).doesNotContain("<img src=x");
        }

        @Test
        @DisplayName("白名单之外的标签被清除（iframe 不能嵌入外部页面）")
        void shouldRejectNonWhitelistedElements() {
            String html = renderer.render("<iframe src=\"https://example.com\"></iframe>");

            assertThat(html).doesNotContain("<iframe");
        }

        @Test
        @DisplayName("data: 协议的图片不会进入输出（data URI 是绕过 URL 白名单的常见手法）")
        void shouldRejectDataUri() {
            String html = renderer.render("![x](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)");

            assertThat(html).doesNotContain("data:text/html");
        }

        @Test
        @DisplayName("链接强制带 rel=nofollow，削弱走垃圾外链的动机")
        void shouldAddNofollowToLinks() {
            String html = renderer.render("[某站](https://example.com)");

            assertThat(html).contains("href=\"https://example.com\"");
            assertThat(html).contains("nofollow");
        }
    }

    @Nested
    @DisplayName("摘要提取")
    class Summarizing {

        @Test
        @DisplayName("摘要剥离 Markdown 标记，不留 ## 与链接语法")
        void shouldStripMarkdownSyntax() {
            String summary = renderer.summarize("## 复习计划\n\n参考[这篇笔记](https://example.com/x)与**课本**。", 300);

            assertThat(summary).doesNotContain("#").doesNotContain("](").doesNotContain("**");
            assertThat(summary).contains("复习计划").contains("这篇笔记").contains("课本");
        }

        @Test
        @DisplayName("超长摘要被截断，且总长度不超过上限（含省略号本身）")
        void shouldTruncateToLimit() {
            String longText = "内容".repeat(200);
            int limit = 50;

            String summary = renderer.summarize(longText, limit);

            assertThat(summary).hasSize(limit);
            assertThat(summary).endsWith("…");
        }

        @Test
        @DisplayName("正好等于上限时不截断、不加省略号")
        void shouldNotTruncateWhenExactlyAtLimit() {
            String text = "a".repeat(30);

            assertThat(renderer.summarize(text, 30)).isEqualTo(text);
        }

        @Test
        @DisplayName("换行被折叠成空格：摘要是单行展示")
        void shouldCollapseNewlines() {
            String summary = renderer.summarize("第一行\n\n第二行\n第三行", 300);

            assertThat(summary).isEqualTo("第一行 第二行 第三行");
        }

        @Test
        @DisplayName("空输入返回空串")
        void shouldReturnEmptySummaryForBlankInput() {
            assertThat(renderer.summarize(null, 300)).isEmpty();
            assertThat(renderer.summarize("\n\n", 300)).isEmpty();
        }
    }
}
