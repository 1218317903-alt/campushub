package ai.camphub.community.app;

import ai.camphub.community.domain.PostSummary;
import ai.camphub.community.domain.TagAssignment;
import ai.camphub.identity.domain.UserBrief;
import java.util.List;

/**
 * 列表卡片所需的完整信息。
 *
 * <h2>为什么要把作者与互动状态"装配进"视图，而不是留给接口层去凑</h2>
 * 这两个信息各需要一次<b>批量</b>查询（作者昵称来自 identity 模块、互动状态来自
 * post_reaction）。批量查询必须发生在"一整页帖子都已经在手"之后，而且必须只发生一次 ——
 * 若把这一步留给接口层，每个接口实现都要自己记得先收集 id 再批量查，
 * 漏掉的地方就会退化成逐条查询（N+1），而它只表现为"列表页变慢"，不会被任何断言拦住。
 *
 * <p>因此这一层负责把"一页帖子"补全成"一页可直接渲染的卡片"，
 * 接口层只做字段到响应体的映射。
 *
 * @param post                帖子列表项（来自 post 表，带板块的 slug 与名称）
 * @param tags                该帖的标签。可能为空列表
 * @param author              作者展示信息。账号被软删除时为 null，接口层需给出占位文案 ——
 *                            内容仍在、作者已注销，是必须能表达的状态
 * @param liked               当前用户是否点过赞。未登录时恒为 false
 * @param favorited           当前用户是否收藏过。未登录时恒为 false
 * @param ownedByCurrentUser  是否由当前用户发布，供前端决定是否显示编辑/删除入口。
 *                            <b>它只是渲染提示，不是鉴权依据</b> ——
 *                            真正的归属校验在服务端每次写操作时重新做
 */
public record PostCardView(
        PostSummary post,
        List<TagAssignment> tags,
        UserBrief author,
        boolean liked,
        boolean favorited,
        boolean ownedByCurrentUser
) {
}
