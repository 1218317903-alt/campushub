package ai.camphub.common.error;

import java.time.Instant;
import java.util.List;

/**
 * 统一错误响应体。
 *
 * <p><b>契约稳定性</b>：本结构是对外契约，所有错误响应（含 4xx / 5xx / 框架抛出的异常）
 * 都必须是这个形状。前端只需实现一套错误处理。
 *
 * <pre>
 * {
 *   "code":      40400,                       // 业务错误码，见 ErrorCode
 *   "message":   "资源不存在",                 // 面向用户的可读文案（不含内部细节）
 *   "traceId":   "3f1a...",                   // 与日志中的 traceId 一致，便于定位
 *   "timestamp": "2026-09-17T08:00:00Z",      // ISO-8601 UTC
 *   "path":      "/api/v1/posts/123",         // 出错的请求路径
 *   "details":   [{"field":"title","reason":"不能为空"}]   // 无字段级错误时为空数组
 * }
 * </pre>
 *
 * <p><b>为什么 details 恒为数组而不是 null</b>：让前端可以无条件写 `for (const d of err.details)`，
 * 不需要先判空。避免为了省几个字节而把判空负担转移给每一个调用方。
 *
 * @param code      业务错误码
 * @param message   面向用户的可读文案
 * @param traceId   链路追踪 ID，与日志一致
 * @param timestamp 错误发生时间（UTC）
 * @param path      出错请求路径
 * @param details   字段级错误明细，无则为空数组
 */
public record ApiError(
        int code,
        String message,
        String traceId,
        Instant timestamp,
        String path,
        List<FieldViolation> details
) {

    /** 字段级错误明细。用于表单类接口把错误定位到具体字段。 */
    public record FieldViolation(String field, String reason) {
    }

    /**
     * 构造不带字段明细的错误响应。
     *
     * @param errorCode 错误码
     * @param traceId   链路 ID
     * @param path      请求路径
     * @return 错误响应体
     */
    public static ApiError of(ErrorCode errorCode, String traceId, String path) {
        return new ApiError(
                errorCode.code(),
                errorCode.defaultMessage(),
                traceId,
                Instant.now(),
                path,
                List.of()
        );
    }

    /**
     * 使用自定义文案构造错误响应。
     *
     * <p>仅用于「希望对外文案比默认更具体」的场景；<b>不要把内部异常信息直接放进来</b>。
     *
     * @param errorCode 错误码
     * @param message   自定义对外文案
     * @param traceId   链路 ID
     * @param path      请求路径
     * @return 错误响应体
     */
    public static ApiError of(ErrorCode errorCode, String message, String traceId, String path) {
        return new ApiError(
                errorCode.code(),
                message,
                traceId,
                Instant.now(),
                path,
                List.of()
        );
    }

    /**
     * 构造带字段明细的错误响应。
     *
     * @param errorCode 错误码
     * @param traceId   链路 ID
     * @param path      请求路径
     * @param details   字段级明细
     * @return 错误响应体
     */
    public static ApiError withDetails(ErrorCode errorCode, String traceId, String path,
                                      List<FieldViolation> details) {
        return new ApiError(
                errorCode.code(),
                errorCode.defaultMessage(),
                traceId,
                Instant.now(),
                path,
                List.copyOf(details)
        );
    }
}
