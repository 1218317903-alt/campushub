package ai.camphub.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 刷新令牌请求。
 *
 * @param refreshToken 登录或上次刷新时返回的刷新令牌原文
 * @param device       设备标识，可为空
 */
@Schema(description = "刷新令牌请求")
public record RefreshRequest(
        @Schema(description = "刷新令牌")
        @NotBlank(message = "刷新令牌不能为空")
        String refreshToken,

        @Schema(description = "设备标识，可为空")
        @Size(max = 64, message = "设备标识最长 64 个字符")
        String device
) {
}
