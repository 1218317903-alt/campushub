package ai.camphub.common.hash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 摘要工具。
 *
 * <h2>为什么抽成一个共享工具而不是各处自己写八行</h2>
 * "算一个内容哈希"在本项目里出现在两个地方：上传时边写边算（{@code DigestInputStream}），
 * 以及解析前对读回的字节复算一次做完整性校验。两处的算法必须完全一致 ——
 * 否则校验会用不同的算法去比对同一个值，结果是<b>每一次解析都在校验失败</b>，
 * 而两边的代码看起来都正确。
 *
 * <p>把它集中之后，"我们用的是 SHA-256"这件事只有一个出处。
 */
public final class Sha256 {

    private static final String ALGORITHM = "SHA-256";

    private Sha256() {
    }

    /**
     * 新建一个摘要器。
     *
     * @return 摘要器
     * @throws IllegalStateException 运行环境不支持 SHA-256（部署问题，不是输入问题）
     */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须支持的算法。走到这里说明运行环境被裁剪过。
            throw new IllegalStateException("运行环境不支持 " + ALGORITHM, ex);
        }
    }

    /**
     * 把摘要字节转成小写十六进制。
     *
     * @param digest 摘要字节
     * @return 十六进制字符串
     */
    public static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    /**
     * 直接计算一段内容的哈希。
     *
     * @param content 内容
     * @return 小写十六进制哈希
     */
    public static String of(byte[] content) {
        return hex(newDigest().digest(content));
    }

    /**
     * 校验内容与期望的哈希是否一致。
     *
     * <h2>比较用 {@link MessageDigest#isEqual}</h2>
     * 内容哈希不是密钥，逐个字节比较不会泄漏什么秘密 —— 但用同一个常量时间比较器
     * 处理所有"比较摘要"的场景，省掉了"这一次要不要防时序攻击"这个每次都要重新判断的问题。
     * 判断错了的代价是零收益的风险，而用对它的代价也是零。
     *
     * @param content     内容
     * @param expectedHex 期望的十六进制哈希；为 null 或空白时视为"没有可校验的值"
     * @return 一致、或没有期望值时为 true
     */
    public static boolean matches(byte[] content, String expectedHex) {
        if (expectedHex == null || expectedHex.isBlank()) {
            return true;
        }
        return MessageDigest.isEqual(
                of(content).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedHex.toLowerCase(java.util.Locale.ROOT)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
