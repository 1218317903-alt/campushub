package ai.camphub.community.api;

import ai.camphub.community.domain.PostSort;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 把查询串里的 {@code sort} 转成 {@link PostSort}，**不区分大小写**。
 *
 * <h2>为什么必须有它</h2>
 * {@code sort} 会出现在用户可见的 URL 里（{@code /community?sort=hot}），
 * 而 Spring 对枚举的默认转换是<b>区分大小写</b>的。直接把 {@code PostSort} 声明成
 * {@code @RequestParam} 的类型，结果就是：接口文档写着"不区分大小写"，实际却只认大写 ——
 * {@code ?sort=hot} 得到 400，而 {@code ?sort=HOT} 正常。
 *
 * <p>这个不一致<b>在只跑集成测试时不会暴露</b>：测试里写的字面量通常与枚举常量同名，
 * 于是永远是合法输入。它只在真实客户端按 URL 习惯发小写请求时才出现 ——
 * 也就是前端第一次把它接起来的时候。这类"契约文档与实现不一致"的缺陷，
 * 必须靠"从真实调用方出发的测试"才能挡住（见 {@code CommunityContentIT} 的排序用例）。
 *
 * <h2>非法取值仍然被拒绝</h2>
 * {@code ?sort=trending} 会抛 {@link IllegalArgumentException}，由 Spring 转成类型转换失败，
 * 最终返回 {@code 40002}。刻意<b>不</b>做"不认识就按默认排序"的兜底：
 * 那会让一个拼错的参数静默退化成"看起来正常工作"，而调用方永远不知道自己的筛选没生效。
 */
@Component
public class PostSortConverter implements Converter<String, PostSort> {

    @Override
    public PostSort convert(String source) {
        // 用 Locale.ROOT 而不是平台默认：土耳其语的 "i".toUpperCase() 会得到 'İ'，
        // 于是 "latest" 在土耳其环境中转不成 LATEST。这类问题只在特定区域设置下出现，
        // 排查成本极高，而从一开始就不会写错
        return PostSort.valueOf(source.strip().toUpperCase(Locale.ROOT));
    }
}
