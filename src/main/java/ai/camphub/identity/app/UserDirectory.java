package ai.camphub.identity.app;

import ai.camphub.identity.domain.UserBrief;
import ai.camphub.identity.infrastructure.UserMapper;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户展示信息目录：identity 模块对外提供的<b>只读</b>接口。
 *
 * <h2>它解决的是"模块化"里最容易被绕过的那条线</h2>
 * community 需要显示帖子作者与评论者的昵称。最省事的做法是让 community 直接
 * {@code JOIN user} 或注入 {@code UserMapper} —— 那会立刻违反"模块间禁止跨模块访问数据表"
 * （docs/00-工程规约.md 架构强制约定 1），后果不是抽象的：user 表加一列、改一次索引，
 * 都要去检查所有模块的 SQL。而这条界线的退化恰恰总是从"我只需要一个昵称"开始的。
 *
 * <h2>依赖方向是单向的，且必须保持单向</h2>
 * {@code community → identity} 是允许的方向：identity 是更基础的模块。
 * <b>反向的边（identity → community）不允许建立</b>：那会立刻形成循环依赖，
 * ArchUnit 的 {@code beFreeOfCycles} 断言会让构建失败。
 * 具体到"个人主页要显示帖子数"这类需求，正确做法是由 community 提供接口、
 * 由更上层的组装点调用，而不是让 identity 去读 post 表。
 *
 * <h2>为什么用类而不是接口</h2>
 * 当前只有一个实现，也没有第二种实现的现实可能（它不是策略，是数据访问）。
 * 为了"将来可能替换"而先抽出接口，会得到一个永远只有一个实现的接口 ——
 * 那是负担而不是抽象。
 *
 * <h2>批量方法才是主力</h2>
 * {@link #findBriefs(Collection)} 是列表场景的正确用法：一页 20 条帖子只需一次查询。
 * 逐条调用 {@link #findBrief(long)} 会让列表页退化成 21 次往返。
 * 单条版本保留是为了详情页这类确实只需要一个用户的场景。
 */
@Service
public class UserDirectory {

    private final UserMapper userMapper;

    /**
     * 构造注入。
     *
     * @param userMapper 用户数据访问
     */
    public UserDirectory(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 查询单个用户的展示信息。
     *
     * @param userId 用户自增主键
     * @return 存在时返回；账号已被软删除时返回空
     */
    @Transactional(readOnly = true)
    public Optional<UserBrief> findBrief(long userId) {
        return userMapper.findBriefByIds(List.of(userId)).stream().findFirst();
    }

    /**
     * 按登录名查询用户的展示信息。
     *
     * <h2>为什么需要"按名字查"这一入口</h2>
     * 内部主键（{@code user.id}）是本模块的私有概念，其它模块只会拿到
     * 用户名、邮箱这类<b>它自己看得懂</b>的标识。演示数据生成器就是这样一个调用方：
     * 它按固定规则创建了 {@code demo01} 这批账号，随后要把帖子写到这些账号名下，
     * 于是必须把登录名换算成 {@code author_id}。
     *
     * <p>没有这个方法时，它只有两条路：直接读 {@code user} 表（破坏模块边界），
     * 或者让注册接口把内部主键返回出来（把内部标识暴露到对外契约上）。
     * 两条都比在这里多一个只读查询更糟 —— 因此这个方法的存在本身就是边界的一部分。
     *
     * <p>返回值仍然只有对外可见的那几个字段，不含邮箱与账号状态。
     *
     * @param username 登录名
     * @return 存在时返回；账号已软删除时返回空
     */
    @Transactional(readOnly = true)
    public Optional<UserBrief> findBriefByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return userMapper.findBriefByUsername(username.strip());
    }

    /**
     * 批量查询用户展示信息。
     *
     * @param userIds 用户自增主键集合，可为空集合
     * @return 以 userId 为键的映射。集合为空时返回空映射，<b>不执行查询</b> ——
     *         空 {@code IN ()} 在 SQL 层是语法错误，必须在到达数据库之前拦住
     */
    @Transactional(readOnly = true)
    public Map<Long, UserBrief> findBriefs(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserBrief> result = new LinkedHashMap<>();
        for (UserBrief brief : userMapper.findBriefByIds(userIds)) {
            result.put(brief.userId(), brief);
        }
        return result;
    }
}
