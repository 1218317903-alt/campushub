package ai.camphub.identity.infrastructure.security;

import ai.camphub.identity.config.SecurityProperties;
import ai.camphub.identity.domain.PasswordPolicy;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * 安全主配置。
 *
 * <h2>本类要回答的核心问题</h2>
 * Boot 引入 spring-security 后会自动锁住全部接口（实测：Phase 01 的
 * {@code /api/v1/system/info} 从 200 变成 401）。这份配置把"哪些接口公开、
 * 哪些需要登录、失败时返回什么"重新明确下来 —— 而不是靠默认值碰运气。
 *
 * <h2>公开端点清单（每一项都需要理由）</h2>
 * <ul>
 *   <li>{@code /api/v1/auth/register|login|refresh}：登录前必须能访问，否则无法登录。</li>
 *   <li>{@code /api/v1/system/info}：只暴露版本号与 schema 基线，Phase 01 已定义其公开契约。</li>
 *   <li>{@code /actuator/health|info}：健康检查供编排系统与负载均衡探活，不能要求凭据。</li>
 *   <li>{@code /v3/api-docs|/swagger-ui}：本地开发需要。**生产环境不靠这里的规则隐藏它**，
 *       而是把 {@code springdoc.api-docs.enabled} 设为 false —— 端点直接 404 比"返回 401"
 *       更彻底，因为 401 本身仍承认了这个端点的存在。</li>
 *   <li>{@code /error}：容器内部的错误转发路径，拦住它会让所有错误变成空白响应。</li>
 * </ul>
 * 其余一律 {@code authenticated()}。默认拒绝而非默认放行，是这份清单能长期安全的前提 ——
 * 新增接口时忘记加规则，结果是"访问不了"（立刻被发现），而不是"裸奔"（很久后才发现）。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * BCrypt 代价因子。
     *
     * <p>取 12 的依据：它是当前硬件上"对登录体验无感、对离线爆破足够昂贵"的平衡点
     * （约 200~300ms/次）。取 10 会让攻击者快 4 倍，取 14 则开始让登录接口在压测中显形。
     * 这个数字应随硬件演进而上调，而不是一劳永逸。
     */
    private static final int BCRYPT_STRENGTH = 12;

    /** HS256 的密钥长度下限（256 bit）。 */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 认证方式：无状态 Bearer 令牌。
     *
     * <p>关掉表单登录、HTTP Basic、默认登出与 CSRF，理由如下：
     * <ul>
     *   <li><b>CSRF 可以安全关闭</b>：本服务的凭据只经 {@code Authorization} 头传递，
     *       不依赖浏览器自动携带的 Cookie。CSRF 攻击的前提是"浏览器会自动带上凭据"，
     *       这个前提不存在时，CSRF 防护没有防护对象。
     *       <b>若将来把刷新令牌改放进 Cookie，这一条必须同步改回来。</b></li>
     *   <li>{@code httpBasic}/{@code formLogin}：它们会引入一套与 JWT 并行的认证入口，
     *       以及一个默认生成的用户。多一条认证路径就多一个需要审计的入口。</li>
     *   <li>{@code logout}：登出业务在 {@code /api/v1/auth/logout} 中实现
     *       （需要撤销刷新令牌、递增世代号），Spring 默认的登出只清会话，语义不符。</li>
     * </ul>
     *
     * <p><b>CORS 不做配置</b>：开发期由 Vite 的 dev proxy 转发（同源），
     * 生产期由 Nginx 反代（同源）。不配 CORS 意味着跨源浏览器请求默认被拒 ——
     * 这正是当前的期望行为，比先放开、以后再收紧安全得多。
     *
     * @param http                 安全构建器
     * @param jwtAuthenticationFilter 访问令牌过滤器
     * @param entryPoint           未认证处理器
     * @param accessDeniedHandler  权限不足处理器
     * @return 过滤链
     * @throws Exception 构建失败
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtAuthenticationFilter,
                                            RestAuthenticationEntryPoint entryPoint,
                                            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/info").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        // 本服务不承载任何需要被 iframe 嵌入的内容
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // CSP 对纯 JSON 响应没有实际约束力，前端页面的 CSP 应由承载它的
                        // Nginx / 静态服务器下发。这里配置它的意义在于覆盖错误页等
                        // 可能返回 HTML 的路径，避免出现"完全没有 CSP"的空档。
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; frame-ancestors 'none'; object-src 'none'")));
        return http.build();
    }

    /**
     * 时钟。注入而非各处调用 {@code Instant.now()}，是为了让"令牌过期""锁定到期"
     * 这类与时间强相关的逻辑可以在测试中被精确控制。
     *
     * <p>刻意不使用系统默认时区：JWT 的 {@code exp} 是绝对时间（epoch 秒），
     * 与本地时区无关；用 UTC 可以避免"开发机在 +08、CI 在 UTC"导致的行为差异。
     *
     * @return UTC 时钟
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 密码编码器。
     *
     * <p>BCrypt 自带随机 salt 并把它写在哈希串里，因此不需要额外的 salt 字段；
     * 校验时从哈希串中读取即可。
     *
     * @return 编码器
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    /**
     * 密码策略。常用弱密码表在启动时一次性载入内存。
     *
     * @param securityProperties 安全配置
     * @return 策略
     */
    @Bean
    PasswordPolicy passwordPolicy(SecurityProperties securityProperties) {
        return new PasswordPolicy(
                securityProperties.password().minLength(),
                CommonPasswordList.load());
    }

    /**
     * JWT 编码器。
     *
     * @param securityProperties 安全配置
     * @return 编码器
     */
    @Bean
    JwtEncoder jwtEncoder(SecurityProperties securityProperties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(hmacKey(securityProperties)));
    }

    /**
     * JWT 解码器与校验器链。
     *
     * <p>校验项：签名、{@code exp}/{@code nbf}、{@code iss}，外加两项声明存在性检查。
     * 最后一项容易被忽略但很关键：若令牌里根本没有 {@code ver} 声明，
     * 过滤器读到的世代号会是 0，而任何真实用户的世代号都 ≥ 1 ——
     * 结果是"缺声明的令牌"反而会被当成"世代号不匹配"拒掉，
     * 表面上没出问题，实际上让校验逻辑的意图变得含糊。显式拒绝更清楚。
     *
     * @param securityProperties 安全配置
     * @return 解码器
     */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties securityProperties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(hmacKey(securityProperties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> requiredClaimsPresence = jwt -> {
            List<OAuth2Error> errors = new ArrayList<>();
            if (jwt.getClaim(JwtTokenService.CLAIM_UID) == null) {
                errors.add(new OAuth2Error("invalid_token", "访问令牌缺少 uid 声明", null));
            }
            if (jwt.getClaim(JwtTokenService.CLAIM_TOKEN_VERSION) == null) {
                errors.add(new OAuth2Error("invalid_token", "访问令牌缺少 ver 声明", null));
            }
            return errors.isEmpty()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(errors);
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(securityProperties.jwt().issuer()),
                requiredClaimsPresence));
        return decoder;
    }

    /**
     * 从配置中取出并校验签名密钥。
     *
     * @param securityProperties 安全配置
     * @return HMAC 密钥
     * @throws IllegalStateException 未配置或长度不足时（启动即失败）
     */
    private static SecretKey hmacKey(SecurityProperties securityProperties) {
        String secret = securityProperties.jwt().secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                    未配置 JWT 签名密钥（app.security.jwt.secret / 环境变量 APP_JWT_SECRET）。
                    生成方式：openssl rand -base64 48
                    此处刻意不提供默认值 —— 一个"能启动但人人可猜"的默认密钥，
                    等于把所有用户的账号交给第一个读到源码的人。""");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 签名密钥过短：HS256 要求至少 " + MIN_SECRET_BYTES + " 字节（256 bit），当前 "
                            + keyBytes.length + " 字节。请用 openssl rand -base64 48 重新生成。");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}
