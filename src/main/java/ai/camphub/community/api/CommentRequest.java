package ai.camphub.community.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发表评论或回复的请求体。
 *
 * <p>上限 1000 字符与 {@code comment.body VARCHAR(1000)} 对齐。评论是短回复，
 * 与正文不同，这个上限是产品约束而非纯粹的资源保护参数，因此写死在注解里、
 * 不进配置 —— 放宽它需要先改列宽，那是一次表结构变更，不该由一次配置调整触发。
 *
 * @param body     纯文本正文。刻意<b>不做</b> Markdown 渲染，理由见 V3 迁移的注释：
 *                 收益（结构、表格）对短回复很有限，而注入面会扩大到每一条评论
 * @param parentId 被回复的顶层评论对外标识；发表顶层评论时省略或传 null。
 *                 传一条回复的标识会被拒绝（{@code 40001}）—— 本平台只有两层
 */
public record CommentRequest(

        @NotBlank(message = "评论内容不能为空")
        @Size(max = 1000, message = "评论最多 1000 字")
        String body,

        String parentId
) {
}
