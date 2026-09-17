package ai.camphub.platform.audit.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审计动作码。
 *
 * <h2>为什么用枚举而不是随手写字符串</h2>
 * 审计日志的价值取决于<b>可聚合性</b>：出了事要能问"最近 24 小时有多少次
 * {@code AUTH_LOGIN_FAILURE}"。如果动作码是自由字符串，早晚会同时出现
 * {@code AUTH_LOGIN_FAILURE} / {@code LOGIN_FAILED} / {@code auth.login.fail} 三种写法，
 * 聚合查询就废了。枚举把这个约束交给编译器。
 *
 * <h2>范围纪律</h2>
 * 本枚举只登记<b>已经在写</b>的动作。不为尚未实现的阶段预留动作码 ——
 * 一个永远不会有日志写入的动作码，只会让"到底哪些操作被审计了"这个问题更难回答。
 * 后续阶段各自追加（Phase 03 加内容类，Phase 10 加审核与后台类）。
 */
public enum AuditAction {

    /** 注册成功。 */
    AUTH_REGISTER("注册账号"),

    /** 登录成功。 */
    AUTH_LOGIN_SUCCESS("登录成功"),

    /** 登录失败（口令错误、账号不存在、账号不可用等，具体原因记在 detail 里）。 */
    AUTH_LOGIN_FAILURE("登录失败"),

    /** 主动登出当前会话。 */
    AUTH_LOGOUT("登出"),

    /** 登出全部设备（递增令牌世代号）。 */
    AUTH_LOGOUT_ALL("登出全部设备"),

    /** 用刷新令牌换取新的令牌对。 */
    AUTH_TOKEN_REFRESH("刷新令牌"),

    /**
     * 检测到刷新令牌重放：一个已被轮换失效的令牌被再次使用。
     *
     * <p>这是<b>安全事件</b>而非普通失败 —— 它意味着令牌已经泄露到客户端之外，
     * 因此处置动作是撤销该用户全部会话，而不是只拒绝这一次请求。
     */
    AUTH_TOKEN_REPLAY_DETECTED("刷新令牌重放（判定为泄露）"),

    /** 修改密码（同时递增令牌世代号，使其它设备的登录状态失效）。 */
    AUTH_PASSWORD_CHANGE("修改密码"),

    /** 修改个人资料。 */
    USER_PROFILE_UPDATE("修改个人资料"),

    /** 单独下线某个设备会话。 */
    SESSION_REVOKE("下线设备会话");

    /** 动作码 → 中文说明，供后台展示与文档生成复用。 */
    private static final Map<String, AuditAction> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    private final String description;

    AuditAction(String description) {
        this.description = description;
    }

    /**
     * @return 面向人的中文说明
     */
    public String description() {
        return description;
    }

    /**
     * 按名称查找动作码。
     *
     * @param name 动作码名称
     * @return 对应的动作码
     * @throws IllegalArgumentException 名称未登记时
     */
    public static AuditAction of(String name) {
        AuditAction action = BY_NAME.get(name);
        if (action == null) {
            throw new IllegalArgumentException("未登记的审计动作码：" + name);
        }
        return action;
    }
}
