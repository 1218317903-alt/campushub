package ai.camphub.workspace.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.config.WorkspaceProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 短期下载令牌的签发与校验。
 *
 * <h2>这个类唯一被攻击的方式是"用户改字符串"，因此测试就围着它写</h2>
 * 令牌的内容由服务端生成、也由服务端读，客户端能做的事只有一件：
 * 把它改成别的东西再送回来。因此这里的用例是穷举"改动会怎样"：
 * 改载荷、改签名、换成别的密钥签的、放太久变过期、以及各种根本不是令牌的输入。
 *
 * <h2>过期怎么测：换一个时钟，而不是等</h2>
 * 本类里没有一个 {@code Thread.sleep}。过期由<b>第二个持有不同时钟的服务实例</b>
 * 来验证 —— 它同时说明了另一件事：有效期来自配置而不是硬编码，
 * 因为两个实例的配置相同、只有时钟不同。
 */
class DownloadTokenServiceTest {

    /** 所有用例共用同一个"签发时刻"，让断言里的时间是一个确定的事实。 */
    private static final Instant ISSUED_AT = Instant.parse("2026-09-18T10:00:00Z");

    /** 测试密钥。它不是凭据，只是让"换了密钥就验不过"这一条可测。 */
    private static final String SECRET = "test-only-download-token-secret";

    /** 另一个密钥，用于验证"换密钥签的令牌验不过"。 */
    private static final String OTHER_SECRET = "another-test-only-secret";

    /** 有效期，秒。 */
    private static final int TTL_SECONDS = 300;

    private static final String WORKSPACE = "ws_abc123";

    private static final String DOCUMENT = "doc_def456";

