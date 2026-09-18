package ai.camphub.workspace.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式声明一个 Mapper 方法<b>不</b>接受第三层防线的自动空间过滤，并给出理由。
 *
 * <h2>为什么需要一个"拒绝注解"</h2>
 * 若只有 {@link ScopedTable}，"没有标注"就同时表达了两件完全不同的事：
 * <ul>
 *   <li>这条查询不涉及私有数据（例如查 {@code category} 字典）—— 无需标注，是正确的；</li>
 *   <li>这条查询涉及私有数据但作者忘了标注 —— 这是漏洞。</li>
 * </ul>
 * 两者在代码里长得一模一样，而后者无法被任何自动化手段发现。
 * 要求"涉及私有数据的查询必须二选一"之后，忘记标注会变成构建失败，
 * 而有意为之的那些则必须在注解里写下理由 —— 理由留在代码里，
 * 下一个改动它的人会先读到。
 *
 * <p>{@code reason} 刻意是必填的 {@code String} 而不是可选值：
 * 一个可以留空的理由字段，最终会全部留空。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Unscoped {

    /**
     * 为什么这条查询不能接受自动空间过滤，以及它靠什么保证不会越权。
     *
     * @return 理由，必填
     */
    String reason();
}
