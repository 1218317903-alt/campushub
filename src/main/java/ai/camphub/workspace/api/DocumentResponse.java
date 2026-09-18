package ai.camphub.workspace.api;

import ai.camphub.workspace.app.DocumentView;
import ai.camphub.workspace.domain.WorkspaceDocument;
import java.time.Instant;

/**
 * 文档元数据的对外表示。
 *
 * <h2>它刻意不暴露的两个字段</h2>
 * <ul>
 *   <li><b>{@code storageKey}</b>：它是文件在存储后端里的确切位置。
 *       下载走的是文档的对外标识，因此它对客户端毫无用途；
 *       而它一旦出现在响应里，"知道键就能猜路径"就成了一个真实的目标。
 *       不返回它，是让"存储路径从不离开服务端"由响应形状来保证。</li>
 *   <li><b>{@code sha256}</b>：它看起来人畜无害（客户端刚上传了这份字节），
 *       但它同时是一个<b>内容指纹</b> —— 任何能读到这个空间的人都可以
 *       用一份已知文件的哈希去确认"这份文件在不在这里"。
 *       对私有空间里的文件来说，这是一个信息泄漏，而界面上没有任何地方需要它。</li>
 * </ul>
 *
 * <h2>Phase 05 新增的四个字段都是"让界面不必猜"</h2>
 * 解析是一条异步流水线，上传接口返回 201 时它才刚入队。若响应里只有
 * {@code parseStatus} 一个状态位，界面就只能显示"解析中"并无限期等待 ——
 * 而用户实际想知道的是"它卡住了还是在动"。因此：
 * <ul>
 *   <li>{@code parseProgress} 让"在动"可见（0 → 5 → 100）；</li>
 *   <li>{@code chunkCount} 与 {@code textLength} 是解析结果的<b>事实</b>，
 *       它们让"解析成功"不再只是一个没人验证过的状态位 ——
 *       一份 200 页的 PDF 抽出 0 个字符时，这两个字段会立刻暴露它；</li>
 *   <li>{@code retryableByMe} 与 {@code deletableByMe} 同一套做法：
 *       界面上的按钮与后端是否真的允许，必须来自同一个答案。</li>
 * </ul>
 *
 * @param publicId       文档对外标识
 * @param name           原始文件名（已净化）
 * @param mimeType       内容类型
 * @param sizeBytes      字节数
 * @param parseStatus    解析状态：{@code PENDING} / {@code PROCESSING} /
 *                       {@code READY} / {@code FAILED}
 * @param parseProgress  解析进度百分比（0~100）；未完成时表示"走到哪一步了"
 * @param parseMessage   面向用户的解析失败原因，可为 null
 * @param chunkCount     解析产出的分块数；未解析时为 0
 * @param textLength     抽出的正文字符数；未解析时为 0
 * @param parsedAt       解析完成时间，可为 null
 * @param uploader       上传者展示信息，可为 null（账号已注销）
 * @param deletableByMe  当前调用者是否可以删除这一份
 * @param retryableByMe  当前调用者是否可以触发重新解析
 * @param createdAt      上传时间
 * @param updatedAt      最后修改时间
 */
public record DocumentResponse(
        String publicId,
        String name,
        String mimeType,
        long sizeBytes,
        String parseStatus,
        int parseProgress,
        String parseMessage,
        int chunkCount,
        int textLength,
        Instant parsedAt,
        ContributorResponse uploader,
        boolean deletableByMe,
        boolean retryableByMe,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 从应用层视图构造。
     *
     * @param view 文档元数据与展示信息
     * @return 响应
     */
    public static DocumentResponse from(DocumentView view) {
        WorkspaceDocument document = view.document();
        return new DocumentResponse(
                document.publicId(),
                document.name(),
                document.mimeType(),
                document.sizeBytes(),
                document.parseStatus().name(),
                document.parseProgress(),
                document.parseMessage(),
                document.chunkCount(),
                document.textLength(),
                document.parsedAt(),
                ContributorResponse.from(view.uploader()),
                view.deletableByMe(),
                view.retryableByMe(),
                document.createdAt(),
                document.updatedAt());
    }
}
