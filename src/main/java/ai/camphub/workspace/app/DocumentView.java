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
 * @param retryableByMe 当前调用者是否可以触发重新解析。
 *                      与 {@code deletableByMe} 同一套做法，理由也一样；
 *                      它单独存在是因为两者的矩阵<b>并不相同</b>：
 *                      删除与重解析都看归属，但重解析还要看解析是否正处于
 *                      进行中（进行中时后端会拒绝打断，见 {@code DocumentService#retryParse}）。
 *                      只暴露一个布尔的话，界面只能靠猜来决定要不要显示"重新解析"
 */
public record DocumentView(WorkspaceDocument document,
                           UserBrief uploader,
                           boolean deletableByMe,
                           boolean retryableByMe) {
}
