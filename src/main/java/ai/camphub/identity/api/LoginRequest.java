package ai.camphub.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 *
 * @param identifier 用户名或邮箱
 * @param password   密码
 * @param device     设备标识，可为空
 */
@Schema(description = "登录请求")
public record LoginRequest(
        @Schema(description = "用户名或邮箱", example = "alice")
        @NotBlank(message = "用户名或邮箱不能为空")
        @Size(max = 254, message = "用户名或邮箱过长")
        String identifier,

        @Schema(description = "密码")
        @NotBlank(message = "密码不能为空")
        @Size(max = 200, message = "密码过长")
        String password,

        @Schema(description = "设备标识，可为空", example = "Chrome on macOS")
        @Size(max = 64, message = "设备标识最长 64 个字符")
        String device
) {
}
