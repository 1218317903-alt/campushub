package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 成员行（数据层读模型）。
 *
 * <p>与 {@link WorkspaceMemberView} 的区别在于用途：这个类型承载 <b>user 自增主键</b>，
 * 供模块内部使用（判定角色、移除成员）；{@link WorkspaceMemberView} 承载的是
 * 可以直接给前端看的展示字段。两者不合并，是为了让"用户主键绝不出现在响应里"
 * 这件事由类型来保证，而不是靠组装响应时记得别带出去。
 *
 * @param userId   用户自增主键
 * @param role     成员角色（不含 OWNER，见 V5 的偏离说明）
 * @param joinedAt 加入时间
 */
public record WorkspaceMember(
        long userId,
        WorkspaceMemberRole role,
        Instant joinedAt
) {
}
