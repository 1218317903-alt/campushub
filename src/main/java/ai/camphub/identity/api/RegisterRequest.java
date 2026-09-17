package ai.camphub.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>校验分两层且职责不同：这里的 Bean Validation 只处理<b>形状</b>
 * （非空、长度、字符集），而"密码是否足够强"属于<b>业务规则</b>，
 * 由 {@code PasswordPolicy} 在应用服务层判定 —— 它需要访问弱密码表，
 * 不适合塞进注解。两层都必须有，任何一层都不能替代另一层。
 *
 * @param username 登录名。限定为字母数字与下划线/连字符：其中<b>禁止 {@code @}</b>
 *                 是功能性的 —— 登录接口靠"是否含 @ "区分用户名与邮箱，若不禁止就会产生歧义
 * @param email    邮箱
 * @param password 密码。这里只做长度下限的粗筛，真正的强度要求由密码策略判定
 * @param nickname 昵称，可为空（为空时取登录名）
 * @param device   设备标识，可为空。仅用于展示在"我的登录设备"列表中
 */
@Schema(description = "注册请求")
public record RegisterRequest(
        @Schema(description = "登录名，3~32 位字母数字下划线或连字符", example = "alice")
        @NotBlank(message = "登录名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$", message = "登录名只能包含字母、数字、下划线和连字符，长度 3~32 位")
        String username,

        @Schema(description = "邮箱", example = "alice@example.com")
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 254, message = "邮箱过长")
        String email,

        @Schema(description = "密码，至少 10 位，且不得是常见弱密码")
        @NotBlank(message = "密码不能为空")
        @Size(max = 200, message = "密码过长")
        String password,

        @Schema(description = "昵称，可为空", example = "Alice")
        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname,

        @Schema(description = "设备标识，可为空", example = "Chrome on macOS")
        @Size(max = 64, message = "设备标识最长 64 个字符")
        String device
) {
}
