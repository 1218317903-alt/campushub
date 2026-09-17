package ai.camphub.identity.infrastructure.security;

import ai.camphub.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 已认证但权限不足的处理：统一返回 403 + 我们的错误信封。
 *
 * <p>与 401 严格区分：401 表示"你还没证明你是谁"，403 表示"我们知道你是谁，
 * 但你不能做这件事"。这个区别决定了客户端该不该尝试刷新令牌 ——
 * 把 403 当成 401 处理会让前端陷入"反复刷新令牌然后仍然失败"的循环。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorResponseWriter responseWriter;

    /**
     * 构造注入。
     *
     * @param responseWriter 错误响应写出器
     */
    public RestAccessDeniedHandler(ApiErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        responseWriter.write(request, response, ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage());
    }
}
