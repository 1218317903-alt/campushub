package ai.camphub.common.error;

import org.springframework.http.HttpStatus;

/**
 * 全局业务错误码。
 *
 * <h2>编码规则（5 位数字）</h2>
 * <pre>
 *   HHH SS
 *   │   └─ 同一 HTTP 状态内的序号（00 起）
 *   └───── HTTP 状态码，便于一眼看出该错误对应的传输语义
 * </pre>
 * 例：{@code 40400} = HTTP 404 的第 0 号错误。
 *
 * <h2>序号分配约定（防止各模块撞号）</h2>
 * <table border="1">
 *   <caption>序号段</caption>
 *   <tr><td>40000 ~ 40009</td><td>通用请求错误（参数、校验、类型）</td></tr>
 *   <tr><td>40010 ~ 40019</td><td>identity 模块（Phase 02 起）</td></tr>
 *   <tr><td>40020 ~ 40029</td><td>workspace 模块（Phase 04 起）</td></tr>
 *   <tr><td>40030 ~ 40039</td><td>community 模块（Phase 03 起）</td></tr>
 *   <tr><td>40040 ~ 40049</td><td>ingestion / discover 模块（Phase 06 起）</td></tr>
 *   <tr><td>40050 ~ 40059</td><td>ai 模块（Phase 08 起）</td></tr>
 * </table>
 * 新增错误码时先在本文档登记，再使用。
 */
public enum ErrorCode {

    // ---------- 400 ----------
    /** 请求体字段校验失败（由 Bean Validation 触发）。 */
    VALIDATION_FAILED(40000, HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    /** 请求参数本身不合法（业务规则层面的前置判断）。 */
    BAD_REQUEST(40001, HttpStatus.BAD_REQUEST, "请求参数不合法"),
    /** 参数类型无法转换，例如把 "abc" 传给 int 型参数。 */
    TYPE_MISMATCH(40002, HttpStatus.BAD_REQUEST, "参数类型不正确"),
    /** 缺少必填参数。 */
    MISSING_PARAMETER(40003, HttpStatus.BAD_REQUEST, "缺少必要参数"),

    // ---------- 404 ----------
    /** 目标资源不存在。注意：无权访问时对外也应表现为 404，避免泄漏资源是否存在。 */
    NOT_FOUND(40400, HttpStatus.NOT_FOUND, "资源不存在"),

    // ---------- 405 / 415 ----------
    /** HTTP 方法不支持。 */
    METHOD_NOT_ALLOWED(40500, HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被支持"),
    /** 请求媒体类型不支持。 */
    UNSUPPORTED_MEDIA_TYPE(41500, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的内容类型"),

    // ---------- 5xx ----------
    /** 未预期的服务端异常。对外不暴露内部细节，细节只进日志。 */
    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后重试"),
    /** 依赖的外部系统暂不可用。 */
    DEPENDENCY_UNAVAILABLE(50300, HttpStatus.SERVICE_UNAVAILABLE, "依赖服务暂时不可用，请稍后重试");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
