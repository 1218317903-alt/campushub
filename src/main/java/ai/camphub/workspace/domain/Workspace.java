package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 空间（读模型）。
 *
 * <h2>为什么没有 memberCount / noteCount</h2>
 * 见 {@code V5__workspace.sql} 的偏离说明第 4 条：这两个数字没有必须依赖计数列的读路径，
 * 而计数列会引入"并发丢更新"这一已经在 post 表上小心处理过的故障模式。
 * 需要时 COUNT 一次即可。
 *
 * @param id         自增主键。仅模块内部使用，<b>不对外暴露</b>
 * @param publicId   对外标识，客户端只能看见它
 * @param name       空间名称
 * @param description 空间描述，可为 null
 * @param visibility 可见性
 * @param ownerId    拥有者自增主键。第二层防线判定 OWNER 身份的依据
 * @param createdAt  创建时间
 * @param updatedAt  最后修改时间
 */
public record Workspace(
        long id,
        String publicId,
        String name,
        String description,
        WorkspaceVisibility visibility,
        long ownerId,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 判断某个用户是不是这个空间的拥有者。
     *
     * @param userId 用户自增主键
     * @return 是否为拥有者
     */
    public boolean isOwnedBy(long userId) {
        return ownerId == userId;
    }
}
