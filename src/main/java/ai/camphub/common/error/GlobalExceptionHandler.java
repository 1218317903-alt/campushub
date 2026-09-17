package ai.camphub.common.error;

import ai.camphub.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：把「任何」异常统一翻译为 {@link ApiError}。
 *
 * <h2>设计要点</h2>
 * <ol>
 *   <li><b>错误形状唯一</b>：不管异常来自业务代码还是框架，出口都是同一种 JSON 结构。
 *       Spring 自带的默认错误响应（{@code timestamp/status/error/path}）与我们的契约不同，
 *       所以必须在这里全部接管，否则前端要写两套解析。</li>
 *   <li><b>不泄漏内部信息</b>：4xx 只回可读文案；5xx 一律回固定文案，
 *       真正的异常细节只写日志。返回堆栈或 SQL 片段是典型的信息泄漏。</li>
 *   <li><b>日志分级</b>：4xx 属于"调用方的问题"，记 warn（且不打堆栈，避免刷爆日志）；
 *       5xx 属于"我们的问题"，记 error 并带完整堆栈。</li>
 *   <li><b>traceId 贯穿</b>：每个响应都带 traceId，与日志一一对应。</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：可直接映射为对外错误码。
     *
     * @param ex      业务异常
     * @param request 当前请求
     * @return 统一错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.errorCode();
        log.warn("业务异常 code={} path={} message={}", errorCode.code(), request.getRequestURI(), ex.getMessage());
        return build(errorCode, ex.getMessage(), request);
    }

    /**
     * 限流异常：与普通业务异常的唯一区别是要带 {@code Retry-After} 响应头。
     *
     * <p>必须比 {@link #handleBusiness} 更具体，Spring 才会优先选中本方法。
     * 把 {@code Retry-After} 放在响应头而不是只写进 JSON 体，是为了让通用的
     * HTTP 客户端与网关也能正确退避，而不需要它们解析我们的业务报文。
     *
     * @param ex      限流异常
     * @param request 当前请求
     * @return 统一错误响应，附带 Retry-After
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ApiError> handleRateLimited(RateLimitedException ex, HttpServletRequest request) {
        long retryAfterSeconds = Math.max(1, ex.retryAfter().toSeconds());
        log.warn("触发限流 path={} retryAfter={}s", request.getRequestURI(), retryAfterSeconds);
        ApiError body = ApiError.of(ErrorCode.RATE_LIMITED, ex.getMessage(),
                TraceIdFilter.currentTraceId(), request.getRequestURI());
        return ResponseEntity.status(ErrorCode.RATE_LIMITED.httpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(body);
    }

    /**
     * 请求体字段校验失败（{@code @Valid} 作用于 {@code @RequestBody}）。
     *
     * @param ex      校验异常
     * @param request 当前请求
     * @return 统一错误响应，含字段级明细
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
                                                        HttpServletRequest request) {
        List<ApiError.FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldViolation)
                .toList();
        log.warn("请求体校验失败 path={} 字段数={}", request.getRequestURI(), details.size());
        return build(ErrorCode.VALIDATION_FAILED, request, details);
    }

    /**
     * 方法参数校验失败（{@code @Validated} 作用于 Controller 的查询参数/路径变量）。
     *
     * @param ex      校验异常
     * @param request 当前请求
     * @return 统一错误响应，含字段级明细
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex,
                                                          HttpServletRequest request) {
        // Spring Framework 7 的 API 是 getParameterValidationResults()；
        // ParameterValidationResult 提供参数名与校验失败原因。
        List<ApiError.FieldViolation> details = ex.getParameterValidationResults().stream()
                .map(result -> {
                    String parameterName = result.getMethodParameter().getParameterName();
                    String reason = result.getResolvableErrors().isEmpty()
                            ? ErrorCode.VALIDATION_FAILED.defaultMessage()
                            : result.getResolvableErrors().get(0).getDefaultMessage();
                    return new ApiError.FieldViolation(parameterName, reason);
                })
                .toList();
        log.warn("方法参数校验失败 path={} 字段数={}", request.getRequestURI(), details.size());
        return build(ErrorCode.VALIDATION_FAILED, request, details);
    }

    /**
     * 方法级约束校验失败（{@code @Validated} + {@code @NotBlank} 等直接标注在参数上）。
     *
     * @param ex      校验异常
     * @param request 当前请求
     * @return 统一错误响应，含字段级明细
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                             HttpServletRequest request) {
        List<ApiError.FieldViolation> details = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::toFieldViolation)
                .toList();
        log.warn("约束校验失败 path={} 字段数={}", request.getRequestURI(), details.size());
        return build(ErrorCode.VALIDATION_FAILED, request, details);
    }

    /**
     * 参数类型不匹配，例如路径变量声明为数字却传入非数字。
     *
     * @param ex      类型不匹配异常
     * @param request 当前请求
     * @return 统一错误响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                      HttpServletRequest request) {
        // 只回参数名，不回传入的原始值 —— 原始值可能很长或含敏感内容
        String message = "参数 [" + ex.getName() + "] 类型不正确";
        log.warn("参数类型不匹配 path={} 参数={}", request.getRequestURI(), ex.getName());
        return build(ErrorCode.TYPE_MISMATCH, message, request);
    }

    /**
     * 缺少必填的查询参数。
     *
     * @param ex      缺参异常
     * @param request 当前请求
     * @return 统一错误响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex,
                                                          HttpServletRequest request) {
        String message = "缺少必要参数 [" + ex.getParameterName() + "]";
        log.warn("缺少参数 path={} 参数={}", request.getRequestURI(), ex.getParameterName());
        return build(ErrorCode.MISSING_PARAMETER, message, request);
    }

    /**
     * 请求体无法解析（JSON 语法错误、类型无法转换等）。
     *
     * @param ex      解析异常
     * @param request 当前请求
     * @return 统一错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException ex,
                                                     HttpServletRequest request) {
        // 刻意不返回 ex.getMessage()：它会带上目标类的完整类型名，属于内部结构泄漏
        log.warn("请求体解析失败 path={} 原因={}", request.getRequestURI(), ex.getClass().getSimpleName());
        return build(ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.defaultMessage(), request);
    }

    /**
     * HTTP 方法不支持。
     *
     * @param ex      方法不支持异常
     * @param request 当前请求
     * @return 统一错误响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                            HttpServletRequest request) {
        log.warn("方法不支持 path={} method={}", request.getRequestURI(), ex.getMethod());
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(), request);
    }

    /**
     * 请求体媒体类型不支持。
     *
     * @param ex      媒体类型异常
     * @param request 当前请求
     * @return 统一错误响应
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                               HttpServletRequest request) {
        log.warn("媒体类型不支持 path={} contentType={}", request.getRequestURI(), ex.getContentType());
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.defaultMessage(), request);
    }

    /**
     * 静态资源/路径未命中（Spring 6.1+ 对未知路径抛出的异常）。
     *
     * <p>没有这个处理器时，未知路径会落到 Spring 默认错误响应，破坏统一错误契约。
     *
     * @param ex      未找到异常
     * @param request 当前请求
     * @return 统一错误响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("路径未命中 path={}", request.getRequestURI());
        return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage(), request);
    }

    /**
     * 兜底：未预期的异常一律按 5xx 处理。
     *
     * <p><b>这是最后一道防线。</b>它保证两件事：响应形状依然统一；内部细节绝不外泄。
     * 排查依赖日志中的完整堆栈 + traceId，而不是把堆栈返回给调用方。
     *
     * @param ex      未预期异常
     * @param request 当前请求
     * @return 统一错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未预期异常 path={} traceId={}",
                request.getRequestURI(), TraceIdFilter.currentTraceId(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), request);
    }

    /**
     * 组装带字段明细的错误响应。
     *
     * @param errorCode 错误码
     * @param request   当前请求
     * @param details   字段明细
     * @return 统一错误响应
     */
    private static ResponseEntity<ApiError> build(ErrorCode errorCode,
                                                 HttpServletRequest request,
                                                 List<ApiError.FieldViolation> details) {
        ApiError body = ApiError.withDetails(errorCode, TraceIdFilter.currentTraceId(),
                request.getRequestURI(), details);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    /**
     * 组装不带字段明细的错误响应。
     *
     * @param errorCode 错误码
     * @param message   对外文案
     * @param request   当前请求
     * @return 统一错误响应
     */
    private static ResponseEntity<ApiError> build(ErrorCode errorCode, String message,
                                                 HttpServletRequest request) {
        ApiError body = ApiError.of(errorCode, message, TraceIdFilter.currentTraceId(), request.getRequestURI());
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    /**
     * 把 Bean Validation 的字段错误转为契约中的明细结构。
     *
     * @param fieldError 字段错误
     * @return 字段明细
     */
    private static ApiError.FieldViolation toFieldViolation(FieldError fieldError) {
        return new ApiError.FieldViolation(fieldError.getField(), fieldError.getDefaultMessage());
    }

    /**
     * 把约束违规转为契约中的明细结构。
     *
     * @param violation 约束违规
     * @return 字段明细
     */
    private static ApiError.FieldViolation toFieldViolation(ConstraintViolation<?> violation) {
        return new ApiError.FieldViolation(
                String.valueOf(violation.getPropertyPath()),
                violation.getMessage());
    }
}
