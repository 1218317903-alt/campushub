package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 空间文档（元数据）。
 *
 * @param id           自增主键，模块内部使用
 * @param publicId     对外标识
 * @param workspaceId  所属空间自增主键
 * @param uploaderId   上传者自增主键。删除权限判定的一维
 * @param name         原始文件名。<b>仅展示用</b>，绝不参与路径拼接
 * @param mimeType     内容类型
 * @param sizeBytes    字节数
 * @param storageKey   对象存储键，由 {@code ObjectStorage} 生成。
 *                     可空，但只在历史行上才会为空 —— 见 {@link #hasStoredContent()}
 * @param sha256       内容哈希，可为 null
 * @param parseStatus  解析状态；本阶段恒为 {@link DocumentParseStatus#PENDING}
 * @param parseMessage 解析失败原因，可为 null
 * @param createdAt    创建时间
 * @param updatedAt    最后修改时间
 */
public record WorkspaceDocument(
        long id,
        String publicId,
        long workspaceId,
        long uploaderId,
        String name,
        String mimeType,
        long sizeBytes,
        String storageKey,
        String sha256,
        DocumentParseStatus parseStatus,
        String parseMessage,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 原文件是否已经真正落到存储上。
     *
     * <p>Phase 04 起上传会把字节写进存储，因此新登记的文档这里恒为 true。
     * 它为 false 只可能出现在两种情形：字节接入之前登记的历史行，
     * 或存储写入被外部清理过。下载接口据此返回
     * {@code 503 DEPENDENCY_UNAVAILABLE} 而不是 404 ——
     * 资源确实存在、也确实有权访问，只是承载它的那份内容取不到。
     * 返回 404 会让使用者以为自己的东西丢了。
     *
     * @return 是否已具备可下载的原文件
     */
    public boolean hasStoredContent() {
        return storageKey != null && !storageKey.isBlank();
    }
}
