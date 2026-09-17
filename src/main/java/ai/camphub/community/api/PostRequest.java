package ai.camphub.community.api;

import ai.camphub.community.app.PostCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 发布 / 编辑帖子的请求体。
 *
 * <h2>校验分成两处，是刻意的分工</h2>
 * <ul>
 *   <li><b>本类的注解</b>管"字段格式"：必填、与数据库列宽对齐的长度上限。
 *       它们不会随运营或压测调整 —— 改变它们意味着改表结构。</li>
 *   <li><b>服务层</b>管"业务策略"：正文长度上限、标签数量上限。
 *       这些值来自 {@code app.community.*} 配置，会被压测调整，
 *       因此不能写死在注解里：把同一个上限写在注解和配置两处，
 *       等于制造两个必须永远保持一致的来源。</li>
 * </ul>
 *
 * <h2>为什么没有 {@code bodyHtml}、{@code publishedAt} 之类的字段</h2>
 * 它们由服务端决定。<b>让客户端无法表达这些值</b>，比"服务端记得忽略它们"可靠 ——
 * 后者依赖每一处实现都不出错，而前者是结构上的不可能。
 *
 * @param categorySlug 板块对外标识。上限 32 与 {@code category.slug VARCHAR(32)} 对齐
 * @param title        标题。上限 120 与 {@code post.title VARCHAR(120)} 对齐
 * @param bodyMd       Markdown 正文。<b>不在此处声明长度上限</b>：真实上限来自配置
 *                     （{@code app.community.post.max-body-length}），由服务层校验并返回
 *                     {@code 40031}。请求体的绝对大小由容器的请求体上限兜底
 * @param tagNames     标签名列表。单个标签名上限 32 与 {@code tag.name VARCHAR(32)} 对齐；
 *                     数量上限不在这里 —— 它来自配置，由服务层校验。
 *                     校验不通过时统一返回 {@code 40031}，因此前端不需要知道具体上限
 */
public record PostRequest(

        @NotBlank(message = "请选择板块")
        @Size(max = 32, message = "板块标识过长")
        String categorySlug,

        @NotBlank(message = "请填写标题")
        @Size(max = 120, message = "标题最多 120 字")
        String title,

        @NotBlank(message = "请填写正文")
        String bodyMd,

        List<@NotBlank(message = "标签名不能为空") @Size(max = 32, message = "单个标签最多 32 字") String> tagNames
) {

    /**
     * 转换为应用层命令。
     *
     * @return 命令对象
     */
    public PostCommand toCommand() {
        return new PostCommand(categorySlug, title, bodyMd, tagNames);
    }
}
