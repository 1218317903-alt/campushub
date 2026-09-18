package ai.camphub.workspace.app;

import ai.camphub.identity.domain.UserBrief;
import ai.camphub.workspace.domain.NoteSummary;

/**
 * 笔记列表项 + 已解析的展示信息。
 *
 * <h2>为什么它是应用层类型而不是对外响应类型</h2>
 * 它承载的是"组装这条响应需要哪些东西"这一层判断（例如"作者是谁"要靠
 * {@code UserDirectory} 批量换出来）。对外响应形状属于 {@code api} 包，
 * 它的字段取舍与这里不同：响应里不该出现自增主键，而这个 View 必须带着它
 * 才能完成去重与批量查询。
 *
 * <p>作者与编辑者都可能为 {@code null}（账号被软删除）。<b>刻意不用空壳对象占位</b>：
 * 一个昵称为空串的用户与"这个用户不存在"在界面上无法区分，
 * 而前者看起来像数据损坏。
 *
 * @param note           笔记列表数据
 * @param author         创建者展示信息，可为 null
 * @param editor         最后编辑者展示信息，可为 null
 * @param deletableByMe  当前调用者是否可以删除这一条。
 *                       由 {@code WorkspaceAction.DELETE_NOTE} 的矩阵算出来，
 *                       而不是在前端按"是不是我写的"猜 —— 管理员与拥有者
 *                       本就可以删别人的笔记，前端猜不出这件事
 */
public record NoteCardView(NoteSummary note, UserBrief author, UserBrief editor, boolean deletableByMe) {
}
