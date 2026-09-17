package ai.camphub.identity.infrastructure.security;

import ai.camphub.common.error.ErrorCode;
import ai.camphub.identity.domain.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 访问令牌鉴权过滤器。
 *
 * <h2>校验链条（缺一不可）</h2>
 * <ol>
 *   <li><b>签名与时间</b>：由 {@code JwtDecoder} 完成，失败即判为无效。</li>
 *   <li><b>账号状态</b>：被停用或被锁定的账号，即使持有合法令牌也不得放行。</li>
 *   <li><b>令牌世代号</b>：与 {@code user.token_version} 比对 ——
 *       这一步才是"登出 / 改密 / 踢下线"能<b>立即</b>生效的原因。
 *       少了它，被登出的访问令牌仍会在剩余有效期内畅通无阻。</li>
 * </ol>
 *
 * <h2>令牌无效时为什么不直接返回 401</h2>
 * 本过滤器<b>不</b>直接写响应，而是把具体错误码放进请求属性后继续放行：
 * <ul>
 *   <li>受保护端点：后续的授权过滤器会拒绝该请求，未认证处理器读取该属性，
 *       于是客户端拿到的是"令牌已过期"而不是笼统的"未登录"——
 *       这两者对前端意味着不同动作（静默刷新 vs 要求重新登录）。</li>
 *   <li>公开端点（如 {@code /auth/login}）：请求正常执行。
 *       这一点很关键 —— 若过滤器直接拦下无效令牌，那么一个令牌已过期的客户端
 *       连"重新登录"这个接口都调不到，会陷入无法自救的状态。</li>
 * </ul>
 * 换句话说：<b>无效令牌只说明"这次请求不是已认证身份"，不说明"整个请求非法"</b>。
 *
 * <h2>关于安全上下文的清理</h2>
 * 本过滤器只负责写入，不负责清理 —— Spring Security 的
 * {@code SecurityContextHolderFilter} 会在每个请求结束时清空上下文。
 * 注意到这一点很重要：如果误以为"没人清理"而在这里再加一次，
 * 反而会掩盖真正需要关注的配置变化（比如将来有人把它换成了不清理的实现）。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final UserPrincipalLoader principalLoader;

    /**
     * 构造注入。
     *
     * @param jwtTokenService 令牌服务
     * @param principalLoader 主体装配器
     */
    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, UserPrincipalLoader principalLoader) {
        this.jwtTokenService = jwtTokenService;
        this.principalLoader = principalLoader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        if (token == null) {
            // 没带令牌不是错误：可能是公开端点，也可能由授权过滤器兜底。
            // 这里不写任何响应，交给后面的环节判断。
            filterChain.doFilter(request, response);
            return;
        }

        ErrorCode failure = resolveAndAuthenticate(request, token);
        if (failure != null) {
            request.setAttribute(RestAuthenticationEntryPoint.ATTRIBUTE_ERROR_CODE, failure);
            log.debug("访问令牌未通过校验 code={} uri={}", failure, request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 校验令牌并（成功时）写入安全上下文。
     *
     * @param request 当前请求
     * @param token   令牌字符串
     * @return 失败时返回对应错误码；成功时返回 null
     */
    private ErrorCode resolveAndAuthenticate(HttpServletRequest request, String token) {
        Jwt jwt;
        try {
            jwt = jwtTokenService.decode(token);
        } catch (JwtException e) {
            // 过期与其它失败分开：前者客户端可自动刷新，后者只能重新登录。
            // 具体原因属内部实现细节，只记日志，不回给调用方。
            return isExpired(e) ? ErrorCode.TOKEN_EXPIRED : ErrorCode.TOKEN_INVALID;
        } catch (IllegalArgumentException e) {
            // 令牌格式本身不合法（如空串、非法 base64）
            return ErrorCode.TOKEN_INVALID;
        }

        long userId = readLongClaim(jwt, JwtTokenService.CLAIM_UID);
        int tokenVersion = (int) readLongClaim(jwt, JwtTokenService.CLAIM_TOKEN_VERSION);

        Optional<UserPrincipal> loaded = principalLoader.load(userId);
        if (loaded.isEmpty()) {
            // 令牌签名有效但账号已不存在（已注销或已软删除）：与令牌失效同义
            return ErrorCode.TOKEN_REVOKED;
        }

        UserPrincipal principal = loaded.get();
        if (!principal.status().isActive()) {
            return ErrorCode.ACCOUNT_NOT_USABLE;
        }
        if (principal.tokenVersion() != tokenVersion) {
            // 世代号不一致 = 该令牌已被"登出全部设备"或"修改密码"作废
            return ErrorCode.TOKEN_REVOKED;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, UserPrincipalLoader.toAuthorities(principal));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return null;
    }

    /**
     * 取 {@code Authorization: Bearer <token>} 中的令牌。
     *
     * <p>大小写不敏感地匹配 {@code Bearer}：按 RFC 7235，认证方案名是大小写不敏感的，
     * 严格区分会让部分客户端莫名无法鉴权，而攻击者并不会因此被挡住。
     *
     * @param request 当前请求
     * @return 令牌字符串；不存在时返回 null
     */
    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 读取数值型声明。
     *
     * <p>JSON 数字反序列化后可能是 Integer 也可能是 Long（取决于数值大小），
     * 因此统一按 {@link Number} 取值再转换，避免 ClassCastException ——
     * 那类错误只在特定数值下出现，是最难在测试中稳定复现的一种。
     *
     * @param jwt  令牌
     * @param name 声明名
     * @return 数值；声明缺失或类型不符时返回 0
     */
    private static long readLongClaim(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    /**
     * 判断解码失败是否由过期引起。
     *
     * <p>不依赖具体异常类型名（会随依赖库版本变化），而是看消息中是否包含
     * {@code Jwt expired} —— Nimbus 在过期路径上固定携带该短语。
     * 判断失败时按"无效"处理：宁可让客户端重新登录一次，
     * 也不要错误地引导它反复刷新一个已经无法刷新的会话。
     *
     * @param e 解码异常
     * @return 由过期引起时返回 true
     */
    private static boolean isExpired(JwtException e) {
        String message = e.getMessage();
        return message != null && message.contains("Jwt expired");
    }
}
