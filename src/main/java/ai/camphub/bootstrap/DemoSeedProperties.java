package ai.camphub.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 合成演示数据配置（前缀 {@code app.demo-seed}）。
 *
 * <h2>为什么不放在 {@code app.community} 下</h2>
 * 因为它跨模块：它既要建 identity 的演示账号，又要建 community 的内容。
 * 挂在任何一个业务模块下都会造成误导 —— 读配置的人会以为关掉 community
 * 就不影响它，而它其实还会往 user 表里写数据。
 * 因此它用与业务模块无关的顶层前缀，归属"应用组装"这一层。
 *
 * <h2>默认关闭，且不依赖"部署的人记得关"</h2>
 * 一个会往业务表写数据的组件，被误开启的代价是一批看起来像真人的假数据 ——
 * 清理它们远比打开开关麻烦。因此：{@code enabled} 默认 {@code false}，
 * 且真正的执行条件写在 {@link DemoSeedRunner} 里（再叠加一道环境判断），
 * 而不是靠这里的默认值单独兜底。
 *
 * @param enabled      是否启用。默认关闭，只在 {@code application-local.yml} 中打开
 * @param authorCount  生成的演示作者数
 * @param postCount    生成的帖子数
 * @param commentCount 生成的评论数（顶层 + 回复的合计上限）
 * @param password     演示账号的登录口令。它由 {@code PasswordPolicy} 校验，
 *                     不满足时生成过程会**直接抛错**而不是静默少建几个账号 ——
 *                     演示数据少一半却照常启动，是比启动失败更难查的情况
 */
@ConfigurationProperties(prefix = "app.demo-seed")
public record DemoSeedProperties(
        boolean enabled,
        int authorCount,
        int postCount,
        int commentCount,
        String password
) {
}
