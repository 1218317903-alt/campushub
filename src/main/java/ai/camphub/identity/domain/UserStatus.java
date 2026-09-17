package ai.camphub.identity.domain;

/**
 * 账号状态。
 *
 * <p><b>为什么用三个状态而不是一个 {@code enabled} 布尔位</b>：
 * "停用"与"锁定"是两种不同的业务事实，处理方式也不同 ——
 * 锁定是**临时**的（由登录失败触发，到期自动解除，用户无需申诉），
 * 停用是**管理决定**（须由管理员解除）。把它们压成一个布尔位，
 * 就没法回答"这个账号为什么不能用、什么时候能恢复"，无论对用户提示
 * 还是对后台排查都是信息丢失。
 *
 * <p>刻意**没有** {@code PENDING_VERIFICATION}：本阶段没有邮箱/短信验证流程，
 * 定义一个永远不会被写入的状态值属于装饰。
 */
public enum UserStatus {

    /** 正常可用。 */
    ACTIVE,

    /** 因连续登录失败被临时锁定，{@code user_credential.locked_until} 到期后自动恢复。 */
    LOCKED,

    /** 被管理员停用。用户无法自行恢复。 */
    DISABLED;

    /**
     * 解析数据库中存储的状态字符串。
     *
     * <p>遇到未知值直接抛异常而不是回退到某个默认值：数据库里出现无法识别的状态，
     * 说明代码与数据已经不一致，此时"当作 ACTIVE 处理"会让一个本该被拦住的账号放行 ——
     * 这类静默降级在安全域里是不可接受的。
     *
     * @param raw 数据库中的原始值
     * @return 对应的状态
     * @throws IllegalArgumentException 值无法识别时
     */
    public static UserStatus fromDatabase(String raw) {
        for (UserStatus status : values()) {
            if (status.name().equals(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("无法识别的账号状态：" + raw);
    }
}
