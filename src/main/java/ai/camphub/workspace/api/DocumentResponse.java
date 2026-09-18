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
 * <h2>字段与列的对应</h2>
 * {@code publicId} / {@code name} / {@code mimeType} / {@code sizeBytes} /
 * {@code parseStatus} / {@code parseMessage} / {@code createdAt} / {@code updatedAt}
 * 一一对应 {@code document} 表的同名列；{@code uploader} 与 {@code deletableByMe}
 * 是查出来/算出来的。
 *
 * @param publicId       文档对外标识
 * @param name           原始文件名（已净化）
 * @param mimeType       内容类型
 * @param sizeBytes      字节数
 * @param parseStatus    解析状态；本阶段恒为 {@code PENDING}
 * @param parseMessage   解析失败原因，可为 null
 * @param uploader       上传者展示信息，可为 null（账号已注销）
 * @param deletableByMe  当前调用者是否可以删除这一份
 * @param createdAt      上传时间
 * @param updatedAt      最后修改时间
 */
public record DocumentResponse(
        String publicId,
        String name,
        String mimeType,
        long sizeBytes,
        String parseStatus,
        String parseMessage,
        ContributorResponse uploader,
        boolean deletableByMe,
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
                document.parseMessage(),
                ContributorResponse.from(view.uploader()),
                view.deletableByMe(),
                document.createdAt(),
                document.updatedAt());
    }
}
