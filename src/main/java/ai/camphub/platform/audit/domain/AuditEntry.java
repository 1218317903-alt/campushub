package ai.camphub.platform.audit.domain;

/**
 * 一条待写入的审计记录。
 *
 * <p>{@code ip} / {@code userAgent} / {@code traceId} 由 {@code AuditService} 从当前请求
 * 自动补齐，调用方只需提供安全语义上有意义的字段 —— 这样就不会出现
 * "某个调用点忘了记 IP"这类不一致。
 *
 * <p><b>写入纪律</b>：{@code detailJson} 中<b>不得出现密码、令牌原文、密钥</b>。
 * 审计日志常常是排查问题时被导出、被转发的一份数据，把凭据写进去等于把它们
 * 从"只在内存与哈希里存在"变成"躺在另一个表里"。需要标记某个令牌时，记它的哈希前缀即可。
 *
 * @param actorUserId 操作者；未认证动作（如登录失败）为 null
 * @param action      动作码
 * @param targetType  操作对象类型，可为 null
 * @param targetId    操作对象标识（存 public_id，不存自增 id）
 * @param result      结果
 * @param ip          来源 IP
 * @param userAgent   客户端 UA
 * @param detailJson  结构化补充信息（JSON 文本），可为 null
 * @param traceId     请求链路 ID
 */
public record AuditEntry(
        Long actorUserId,
        String action,
        String targetType,
        String targetId,
        String result,
        String ip,
        String userAgent,
        String detailJson,
        String traceId
) {
}
