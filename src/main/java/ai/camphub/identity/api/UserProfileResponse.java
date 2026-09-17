package ai.camphub.identity.api;

import ai.camphub.identity.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 用户资料响应。
 *
 * <p>注意这里<b>没有</b>邮箱与状态字段，也没有自增 ID：
 * <ul>
 *   <li>自增 ID 换成 {@code publicId}，避免把"系统里第几个用户"这类信息带出去；</li>
 *   <li>邮箱属于个人信息，只有本人（或后台合规流程）需要，默认不出现在通用资料响应里；</li>
 *   <li>状态字段对本人没有意义（能用就说明是 ACTIVE），对他人更不该暴露。</li>
 * </ul>
 * 需要哪些字段取决于<b>谁在看</b>，因此不同接口应当有不同的响应形态，
 * 而不是把一个"大而全"的用户对象在所有地方复用。
 *
 * @param publicId  对外标识
 * @param username  登录名
 * @param nickname  昵称
 * @param avatarUrl 头像地址，可为空
 * @param bio       简介，可为空
 * @param createdAt 注册时间
 */
@Schema(description = "用户资料")
public record UserProfileResponse(
        @Schema(description = "对外标识") String publicId,
        @Schema(description = "登录名") String username,
        @Schema(description = "昵称") String nickname,
        @Schema(description = "头像地址") String avatarUrl,
        @Schema(description = "个人简介") String bio,
        @Schema(description = "注册时间") Instant createdAt
) {

    /**
     * 由领域对象构造响应。
     *
     * @param user 用户
     * @return 响应
     */
    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.publicId(),
                user.username(),
                user.nickname(),
                user.avatarUrl(),
                user.bio(),
                user.createdAt());
    }
}
