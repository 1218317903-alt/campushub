package ai.camphub.workspace.api;

import ai.camphub.workspace.app.NoteDetailView;
import ai.camphub.workspace.domain.NoteDetail;
import java.time.Instant;

/**
 * 笔记详情的对外表示。
 *
 * <h2>为什么 {@code bodyMd} 与 {@code bodyHtml} 一起返回</h2>
 * 这是 ADR 0005 在对外契约上的直接后果，也是本系统与"前端自己渲染 Markdown"
 * 做法的分界线：
 * <ul>
 *   <li>{@code bodyHtml} 是服务端用同一套白名单净化后的结果，供展示直接插入。
 *       前端<b>不应</b>再对它做一次 Markdown 渲染 —— 那等于把净化过的内容
 *       重新解析一遍，而第二次解析器未必有同样的白名单。</li>
 *   <li>{@code bodyMd} 供编辑器绑定。编辑必须是精确往返：若编辑器只能拿到 HTML，
 *       用户保存一次就会把原文改写成 HTML 的序列化结果，Markdown 里的
 *       表格、脚注、自定义语法会在第一次编辑时静默丢失。</li>
 * </ul>
 *
 * @param publicId       笔记对外标识
 * @param title          标题
 * @param summary        纯文本摘要
 * @param bodyMd         Markdown 原文，供编辑器绑定
 * @param bodyHtml       渲染并净化后的 HTML，供展示
 * @param author         创建者展示信息，可为 null
 * @param editor         最后编辑者展示信息，可为 null
 * @param deletableByMe  当前调用者是否可以删除这一条
 * @param createdAt      创建时间
 * @param updatedAt      最后编辑时间
 */
public record NoteDetailResponse(
        String publicId,
        String title,
        String summary,
        String bodyMd,
        String bodyHtml,
        ContributorResponse author,
        ContributorResponse editor,
        boolean deletableByMe,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 从应用层视图构造。
     *
     * @param view 笔记详情与展示信息
     * @return 响应
     */
    public static NoteDetailResponse from(NoteDetailView view) {
        NoteDetail note = view.note();
        return new NoteDetailResponse(
                note.publicId(),
                note.title(),
                note.summary(),
                note.bodyMd(),
                note.bodyHtml(),
                ContributorResponse.from(view.author()),
                ContributorResponse.from(view.editor()),
                view.deletableByMe(),
                note.createdAt(),
                note.updatedAt());
    }
}
