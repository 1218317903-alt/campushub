package ai.camphub.identity.api;

import ai.camphub.identity.domain.RefreshTokenRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 会话（登录设备）响应。
 *
 * <p>暴露自增 ID 供"下线这台设备"使用，这是有意的取舍：
 * 把它换成随机标识需要再加一列与一层映射，而该接口已做归属校验
 * （别人的会话一律 404），因此"猜 ID"既拿不到别人的数据，也得不到"是否存在"的反馈。
 *
 * @param id         会话 ID
 * @param device     设备标识。由客户端上报，仅作展示，不构成安全判据
 * @param createdAt  登录时间
 * @param expiresAt  该会话的失效时间
 * @param lastUsedAt 最近一次使用时间，可为空
 */
@Schema(description = "会话（登录设备）")
public record SessionResponse(
        @Schema(description = "会话 ID") long id,
        @Schema(description = "设备标识") String device,
        @Schema(description = "登录时间") Instant createdAt,
        @Schema(description = "失效时间") Instant expiresAt,
        @Schema(description = "最近使用时间") Instant lastUsedAt
) {

    /**
     * 由领域对象构造响应。
     *
     * @param record 刷新令牌记录
     * @return 响应
     */
    public static SessionResponse from(RefreshTokenRecord record) {
        return new SessionResponse(
                record.id(),
                record.device(),
                record.createdAt(),
                record.expiresAt(),
                record.lastUsedAt());
    }
}
