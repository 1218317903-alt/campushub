package ai.camphub.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改个人资料请求。
 *
 * <p>{@code avatarUrl} 用正则限定为 {@code http/https}：允许任意字符串的话，
 * 一个 {@code javascript:} 开头的头像地址就可能在展示端变成脚本执行入口。
 * 服务端不该假设前端一定会做校验。
 *
 * @param nickname  昵称
 * @param avatarUrl 头像地址，可为空（表示清空）
 * @param bio       个人简介，可为空
 */
@Schema(description = "修改个人资料请求")
public record UpdateProfileRequest(
        @Schema(description = "昵称", example = "Alice")
        @NotBlank(message = "昵称不能为空")
        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname,

        @Schema(description = "头像地址，仅支持 http/https，可为空", example = "https://example.com/a.png")
        @Size(max = 512, message = "头像地址最长 512 个字符")
        @Pattern(regexp = "^https?://\\S+$", message = "头像地址必须是以 http:// 或 https:// 开头的链接")
        String avatarUrl,

        @Schema(description = "个人简介，可为空")
        @Size(max = 200, message = "个人简介最长 200 个字符")
        String bio
) {
}
