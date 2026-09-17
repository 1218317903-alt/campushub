package ai.camphub.identity.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 密码策略：判断一个候选密码是否可以接受。
 *
 * <h2>为什么不做"大写+小写+数字+符号"的强制组合</h2>
 * 强制组合会把用户推向可预测的变形模式（{@code Password1!}、{@code Qwer1234!}），
 * 攻击者的字典里这类模式权重最高，实际强度反而低于一个不满足组合要求的长口令。
 * NIST SP 800-63B 因此明确建议：<b>要求长度、检查是否出现在已知泄露列表中、
 * 不强制字符类别组合</b>。本类遵循这一结论。
 *
 * <h2>领域纯净性</h2>
 * 本类不依赖任何 Spring 类型，常用弱密码表由外部注入（{@code Set}），
 * 因此可以用一个三行的单元测试完整覆盖它的全部规则 ——
 * 不需要启动容器，也不需要读取 classpath 资源。
 */
public final class PasswordPolicy {

    /**
     * BCrypt 的有效输入上限（字节）。超过 72 字节的部分会被**静默丢弃**，
     * 意味着两个不同的长密码可能哈希成同一个值。与其接受这种静默的强度损失，
     * 不如在入口处直接拒绝并告诉用户原因。
     */
    public static final int BCRYPT_MAX_BYTES = 72;

    /** 判断"密码是否包含用户名"时的最小子串长度，太短的子串容易误伤。 */
    private static final int MIN_SUBSTRING_CHECK_LENGTH = 3;

    private final int minLength;
    private final Set<String> commonPasswords;

    /**
     * 构造策略。
     *
     * @param minLength       最短长度（按 Unicode 码点计，一个汉字算 1 位）
     * @param commonPasswords 常用弱密码表。传入前应已统一为小写；
     *                        本类内部会再做一次小写规范化以防调用方遗漏
     */
    public PasswordPolicy(int minLength, Set<String> commonPasswords) {
        this.minLength = minLength;
        this.commonPasswords = commonPasswords.stream()
                .map(p -> p.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 校验候选密码，返回全部不满足项。
     *
     * <p>刻意返回<b>全部</b>问题而不是遇到第一个就返回：用户一次就能把密码改对，
     * 而不是"改一次、被拒一次、再改一次"。
     *
     * @param password 候选密码
     * @param username 登录名，用于判断密码是否与账号相关
     * @param email    邮箱，用于判断密码是否与账号相关
     * @return 问题描述列表；为空表示通过
     */
    public List<String> violations(String password, String username, String email) {
        List<String> problems = new ArrayList<>();

        if (password == null || password.isBlank()) {
            problems.add("密码不能为空");
            // 空密码后续的长度、包含关系判断都没有意义，直接返回
            return problems;
        }

        int codePoints = password.codePointCount(0, password.length());
        if (codePoints < minLength) {
            problems.add("密码长度至少 " + minLength + " 位");
        }

        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > BCRYPT_MAX_BYTES) {
            problems.add("密码过长：按 UTF-8 计算不得超过 " + BCRYPT_MAX_BYTES + " 字节（当前 " + bytes + " 字节）");
        }

        // 首尾空白在视觉上不可见，却会改变哈希结果，于是制造出一个支持上无解的局面：
        // 用户认为自己设的是 "password123"，实际设的是 " password123 "，
        // 之后无论怎么"照原样输入"都可能对不上。这里明确拒绝，而不是静默 trim ——
        // 静默修改用户的凭据是更糟的选择：他会发现"密码被别人改过了"。
        boolean hasSurroundingWhitespace = !password.equals(password.strip());
        if (hasSurroundingWhitespace) {
            problems.add("密码不能以空白字符（空格、制表符等）开头或结尾");
        }

        // 比对用"去掉首尾空白"后的形式：否则 "  password123  " 就成了绕过弱密码表的手段 ——
        // 而攻击字典的第一条规则正是"在候选词前后补上常见字符"。
        String comparable = password.toLowerCase(Locale.ROOT).strip();

        if (commonPasswords.contains(comparable)) {
            problems.add("该密码出现在常见弱密码列表中，请更换");
        }

        String normalizedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!normalizedUsername.isEmpty()) {
            if (comparable.equals(normalizedUsername)) {
                problems.add("密码不能与登录名相同");
            } else if (normalizedUsername.length() >= MIN_SUBSTRING_CHECK_LENGTH
                    && comparable.contains(normalizedUsername)) {
                problems.add("密码不能包含登录名");
            }
        }

        String emailLocalPart = localPartOf(email);
        if (!emailLocalPart.isEmpty()
                && emailLocalPart.length() >= MIN_SUBSTRING_CHECK_LENGTH
                && comparable.contains(emailLocalPart)) {
            problems.add("密码不能包含邮箱用户名");
        }

        return problems;
    }

    /**
     * 取出邮箱 {@code @} 之前的部分并小写化。
     *
     * @param email 邮箱，可为 null
     * @return 规范化后的本地部分；无法解析时返回空串
     */
    private static String localPartOf(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "";
        }
        return email.substring(0, at).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @return 配置的最短长度，供接口文档与错误提示复用
     */
    public int minLength() {
        return minLength;
    }
}
