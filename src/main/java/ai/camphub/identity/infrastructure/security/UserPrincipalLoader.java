package ai.camphub.identity.infrastructure.security;

import ai.camphub.identity.domain.User;
import ai.camphub.identity.domain.UserPrincipal;
import ai.camphub.identity.infrastructure.UserMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * 按用户 ID 装配鉴权主体（含角色与权限）。
 *
 * <h2>为什么每次请求都回库读</h2>
 * 这是本项目在"令牌无状态"与"权限可即时撤销"之间的一次明确取舍。
 * 把角色/权限放进令牌能省掉这几次查询，代价是权限变更要等令牌过期才生效 ——
 * 对于一个会有 MODERATOR / ADMIN 角色的系统，这个延迟不可接受。
 *
 * <p>当前的实现是"账号 1 次查询 + 角色 1 次查询 + 权限 1 次查询"。
 * 三次都是主键/覆盖索引上的小查询，但确实是每个已认证请求的固定开销。
 * <b>刻意不在此处预先加缓存</b>：Phase 09 会先压测拿到真实数字，
 * 再决定是否需要缓存以及缓存的 TTL —— 没有测量就加缓存，
 * 只是把"慢"换成了"偶发不一致"，而且问题会被推迟到更难排查的时候出现。
 *
 * <h2>角色 → 权限的映射在 SQL 里完成</h2>
 * 由 {@code user_role → role_permission → permission} 三表连接得出，
 * 而不是在 Java 里先查角色、再按角色查权限。少一次往返，也避免"角色很多时 N+1"。
 */
@Component
public class UserPrincipalLoader {

    /** Spring Security 的约定：角色权限以 {@code ROLE_} 前缀区分于普通权限。 */
    private static final String ROLE_PREFIX = "ROLE_";

    private final UserMapper userMapper;

    /**
     * 构造注入。
     *
     * @param userMapper 用户 Mapper
     */
    public UserPrincipalLoader(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 装配主体。
     *
     * @param userId 用户内部 ID
     * @return 用户不存在（或已软删除）时返回空
     */
    public Optional<UserPrincipal> load(long userId) {
        Optional<User> found = userMapper.findById(userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        User user = found.get();
        Set<String> roles = Set.copyOf(userMapper.selectRoleCodesByUserId(userId));
        Set<String> permissions = Set.copyOf(userMapper.selectPermissionCodesByUserId(userId));
        return Optional.of(new UserPrincipal(
                user.id(),
                user.publicId(),
                user.username(),
                user.nickname(),
                user.status(),
                user.tokenVersion(),
                roles,
                permissions));
    }

    /**
     * 把角色与权限码转换为 Spring Security 的权限集合。
     *
     * <p>角色加 {@code ROLE_} 前缀（使 {@code hasRole('ADMIN')} 可用），
     * 权限码原样保留（供 {@code hasAuthority('post:create')} 使用）。
     *
     * @param principal 鉴权主体
     * @return 权限集合
     */
    public static List<GrantedAuthority> toAuthorities(UserPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>(principal.roles().size() + principal.permissions().size());
        for (String role : principal.roles()) {
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
        }
        for (String permission : principal.permissions()) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        return authorities;
    }
}
