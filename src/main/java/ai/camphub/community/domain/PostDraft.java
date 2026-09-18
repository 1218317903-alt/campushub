package ai.camphub.community.domain;

import java.time.Instant;

/**
 * 待发布的帖子。
 *
 * <h2>为什么需要它，而不是复用 {@link PostDetail}</h2>
 * 两者的字段集合不同，而且差异是本质的：
 * <ul>
 *   <li>插入语句要的是 {@code category_id}（外键），而读取到的帖子带的是
 *       {@code category_slug} / {@code category_name}（join 出来的展示字段）。
 *       若为了复用而给 {@link PostDetail} 也加上 category_id，读模型里就会同时存在
 *       同一概念的三个字段，之后每处渲染都要判断"该用哪个"。</li>
 *   <li>{@code body_html} 是服务端从 {@code body_md} 派生出来的，不是调用方提供的事实。
 *       把它放进一个叫 Draft 的类型里，正好让"派生值由服务端负责"这件事在类型上可见。</li>
 * </ul>
 * 字段与 INSERT 的列一一对应，映射层不需要任何额外组装。
 *
 * <p>它同时也是"编辑"的输入：编辑与发布要更新的列相同，因此不需要第二个类型。
 *
 * @param publicId    对外标识，由 {@link ai.camphub.common.random.RandomValues#publicId()} 生成
 * @param authorId    作者自增主键。资源级鉴权靠它判定"这条帖子是不是我的"
 * @param categoryId  板块自增主键（由 slug 解析而来，不是客户端直接传的数字）
 * @param title       标题
 * @param summary     摘要，由 {@link ai.camphub.common.rendering.MarkdownRenderer#summarize} 派生
 * @param bodyMd      Markdown 原文
 * @param bodyHtml    净化后的 HTML，由 {@link ai.camphub.common.rendering.MarkdownRenderer#render} 派生
 * @param publishedAt 发布时间。<b>编辑时不得修改</b>：它表达"这条内容是什么时候出现的"，
 *                    改它会让时间线排序失去意义
 */
public record PostDraft(
        String publicId,
        long authorId,
        int categoryId,
        String title,
        String summary,
        String bodyMd,
        String bodyHtml,
        Instant publishedAt
) {
}
