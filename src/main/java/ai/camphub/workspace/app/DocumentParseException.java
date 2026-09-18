package ai.camphub.workspace.app;

/**
 * 文档解析失败。
 *
 * <h2>这个异常存在的唯一理由是区分"该不该重试"</h2>
 * 解析失败有两类，而它们的处理方式完全相反：
 * <ul>
 *   <li><b>可重试</b>：存储暂时读不到、数据库连接断开、解析超出时间上限。
 *       这类失败重试一次很可能就成功了。</li>
 *   <li><b>不可重试</b>：文件损坏、加密 PDF 需要密码、页数超出上限、格式不支持。
 *       这类失败重试一百次都是同样的结果，只是把 CPU 与时间再烧一遍。</li>
 * </ul>
 *
 * <p>若只有一个 {@code RuntimeException}，worker 就只能对所有失败一视同仁地重试。
 * 后果不是"多试几次"这么轻：一份 200 MB 的损坏 PDF 会按退避策略被完整重读
 * 三次，而且每次都以耗尽解析超时结束 —— 用户看到的是文档长时间停在"处理中"，
 * 最后才失败，而他本可以在上传后一秒内就被告知"这份文件读不了"。
 *
 * <h2>两个消息字段是刻意的</h2>
 * {@code getMessage()} 面向日志（可以带文件名、字节偏移这类内部线索），
 * {@link #userMessage()} 面向用户（一句话说清是什么问题、能不能自救），
 * 它会写入 {@code document.parse_message} 并直接显示在界面上。
 * 用一个字段同时干这两件事的结果，通常是日志不够用、而界面泄漏了内部细节。
 */
public class DocumentParseException extends RuntimeException {

    /** 序列化兼容标识。异常本身不建议序列化，这里只是消除告警。 */
    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final String userMessage;

    private DocumentParseException(boolean retryable, String userMessage, String logMessage,
                                   Throwable cause) {
        super(logMessage, cause);
        this.retryable = retryable;
        this.userMessage = userMessage;
    }

    /**
     * 构造一个不可重试的失败：这份内容按这个格式读不出来，再试也一样。
     *
     * @param userMessage 面向用户的一句话，会显示在界面上
     * @param logMessage  面向日志的一句话，可以带内部细节
     * @param cause       原始异常，可为 null
     * @return 异常实例
     */
    public static DocumentParseException permanent(String userMessage, String logMessage,
                                                   Throwable cause) {
        return new DocumentParseException(false, userMessage, logMessage, cause);
    }

    /**
     * 构造一个可重试的失败：这次没成，但重试有希望。
     *
     * @param userMessage 面向用户的一句话
     * @param logMessage  面向日志的一句话
     * @param cause       原始异常，可为 null
     * @return 异常实例
     */
    public static DocumentParseException transientFailure(String userMessage, String logMessage,
                                                          Throwable cause) {
        return new DocumentParseException(true, userMessage, logMessage, cause);
    }

    /**
     * 重试是否有可能改变结果。
     *
     * @return 值得重试时为 true
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 面向用户的原因说明。
     *
     * <p>它会写入 {@code document.parse_message} 并显示在界面上的失败提示里，
     * 因此<b>不含任何内部细节</b>：没有文件路径、没有异常类名、没有堆栈。
     *
     * @return 一句话说明
     */
    public String userMessage() {
        return userMessage;
    }
}
