package ai.camphub.workspace.domain;

/**
 * 文档解析状态。
 *
 * <h2>本阶段只有 PENDING 会真正出现</h2>
 * Phase 04 交付的是文档<b>元数据与原始字节</b>（字节写本地磁盘），
 * 而"把内容解析成可检索的文本"这条流水线属于 Phase 05。
 *
 * <p>这四个取值现在就写下来，是因为它们是数据库列的取值域 ——
 * 列的取值域一旦上线就极难收紧，而放过它的代价是"库里存在一个没人认识的字符串"。
 *
 * <h2>为什么要 PROCESSING</h2>
 * 解析是异步的（Phase 05）。没有 PROCESSING 这个中间态，"正在解析"就只能
 * 表达成 PENDING，而前端无法区分"还没轮到"与"正在跑" ——
 * 用户看到的进度会长时间停在 0，看起来像卡住了。
 */
public enum DocumentParseStatus {

    /** 已登记元数据，等待解析。本阶段的终态。 */
    PENDING,

    /** 解析进行中。 */
    PROCESSING,

    /** 解析完成，可用于检索。 */
    READY,

    /** 解析失败，原因见 {@code parse_message}。终态，需要用户重新上传。 */
    FAILED;

    /**
     * 解析是否已进入终态。
     *
     * @return {@code READY} 或 {@code FAILED} 时为 true
     */
    public boolean isTerminal() {
        return this == READY || this == FAILED;
    }
}
