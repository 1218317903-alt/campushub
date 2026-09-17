package ai.camphub.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 密码策略测试。
 *
 * <p>本类之所以能写成快速的纯单元测试，正是因为 {@code PasswordPolicy} 不依赖 Spring、
 * 弱密码表由构造参数注入。若它自己去读 classpath 资源，这里就得启动容器才能跑 ——
 * 而一个有十几条断言的规则类，本该在几十毫秒内跑完。
 */
class PasswordPolicyTest {

    /** 测试用弱密码表，覆盖大小写两种写法以验证规范化行为。 */
    private static final Set<String> COMMON_PASSWORDS = Set.of("password", "123456", "qwerty123");

    private static final PasswordPolicy POLICY = new PasswordPolicy(10, COMMON_PASSWORDS);

    private static final String USERNAME = "alice";
    private static final String EMAIL = "alice@example.com";

    @Test
    @DisplayName("合规密码不产生任何问题")
    void shouldAcceptReasonablePassword() {
        assertThat(POLICY.violations("Tr0ub4dor&3xK", USERNAME, EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("过短的密码被拒绝，且提示里带出最小长度")
    void shouldRejectShortPassword() {
        assertThat(POLICY.violations("short123", USERNAME, EMAIL))
                .hasSize(1)
                .anyMatch(problem -> problem.contains("10"));
    }

    @Test
    @DisplayName("长度按 Unicode 码点计算：补充平面字符只算 1 位")
    void shouldCountCodePointsRatherThanUtf16Units() {
        // 8 个汉字（8 码点 / 8 个 char）+ 1 个补充平面 emoji（1 码点 / 2 个 char）
        // = 9 码点、10 个 char。用 codePointCount 判定为"不足 10 位"，
        // 用 String.length() 则会误判为刚好 10 位而放行。
        String nineCodePoints = "一二三四五六七八\uD83D\uDD12";

        assertThat(nineCodePoints.length()).as("前提：该串的 UTF-16 长度确实是 10").isEqualTo(10);
        assertThat(nineCodePoints.codePointCount(0, nineCodePoints.length()))
                .as("前提：该串的码点数确实是 9").isEqualTo(9);

        assertThat(POLICY.violations(nineCodePoints, USERNAME, EMAIL))
                .as("必须按码点判定为过短")
                .hasSize(1)
                .anyMatch(problem -> problem.contains("10"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "PASSWORD", "PassWord"})
    @DisplayName("弱密码表命中时大小写不敏感")
    void shouldRejectCommonPasswordIgnoringCase(String candidate) {
        assertThat(POLICY.violations(candidate, USERNAME, EMAIL))
                .anyMatch(problem -> problem.contains("常见弱密码"));
    }

    @Test
    @DisplayName("被空白字符包裹的弱密码同样被拒绝——加空格不是绕过手段")
    void shouldRejectCommonPasswordSurroundedByWhitespace() {
        // 用户若以为"前后加空格"就能绕过弱密码检查，实际得到的仍是一个会被
        // 攻击字典优先尝试的密码。因此弱密码表比对使用去除首尾空白后的形式。
        assertThat(POLICY.violations("  PASSWORD  ", USERNAME, EMAIL))
                .anyMatch(problem -> problem.contains("常见弱密码"));
    }

    @Test
    @DisplayName("首尾空白被明确拒绝——不静默 trim，避免造出「看着一样却输不对」的密码")
    void shouldRejectSurroundingWhitespace() {
        // 与上一条的区别：上一条是"弱密码表按去空白后的形式比对"，
        // 这一条是"首尾空白本身就要拒绝"。两者都需要：
        // 只做前者，用户会得到一个带不可见空格的有效密码；
        // 只做后者，用户会看到"不能有空格"却不知道空格掩盖了弱密码。
        assertThat(POLICY.violations(" maple-river-quiet-88 ", USERNAME, EMAIL))
                .anyMatch(problem -> problem.contains("空白"));
    }

    @Test
    @DisplayName("密码不能与登录名相同或包含登录名")
    void shouldRejectPasswordRelatedToUsername() {
        assertThat(POLICY.violations("alicealice", USERNAME, EMAIL))
                .anyMatch(problem -> problem.contains("登录名"));
    }

    @Test
    @DisplayName("密码不能包含邮箱 @ 之前的部分")
    void shouldRejectPasswordRelatedToEmail() {
        assertThat(POLICY.violations("bob_the_builder", "someone", "bob@example.com"))
                .anyMatch(problem -> problem.contains("邮箱用户名"));
    }

    @Test
    @DisplayName("账号相关性判断对短片段豁免，避免误伤正常密码")
    void shouldNotFlagVeryShortAccountFragment() {
        // 邮箱本地部分只有 2 个字符时不参与"是否包含"判断：
        // 否则 "ab" 这样的片段会命中大量正常密码，把策略变成噪声
        assertThat(POLICY.violations("xxabxx123456", "someone", "ab@example.com")).isEmpty();
    }

    @Test
    @DisplayName("超过 BCrypt 72 字节上限时拒绝，而不是放任其被静默截断")
    void shouldRejectPasswordLongerThanBcryptLimit() {
        // 100 个汉字 = 300 字节，远超 72 字节。
        // BCrypt 会静默丢弃第 72 字节之后的内容，意味着两个不同的长密码可能哈希成同一个值。
        String tooLong = "密".repeat(100);

        assertThat(POLICY.violations(tooLong, USERNAME, EMAIL))
                .anyMatch(problem -> problem.contains("72"));
    }

    @Test
    @DisplayName("一次返回全部问题，让用户一次改对")
    void shouldReturnAllProblemsAtOnce() {
        assertThat(POLICY.violations("password", USERNAME, EMAIL))
                .as("既过短（8 位 < 10）又命中弱密码表，两条都应报出")
                .hasSize(2);
    }

    @Test
    @DisplayName("空密码只报一条，不产生无意义的后续判断")
    void shouldReportOnlyBlankProblemForBlankPassword() {
        assertThat(POLICY.violations("   ", USERNAME, EMAIL))
                .hasSize(1)
                .anyMatch(problem -> problem.contains("不能为空"));
    }

    @Test
    @DisplayName("null 密码不抛异常，按空处理")
    void shouldHandleNullPassword() {
        assertThat(POLICY.violations(null, USERNAME, EMAIL))
                .hasSize(1)
                .anyMatch(problem -> problem.contains("不能为空"));
    }
}
