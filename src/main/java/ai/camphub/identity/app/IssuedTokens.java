package ai.camphub.identity.app;

/**
 * 一次签发产生的令牌对。
 *
 * <p><b>刷新令牌只在响应中出现这一次。</b>服务端只保存它的哈希，因此它无法被重新读出 ——
 * 若客户端把它弄丢，唯一的出路是重新登录。这个限制是刻意的：
 * 任何"能再取回刷新令牌"的机制，都意味着服务端掌握了可直接冒用账号的明文凭据。
 *
 * @param accessToken                 访问令牌（JWT）
 * @param refreshToken                刷新令牌（不透明随机串），仅在本次响应中返回
 * @param expiresInSeconds            访问令牌有效期（秒），供客户端提前刷新
 */
public record IssuedTokens(
        String accessToken,
        String refreshToken,
        long expiresInSeconds
) {
}
