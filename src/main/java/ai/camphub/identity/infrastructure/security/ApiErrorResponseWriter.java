package ai.camphub.identity.infrastructure.security;

import ai.camphub.common.error.ApiError;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 在 Servlet 过滤器层直接写出统一错误响应。
 *
 * <h2>为什么需要它</h2>
 * 鉴权发生在 {@code DispatcherServlet} <b>之前</b>，因此
 * {@link ai.camphub.common.error.GlobalExceptionHandler} 根本不会被调用。
 * 如果这里不管，Spring Security 默认会返回一个空 body 的 401 ——
 * 前端就得为"认证失败"单独写一套解析逻辑，而这正是统一错误契约要消除的东西。
 *
 * <p>本类的存在，就是为了让"未登录"与"业务校验失败"在客户端看来形状完全一致，
 * 只有 {@code code} 不同。
 *
 * <p><b>关于 Jackson 版本</b>：这里注入的是 {@code tools.jackson.databind.ObjectMapper}
 * （Jackson 3），不是 {@code com.fasterxml.jackson}（Jackson 2）。
 * Spring Boot 4 / Spring Framework 7 已整体迁移到 Jackson 3，
 * 容器里自动配置的只有 Jackson 3 的 {@code ObjectMapper} ——
 * 若沿用 Jackson 2 的导入，编译能过（springdoc 仍在传递 Jackson 2），
 * 但运行时找不到 Bean，且报错信息只提示"没有 ObjectMapper"，很容易误判方向。
 */
@Component
public class ApiErrorResponseWriter {

    private final ObjectMapper objectMapper;

    /**
     * 构造注入。
     *
     * @param objectMapper JSON 序列化器
     */
    public ApiErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写出错误响应。
     *
     * @param request    当前请求（用于填充 path）
     * @param response   响应
     * @param errorCode  错误码
     * @param message    对外文案
     * @throws IOException 写响应失败
     */
    public void write(HttpServletRequest request,
                      HttpServletResponse response,
                      ErrorCode errorCode,
                      String message) throws IOException {
        // 响应一旦开始提交就不能再改状态码或头，先判断避免"半截响应"
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ApiError body = ApiError.of(errorCode, message, TraceIdFilter.currentTraceId(), request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
