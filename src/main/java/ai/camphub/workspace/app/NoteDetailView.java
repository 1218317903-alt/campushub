package ai.camphub.workspace.app;

import ai.camphub.identity.domain.UserBrief;
import ai.camphub.workspace.domain.NoteDetail;

/**
 * 笔记详情 + 已解析的展示信息。
 *
 * @param note          笔记详情（含 Markdown 原文与净化后的 HTML）
 * @param author        创建者展示信息，可为 null
 * @param editor        最后编辑者展示信息，可为 null
 * @param deletableByMe 当前调用者是否可以删除这一条。与 {@link NoteCardView} 同义，
 *                      两处都由同一张权限矩阵算出 —— 界面上的按钮与
 *                      服务端的判定必须来自同一个答案
 */
public record NoteDetailView(NoteDetail note, UserBrief author, UserBrief editor, boolean deletableByMe) {
}
