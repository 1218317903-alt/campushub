package ai.camphub.workspace.app;

import ai.camphub.identity.domain.UserBrief;
import ai.camphub.workspace.domain.WorkspaceDocument;

/**
 * 文档元数据 + 已解析的展示信息。
 *
 * <h2>它不带 {@code storageKey}</h2>
 * 存储键是服务端内部的东西：它指向文件在存储后端里的确切位置，
 * 出现在响应里既没有用途（下载走的是文档的对外标识），
 * 又给了一个"知道键就能猜路径"的目标。它在应用层类型里保留，
 * 是因为下载需要它；对外响应由 {@code DocumentResponse} 决定不含它。
 *
 * @param document      文档元数据
 * @param uploader      上传者展示信息，可为 null
 * @param deletableByMe 当前调用者是否可以删除这一份。
 *                      由 {@code WorkspaceAction.DELETE_DOCUMENT} 的矩阵算出，
 *                      与笔记侧同一套做法 —— 界面上的按钮与是否真的允许
 *                      必须来自同一个答案
 */
public record DocumentView(WorkspaceDocument document, UserBrief uploader, boolean deletableByMe) {
}
