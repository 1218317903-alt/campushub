package ai.camphub.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求。
 *
 * @param currentPassword 当前密码。要求出示它，是为了让"一次被窃取的访问令牌"
 *                        不足以永久接管账号 —— 攻击者必须同时知道原密码才能改密
 * @param newPassword     新密码
 * @param device          设备标识，用于为当前设备重新建立会话（其它设备会被下线）
 */
@Schema(description = "修改密码请求")
public record ChangePasswordRequest(
        @Schema(description = "当前密码")
        @NotBlank(message = "当前密码不能为空")
        @Size(max = 200, message = "当前密码过长")
        String currentPassword,

        @Schema(description = "新密码，至少 10 位，且不得是常见弱密码")
        @NotBlank(message = "新密码不能为空")
        @Size(max = 200, message = "新密码过长")
        String newPassword,

        @Schema(description = "设备标识，可为空")
        @Size(max = 64, message = "设备标识最长 64 个字符")
        String device
) {
}
