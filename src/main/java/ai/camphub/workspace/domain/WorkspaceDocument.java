package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 空间文档（元数据 + 解析结果 + 删除标记）。
 *
 * <h2>解析结果为什么和元数据放在同一个 record 里</h2>
 * 它们在数据库里是同一行（{@code V6} 给 {@code document} 加了这几列）。
 * 分开成两个 record 会让每次读元数据都要回答"我要不要那个状态" ——
 * 而列表页正需要它（用户要看到哪份文档还在解析）。
 *
 * <h2>为什么还要带上 deletedAt</h2>
 * 请求路径的查询全部带 {@code deleted_at IS NULL}，因此对它们这个字段恒为 null。
 * 它存在是为了<b>后台路径</b>：worker 按主键取文档时不过滤删除标记，
 * 而它必须先知道"这份文档还在不在"，才能决定是跳过解析还是照常执行。
 * 少了这个字段，worker 只能靠"更新影响 0 行"来事后推断 ——
 * 那时它已经把整份文件解析了一遍。
 *
 * @param id            自增主键，模块内部使用
 * @param publicId      对外标识
 * @param workspaceId   所属空间自增主键
 * @param uploaderId    上传者自增主键。删除权限判定的一维
 * @param name          原始文件名。<b>仅展示用</b>，绝不参与路径拼接
 * @param mimeType      内容类型
 * @param sizeBytes     字节数
 * @param storageKey    对象存储键，由 {@code ObjectStorage} 生成。
 *                      <b>为 null 表示字节已从存储清除</b>，见 {@link #hasStoredContent()}
 * @param sha256        内容哈希，可为 null
 * @param parseStatus   解析状态
 * @param parseMessage  解析失败原因（面向用户，不含内部堆栈），可为 null
 * @param parseProgress 解析进度 0~100
 * @param chunkCount    产出的分块数
 * @param textLength    抽出的正文字符数
 * @param parserVersion 产出当前结果的解析器版本，可为 null
 * @param parsedAt      最近一次解析成功的时间，可为 null
 * @param createdAt     创建时间
 * @param updatedAt     最后修改时间
 * @param deletedAt     软删除时间；未删除时为 null
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
        int parseProgress,
        int chunkCount,
        int textLength,
        String parserVersion,
        Instant parsedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {

    /**
     * 原文件是否还在存储上。
     *
     * <p>为 null（或空白）只可能出现在两种情况：字节接入之前登记的历史行，
     * 或删除流程已经把字节清掉。下载接口据此返回
     * {@code 503 DEPENDENCY_UNAVAILABLE} 而不是 404 ——
     * 资源确实存在、也确实有权访问，只是承载它的那份内容取不到。
     * 返回 404 会让使用者以为自己的东西丢了。
     *
     * <p>注意它与 {@link #deletedAt} 是两件事：软删除的文档行还在，
     * 但它的 {@code storage_key} 只有在 CLEANUP 任务成功之后才会变成 null。
     * 中间这个窗口正是"元数据已删、字节未清"的那个窗口。
     *
     * @return 是否已具备可下载的原文件
     */
    public boolean hasStoredContent() {
        return storageKey != null && !storageKey.isBlank();
    }

    /**
     * 是否已经完成解析且产出了可检索的内容。
     *
     * <h2>为什么把"就绪"与"有内容"分开</h2>
     * {@code parseStatus == READY} 只说明<b>处理流程走完了</b>，
     * 不说明一定有内容。扫描件 PDF 就是典型：解析成功、零个分块。
     * 把两者混为一谈的实现会在扫描件上给出错误的提示
     * （"解析失败"或"可检索"），而用户手上那份文件完全正常。
     *
     * @return 已就绪且有可检索内容时为 true
     */
    public boolean hasSearchableContent() {
        return parseStatus == DocumentParseStatus.READY && chunkCount > 0;
    }

    /**
     * 是否已被软删除。
     *
     * @return 已删除时为 true
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
