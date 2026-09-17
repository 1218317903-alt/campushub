package ai.camphub.identity.infrastructure.security;

import ai.camphub.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 未认证访问的处理：统一返回 401 + 我们的错误信封。
 *
 * <p>默认实现（{@code Http403ForbiddenEntryPoint} 或空 body 的 401）会破坏统一错误契约，
 * 因此必须替换。
 *
 * <p>具体错误码由过滤器通过请求属性 {@link #ATTRIBUTE_ERROR_CODE} 传入：
 * "没带令牌"、"令牌过期"、"令牌无效"、"令牌已撤销"对客户端意味着不同的动作
 * （登录 / 刷新 / 重新登录），在错误码上区分开是有实际价值的，
 * 因此不需要为每种情形各写一个 EntryPoint —— 状态码由错误码自身决定。
 * 注意绝大多数情况是 401，但"账号不可用"会是 403：
 * 那种情况下令牌本身没问题，能识别出身份，只是该身份不该被放行。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** 请求属性名：过滤器用它传递具体错误码。 */
    public static final String ATTRIBUTE_ERROR_CODE = "camphub.security.errorCode";

    private final ApiErrorResponseWriter responseWriter;

    /**
     * 构造注入。
     *
     * @param responseWriter 错误响应写出器
     */
    public RestAuthenticationEntryPoint(ApiErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ErrorCode errorCode = resolveErrorCode(request);
        // 按 RFC 6750 告知调用方凭据类型与失败性质。
        // 让"是不是令牌问题"在传输层就可判断，客户端无需先解析 body。
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"camphub\", error=\"invalid_token\"");
        responseWriter.write(request, response, errorCode, errorCode.defaultMessage());
    }

    /**
     * 取出过滤器设置的具体错误码，缺省为"未提供凭据"。
     *
     * @param request 当前请求
     * @return 错误码
     */
    private static ErrorCode resolveErrorCode(HttpServletRequest request) {
        Object attribute = request.getAttribute(ATTRIBUTE_ERROR_CODE);
        if (attribute instanceof ErrorCode errorCode) {
            return errorCode;
        }
        return ErrorCode.UNAUTHENTICATED;
    }
}
