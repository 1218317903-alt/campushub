package ai.camphub.platform.audit.app;

import ai.camphub.common.config.RequestProperties;
import ai.camphub.common.web.ClientIpResolver;
import ai.camphub.common.web.TraceIdFilter;
import ai.camphub.platform.audit.domain.AuditAction;
import ai.camphub.platform.audit.domain.AuditEntry;
import ai.camphub.platform.audit.domain.AuditResult;
import ai.camphub.platform.audit.infrastructure.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 安全审计的写入入口。
 *
 * <h2>关键设计：为什么用 {@code REQUIRES_NEW} 独立事务</h2>
 * 绝大多数需要审计的失败路径，最终都会<b>抛异常</b>结束（登录失败抛 401、
 * 密码不合规抛 400）。如果审计写入与业务处于同一事务，业务一抛异常、
 * 事务一回滚，那条"失败记录"就一起没了 —— 而那恰恰是审计里最有价值的一类记录。
 *
 * <p>代价也要说清楚：独立事务意味着"审计已提交、业务随后才回滚"在理论上可能发生
 * （记录了一次最终未生效的尝试）。我们接受这个代价，因为：
 * ① 审计记录的是"谁试图做了什么"，尝试本身即事实；
 * ② 反向的代价（丢失全部失败记录）对安全是不可接受的。
 *
 * <p>另外，审计插入只写 {@code audit_log} 一张表、不与之竞争其它行的锁，
 * 因此这里额外占用一个数据库连接的代价很低。
 *
 * <h2>调用点的义务</h2>
 * 本类不判断"这个操作值不值得审计" —— 由调用方决定。因此新增敏感操作时，
 * 除了写业务代码，还必须补一次 {@code record(...)}；这是需要评审时专门检查的一项。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 与 {@code audit_log.user_agent} 的列宽一致。 */
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final boolean trustForwardedHeaders;

    /**
     * 构造注入。
     *
     * @param auditLogMapper        审计写入 Mapper
     * @param objectMapper          JSON 序列化器
     * @param requestProperties     HTTP 层配置（决定是否信任代理头）
     */
    public AuditService(AuditLogMapper auditLogMapper,
                        ObjectMapper objectMapper,
                        RequestProperties requestProperties) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
        this.trustForwardedHeaders = requestProperties.trustForwardedHeaders();
    }

    /**
     * 记录一条审计（无补充信息）。
     *
     * @param action      动作码
     * @param result      结果
     * @param actorUserId 操作者 ID；未认证时为 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, AuditResult result, Long actorUserId) {
        record(action, result, actorUserId, null, null, null);
    }

    /**
     * 记录一条审计。
     *
     * @param action      动作码
     * @param result      结果
     * @param actorUserId 操作者 ID；未认证时为 null
     * @param targetType  操作对象类型，可为 null
     * @param targetId    操作对象标识，可为 null
     * @param detail      结构化补充信息，可为 null。
     *                    <b>不得包含密码、令牌原文、密钥</b>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action,
                       AuditResult result,
                       Long actorUserId,
                       String targetType,
                       String targetId,
                       Map<String, Object> detail) {
        HttpServletRequest request = currentRequest();
        AuditEntry entry = new AuditEntry(
                actorUserId,
                action.name(),
                targetType,
                targetId,
                result.name(),
                ClientIpResolver.resolve(request, trustForwardedHeaders),
                userAgentOf(request),
                toJson(detail),
                TraceIdFilter.currentTraceId());
        auditLogMapper.insert(entry);

        // 登录失败是安全事件里最需要被"看见"的一类，因此单独打一条 warn 级日志，
        // 让它在默认日志级别下就能被运维发现，而不必去翻数据库
        if (result == AuditResult.FAILURE) {
            log.warn("审计·失败动作 action={} targetType={} targetId={} actor={} ip={}",
                    action.name(), targetType, targetId, actorUserId, entry.ip());
        }
    }

    /**
     * 取当前请求。非 Web 上下文（如定时任务）时返回 null。
     *
     * @return 当前请求或 null
     */
    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /**
     * 取客户端 UA 并截断到列宽。
     *
     * @param request 当前请求，可为 null
     * @return UA 或 null
     */
    private static String userAgentOf(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= MAX_USER_AGENT_LENGTH
                ? userAgent
                : userAgent.substring(0, MAX_USER_AGENT_LENGTH);
    }

    /**
     * 把补充信息序列化为 JSON。
     *
     * <p>序列化失败时不让异常中断业务（补充信息是辅助性的，不该有否决权），
     * 但也不静默写成 null —— 那会让这条记录看起来像"本来就没有补充信息"。
     * 改为写入一个显式的错误标记，排查时才分得清"没有"和"丢了"。
     *
     * @param detail 补充信息，可为 null
     * @return JSON 文本；detail 为 null 时返回 null
     */
    private String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JacksonException e) {
            log.error("审计补充信息序列化失败，已以错误标记落库。字段={}", detail.keySet(), e);
            return "{\"_error\":\"detail serialization failed\"}";
        }
    }
}
