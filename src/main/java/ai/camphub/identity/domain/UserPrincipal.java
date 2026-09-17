package ai.camphub.identity.domain;

import java.util.Set;

/**
 * 已认证主体：一次请求中"当前是谁"的完整快照。
 *
 * <p>它是鉴权过滤器校验访问令牌之后放进安全上下文的<b>唯一</b>身份来源。
 * 业务代码一律通过它取当前用户，不允许自己去解析令牌或信任请求头 ——
 * 那样会绕开"令牌世代号比对"这一步，使登出与踢下线失效。
 *
 * <p><b>为什么角色与权限放在这里，而不是从令牌声明里读</b>：
 * 令牌一旦签发就无法收回，若把权限写进令牌，撤销一个管理员的权限就必须等他
 * 令牌自然过期（或把整批令牌作废，误伤其他能力）。改为每次请求从库里读，
 * 权限变更立即生效。代价是每个已认证请求多一次数据库查询 ——
 * 这是一笔<b>明确知情的成本</b>，Phase 09 会用实测数据决定是否加一层短 TTL 缓存，
 * 而不是现在就凭直觉上缓存。
 *
 * @param userId       自增主键（内部使用，不对外暴露）
 * @param publicId     对外标识
 * @param username     登录名
 * @param nickname     展示名
 * @param status       账号状态
 * @param tokenVersion 令牌世代号，鉴权时与令牌里的 {@code ver} 声明比对
 * @param roles        角色码集合，如 {@code ["USER"]}
 * @param permissions  权限码集合，如 {@code ["post:create"]}
 */
public record UserPrincipal(
        long userId,
        String publicId,
        String username,
        String nickname,
        UserStatus status,
        int tokenVersion,
        Set<String> roles,
        Set<String> permissions
) {

    /**
     * 规范化构造：把集合复制成不可变集合，避免调用方在主体创建后继续改动它。
     *
     * @param userId       自增主键
     * @param publicId     对外标识
     * @param username     登录名
     * @param nickname     展示名
     * @param status       账号状态
     * @param tokenVersion 令牌世代号
     * @param roles        角色码集合
     * @param permissions  权限码集合
     */
    public UserPrincipal {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }

    /**
     * 判断是否拥有某个权限点。
     *
     * <p>供方法级鉴权（{@code @PreAuthorize}）与业务分支使用。
     * 注意它只回答"这个身份有没有这个权限"，<b>不回答</b>"这个身份能不能访问这条具体数据"——
     * 后者属于资源级鉴权，必须另行校验对象归属（见 docs/03-domain-permission.md §10.3）。
     *
     * @param permissionCode 权限码
     * @return 拥有时返回 true
     */
    public boolean hasPermission(String permissionCode) {
        return permissions.contains(permissionCode);
    }

    /**
     * 判断是否拥有某个角色。
     *
     * @param roleCode 角色码
     * @return 拥有时返回 true
     */
    public boolean hasRole(String roleCode) {
        return roles.contains(roleCode);
    }
}
