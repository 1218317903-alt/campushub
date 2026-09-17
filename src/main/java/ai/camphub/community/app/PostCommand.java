package ai.camphub.community.app;

import java.util.List;

/**
 * 发布与编辑帖子的输入。
 *
 * <p>发布与编辑接受的字段集合相同，因此共用一个记录 —— 分成两个字段完全一样的类型，
 * 只会带来"改了一个忘记改另一个"的风险，而没有表达任何额外信息。
 *
 * <h2>这里只有原始输入，没有派生值</h2>
 * 摘要、渲染后的 HTML、发布时间、作者、对外标识都不在这里：它们由服务端决定，
 * 客户端无从提供。<b>让客户端无法表达"我指定的正文 HTML 是什么"</b>，
 * 正是这套结构存在的意义之一 —— 若请求体里能带 {@code bodyHtml}，
 * 就必须在服务端记得忽略它，而"记得忽略某字段"是最容易被遗漏的一类防御。
 *
 * @param categorySlug 板块对外标识。用标识而不是内部主键：客户端不该知道、也不该去猜
 *                     自增主键（猜得到就意味着可以试探不存在的板块）
 * @param title        标题
 * @param bodyMd       Markdown 正文原文
 * @param tagNames     标签名列表，可为 null 或空。由服务端规范化后解析或创建
 */
public record PostCommand(
        String categorySlug,
        String title,
        String bodyMd,
        List<String> tagNames
) {
}
