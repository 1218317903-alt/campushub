package ai.camphub.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 登出请求。
 *
 * <p>登出需要带上刷新令牌：访问令牌是无状态的，服务端无法凭它定位"要撤销哪一个会话"。
 * 只让访问令牌失效是不够的 —— 那只是让客户端"看起来"登出了，
 * 而真正能换出新会话的刷新令牌仍然有效。
 *
 * @param refreshToken 当前会话的刷新令牌
 */
@Schema(description = "登出请求")
public record LogoutRequest(
        @Schema(description = "当前会话的刷新令牌")
        @NotBlank(message = "刷新令牌不能为空")
        String refreshToken
) {
}
