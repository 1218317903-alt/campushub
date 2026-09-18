package ai.camphub.workspace.infrastructure.scope;

import ai.camphub.workspace.app.WorkspaceScopeContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求结束时清理 {@link WorkspaceScopeContext}。
 *
 * <h2>为什么这是一个必须存在的组件</h2>
 * 授权范围被放在 ThreadLocal 里，而 Tomcat 的请求线程来自线程池、会被后续请求复用。
 * 没有这道清理，一个请求算出的授权范围会留给下一个恰好落到同一线程的请求 ——
 * 症状是"偶发地看到别人的空间"，只在并发下出现，本机手工点页面永远复现不了。
 * 这类问题的排查成本远高于"多写一个过滤器"。
 *
 * <p>用 {@code finally} 而不是在控制器返回后清理：异常路径同样会复用线程，
 * 而异常恰恰是最容易被漏掉的那条路径。
 *
 * <h2>为什么 order 要排在最前</h2>
 * 过滤器链是嵌套的，{@code finally} 的执行顺序与外层相反。
 * 排在越前面，它的 {@code finally} 就越晚执行 —— 从而保证整条链路
 * （含 Spring Security 的过滤器、含异常处理）都在清理之前完成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class WorkspaceScopeResetFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            WorkspaceScopeContext.clear();
        }
    }
}
