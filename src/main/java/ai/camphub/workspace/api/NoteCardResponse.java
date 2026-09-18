package ai.camphub.workspace.api;

import ai.camphub.workspace.app.NoteCardView;
import ai.camphub.workspace.domain.NoteSummary;
import java.time.Instant;

/**
 * 笔记列表项的对外表示。
 *
 * <h2>为什么没有 {@code bodyHtml}</h2>
 * 列表页展示的是 {@code summary} —— 它在写入时就从正文派生好了（ADR 0005）。
 * 把正文带进列表会让一页 20 条把几百 KB 的 {@code MEDIUMTEXT} 搬进内存再丢掉，
 * 而且客户端的列表卡片根本不会用到它。
 *
 * @param publicId       笔记对外标识
 * @param title          标题
 * @param summary        纯文本摘要
 * @param author         创建者展示信息，可为 null
 * @param editor         最后编辑者展示信息，可为 null
 * @param deletableByMe  当前调用者是否可以删除这一条。
 *                       服务端算好之后交给前端，避免"显示了按钮、点下去报 404"
 * @param createdAt      创建时间
 * @param updatedAt      最后编辑时间
 */
public record NoteCardResponse(
        String publicId,
        String title,
        String summary,
        ContributorResponse author,
        ContributorResponse editor,
        boolean deletableByMe,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 从应用层视图构造。
     *
     * @param view 笔记列表项与展示信息
     * @return 响应
     */
    public static NoteCardResponse from(NoteCardView view) {
        NoteSummary note = view.note();
        return new NoteCardResponse(
                note.publicId(),
                note.title(),
                note.summary(),
                ContributorResponse.from(view.author()),
                ContributorResponse.from(view.editor()),
                view.deletableByMe(),
                note.createdAt(),
                note.updatedAt());
    }
}
