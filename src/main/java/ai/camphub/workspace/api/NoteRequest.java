package ai.camphub.workspace.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建或编辑笔记的请求体。
 *
 * <h2>为什么请求体里没有 {@code bodyHtml}</h2>
 * HTML 是服务端从 {@code bodyMd} 渲染并净化后的<b>派生结果</b>（ADR 0005）。
 * 若允许客户端提交 HTML，等于把净化白名单交给调用方 —— 不提交的那个客户端是安全的，
 * 提交的那个是攻击面。派生值不进请求体，是这个边界在类型上的表达。
 *
 * <h2>字段与列的对应</h2>
 * <ul>
 *   <li>{@code title} → {@code note.title VARCHAR(200)}（注解上限 200）</li>
 *   <li>{@code bodyMd} → {@code note.body_md MEDIUMTEXT}。
 *       注解这里<b>不设</b>长度上限 —— 上限来自
 *       {@code app.workspace.notes.max-body-length}，它同时约束渲染与净化的代价。
 *       在注解里再写一个数字，会让"到底哪个才是真上限"变成需要读两处才能回答的问题</li>
 * </ul>
 *
 * @param title  标题
 * @param bodyMd Markdown 正文
 */
public record NoteRequest(
        @NotBlank(message = "笔记标题不能为空")
        @Size(max = 200, message = "笔记标题最多 200 个字符")
        String title,

        @NotBlank(message = "笔记正文不能为空")
        String bodyMd
) {
}
