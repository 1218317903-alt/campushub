package ai.camphub.identity.api;

import ai.camphub.identity.app.IssuedTokens;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 令牌对响应。
 *
 * <p><b>刷新令牌只在这里出现一次。</b>服务端只保存它的哈希，因此无法再次返回；
 * 客户端若丢失，只能重新登录。这个限制是刻意的 ——
 * 任何"服务端还能把刷新令牌取出来给你"的机制，都意味着明文凭据被持久化在某个地方。
 *
 * @param accessToken  访问令牌，放在 {@code Authorization: Bearer} 头中使用
 * @param refreshToken 刷新令牌，仅本次响应返回
 * @param tokenType    令牌类型，固定 {@code Bearer}
 * @param expiresIn    访问令牌有效期（秒），客户端据此提前刷新
 *                     （建议在剩余 1~2 分钟时刷新，避免请求正好压在过期边界上）
 */
@Schema(description = "令牌对")
public record IssuedTokensResponse(
        @Schema(description = "访问令牌") String accessToken,
        @Schema(description = "刷新令牌（仅本次返回）") String refreshToken,
        @Schema(description = "令牌类型", example = "Bearer") String tokenType,
        @Schema(description = "访问令牌有效期（秒）", example = "900") long expiresIn
) {

    /**
     * 由应用层结果构造响应。
     *
     * @param tokens 令牌对
     * @return 响应
     */
    public static IssuedTokensResponse from(IssuedTokens tokens) {
        return new IssuedTokensResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer",
                tokens.expiresInSeconds());
    }
}
