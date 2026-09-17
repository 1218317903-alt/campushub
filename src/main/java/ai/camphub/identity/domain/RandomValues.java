package ai.camphub.identity.domain;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 随机值生成：对外标识与不透明令牌。
 *
 * <h2>为什么必须用 {@link SecureRandom} 而不是 {@code Random}</h2>
 * 这两类值的<b>全部安全性都来自不可预测性</b>：
 * <ul>
 *   <li>{@code public_id} 用于替代自增主键对外暴露，目的就是让"换个数字试试"失效。
 *       若生成序列可预测，攻击者就能枚举出全部资源的标识，这层防护等于没做。</li>
 *   <li>刷新令牌本身就是凭据，可预测等于可以直接登录别人账号。</li>
 * </ul>
 * {@code java.util.Random} 是可复现的伪随机序列，只适合做模拟数据。
 */
public final class RandomValues {

    /** 对外标识长度，与 {@code CHAR(22)} 列宽一致。 */
    public static final int PUBLIC_ID_LENGTH = 22;

    /** 刷新令牌的随机字节数：256 bit。 */
    private static final int OPAQUE_TOKEN_BYTES = 32;

    /**
     * 62 进制字符表（数字 + 大小写字母）。
     *
     * <p>刻意不用 URL 安全字符表：{@code public_id} 会出现在 URL 路径里，
     * 用这套字符集可以避免 {@code -} {@code _} 与视觉上易混的字符带来的复制粘贴问题。
     * 62^22 ≈ 2.7×10^39，远超暴力枚举范围。
     */
    private static final char[] PUBLIC_ID_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private RandomValues() {
    }

    /**
     * 生成对外标识。
     *
     * @return 22 位随机字符串
     */
    public static String publicId() {
        char[] chars = new char[PUBLIC_ID_LENGTH];
        for (int i = 0; i < PUBLIC_ID_LENGTH; i++) {
            chars[i] = PUBLIC_ID_ALPHABET[SECURE_RANDOM.nextInt(PUBLIC_ID_ALPHABET.length)];
        }
        return new String(chars);
    }

    /**
     * 生成不透明令牌（用于刷新令牌）。
     *
     * <p>用 Base64 URL 安全编码且**去掉填充符**：{@code =} 在 URL 与表单里需要转义，
     * 而去掉填充不影响解码（长度已知）。
     *
     * @return 43 字符的 URL 安全随机串
     */
    public static String opaqueToken() {
        byte[] bytes = new byte[OPAQUE_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
