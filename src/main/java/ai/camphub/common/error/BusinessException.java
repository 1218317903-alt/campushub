package ai.camphub.common.error;

import java.util.Objects;

/**
 * 业务异常。
 *
 * <p>用法：业务代码在「可以明确判断为业务失败」的分支抛出本异常，
 * 由 {@link GlobalExceptionHandler} 统一翻译为 {@link ApiError}。
 *
 * <pre>
 * if (post.isDeleted()) {
 *     throw new BusinessException(ErrorCode.NOT_FOUND);
 * }
 * </pre>
 *
 * <p><b>约定</b>：
 * <ul>
 *   <li>本异常携带的 {@code message} 会直接返回给用户，<b>不得包含内部实现细节</b>
 *       （SQL、堆栈、文件路径、内部 ID 规则）。</li>
 *   <li>不用于表达「程序缺陷」类错误，那类错误让它自然抛出、走 500 分支并记日志。</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /**
     * 使用错误码的默认文案。
     *
     * @param errorCode 错误码
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /**
     * 使用自定义对外文案。
     *
     * @param errorCode 错误码
     * @param message   面向用户的文案（不得含内部细节）
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /**
     * 携带底层原因，便于日志排查。注意：{@code cause} 不会返回给用户。
     *
     * @param errorCode 错误码
     * @param message   面向用户的文案
     * @param cause     底层原因
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
