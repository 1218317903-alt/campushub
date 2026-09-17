package ai.camphub.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 令牌哈希工具。
 *
 * <h2>为什么是 SHA-256 而不是 BCrypt</h2>
 * 直觉上"凭据就该用慢哈希"，但对刷新令牌不成立：
 * <ul>
 *   <li><b>没有弱口令问题</b>：令牌是服务端生成的 256 bit 随机值，不是人选的密码，
 *       不存在字典攻击的空间，慢哈希防的是"猜得出"，而这里猜不出。</li>
 *   <li><b>慢哈希会变成性能瓶颈</b>：每次刷新都要按 {@code token_hash} 检索，
 *       BCrypt 的 cost=12 意味着每次查询前先付几十毫秒 CPU。
 *       用它保护一个本来就不可猜的值，是在为不存在的威胁付费。</li>
 * </ul>
 * 密码仍然使用 BCrypt —— 那里"可猜"是真实存在的威胁，代价花得值。
 *
 * <p>输出固定为 64 位小写十六进制，与 {@code refresh_token.token_hash CHAR(64)} 对齐。
 */
public final class TokenHasher {

    /** 数据库列宽与哈希长度一致，避免"列里存了截断值"这类静默错误。 */
    public static final int HASH_HEX_LENGTH = 64;

    private TokenHasher() {
    }

    /**
     * 计算字符串的 SHA-256 哈希（小写十六进制）。
     *
     * @param raw 原文。允许为空字符串（用于需要统一处理边界值的场景），但不接受 null
     * @return 64 位十六进制哈希
     */
    public static String sha256Hex(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("raw 不能为 null");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，走到这里说明运行环境已被破坏
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，运行环境异常", e);
        }
        byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
        char[] hex = new char[HASH_HEX_LENGTH];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            hex[i * 2] = Character.forDigit(value >>> 4, 16);
            hex[i * 2 + 1] = Character.forDigit(value & 0x0F, 16);
        }
        return new String(hex);
    }
}