    /**
     * 构造一个在指定时刻的服务实例。
     *
     * @param now         时钟指向的时刻
     * @param ttlSeconds  有效期
     * @param secret      签名密钥
     * @return 服务实例
     */
    private static DownloadTokenService service(Instant now, int ttlSeconds, String secret) {
        WorkspaceProperties properties = new WorkspaceProperties(null, null, null,
                new WorkspaceProperties.Documents(1024, List.of("text/plain"), false, "",
                        ttlSeconds, secret),
                null, null, null, null);
        return new DownloadTokenService(properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    /**
     * 构造一个在签发时刻生效的默认服务。
     *
     * @return 服务实例
     */
    private static DownloadTokenService service() {
        return service(ISSUED_AT, TTL_SECONDS, SECRET);
    }

    @Test
    @DisplayName("签发后能验出它绑定的空间与文档")
    void roundTripReturnsBoundTarget() {
        String token = service().issue(WORKSPACE, DOCUMENT);

        Optional<DownloadTokenService.DownloadTicket> verified = service().verify(token);

        assertThat(verified).isPresent();
        assertThat(verified.get().workspacePublicId()).isEqualTo(WORKSPACE);
        assertThat(verified.get().docPublicId()).isEqualTo(DOCUMENT);
    }

    @Test
    @DisplayName("令牌是 URL 安全的：不含 + / =")
    void tokenIsUrlSafe() {
        // 它要出现在 URL 路径里。带上 + 或 = 之后，各级代理与日志系统
        // 对它的转义处理各不相同 —— 那会变成"链接在某些环境下打不开"。
        String token = service().issue(WORKSPACE, DOCUMENT);

        assertThat(token).doesNotContain("+").doesNotContain("/").doesNotContain("=");
    }

    @Test
    @DisplayName("有效期来自配置，不是硬编码")
    void ttlComesFromConfiguration() {
        assertThat(service().ttl()).isEqualTo(Duration.ofSeconds(TTL_SECONDS));
        assertThat(service(ISSUED_AT, 60, SECRET).ttl()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("改一个字符就验不过 —— 载荷与签名都在被保护的范围里")
    void tamperingIsRejected() {
        String token = service().issue(WORKSPACE, DOCUMENT);
        int separator = token.indexOf('.');

        // 改载荷：换掉载荷部分的一个字符（保持 base64 合法，因此它仍能解码出来，
        // 只是内容变了）。若签名没有覆盖载荷，这一改就会被放行。
        String payload = token.substring(0, separator);
        String signature = token.substring(separator + 1);
        String tamperedPayload = flipFirstChar(payload) + "." + signature;
        assertThat(service().verify(tamperedPayload)).isEmpty();

        // 改签名：这一次载荷是真的，签名是假的。
        String tamperedSignature = payload + "." + flipFirstChar(signature);
        assertThat(service().verify(tamperedSignature)).isEmpty();
    }

    @Test
    @DisplayName("换一个密钥签出来的令牌验不过")
    void tokenFromAnotherSecretIsRejected() {
        String token = service(ISSUED_AT, TTL_SECONDS, OTHER_SECRET).issue(WORKSPACE, DOCUMENT);

        assertThat(service().verify(token)).isEmpty();
    }

    @Test
    @DisplayName("超过有效期即失效 —— 判据是时刻，而不是用过几次")
    void expiredTokenIsRejected() {
        String token = service().issue(WORKSPACE, DOCUMENT);

        assertThat(service(ISSUED_AT.plusSeconds(TTL_SECONDS), TTL_SECONDS, SECRET).verify(token))
                .as("恰好到期的这一秒仍然有效（判据是 clock > expiresAt）")
                .isPresent();
        assertThat(service(ISSUED_AT.plusSeconds(TTL_SECONDS + 1), TTL_SECONDS, SECRET).verify(token))
                .isEmpty();
    }

    @Test
    @DisplayName("同一枚令牌可以反复使用 —— 它是凭据，不是一次性票据")
    void tokenIsReusableUntilItExpires() {
        // 这条是有意为之的：链接的用途包含"转发给别人"，而一次性的链接
        // 在转发场景下会变成"第一个打开的人决定了其他人打不开"。
        // 暴露窗口由有效期控制，而不是由使用次数控制。
        String token = service().issue(WORKSPACE, DOCUMENT);

        assertThat(service().verify(token)).isPresent();
        assertThat(service().verify(token)).isPresent();
        assertThat(service().verify(token)).isPresent();
    }

    @Nested
    @DisplayName("非法输入")
    class MalformedInput {

        @Test
        @DisplayName("各种不是令牌的输入返回空，而不是抛异常")
        void returnsEmptyInsteadOfThrowing() {
            // 用户会手工截断、拼错、或者在聊天软件里被自动加标点。
            // 这些都是正常输入，不是异常情况 —— 返回 Optional 让调用方
            // 无法忘记处理它。
            for (String candidate : new String[]{"", "   ", "nodot", ".", "a.", ".b", "a.b.c",
                    "中文不是令牌", "!!!.???"}) {
                assertThat(service().verify(candidate))
                        .as("输入 [%s] 应当被安静地拒绝", candidate)
                        .isEmpty();
            }
            assertThat(service().verify(null)).isEmpty();
        }

        @Test
        @DisplayName("载荷段数不对时拒绝（多一个冒号也不行）")
        void wrongFieldCountIsRejected() {
            // 直接构造一个"签名正确但载荷结构不对"的令牌是不可能的（签不出来），
            // 因此这里只验证公开行为：随机 base64 串一律被拒。
            assertThat(service().verify("YWJj.ZGVm")).isEmpty();
        }

        @Test
        @DisplayName("requireValid 把无效令牌翻成 40024")
        void requireValidThrowsDownloadLinkInvalid() {
            assertThatThrownBy(() -> service().requireValid("not-a-token"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                            .isEqualTo(ErrorCode.DOWNLOAD_LINK_INVALID));
        }
    }

    /**
     * 把 base64url 串的第一个字符换掉。
     *
     * <p>换掉而不是删掉：删掉会让长度变化、可能直接解码失败，
     * 于是测试验证的变成"格式校验"而不是"签名校验" —— 那是两件不同的事。
     *
     * @param value 原串
     * @return 改过的串
     */
    private static String flipFirstChar(String value) {
        char first = value.charAt(0);
        char replacement = first == 'A' ? 'B' : 'A';
        return replacement + value.substring(1);
    }
}
