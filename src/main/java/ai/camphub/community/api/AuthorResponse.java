package ai.camphub.community.api;

import ai.camphub.identity.domain.UserBrief;

/**
 * 作者展示信息（响应体）。
 *
 * <h2>注销用户的占位值由服务端给出，而不是留给前端</h2>
 * 内容仍在、作者已注销，是一个必须能表达的正常状态。若这一层在作者缺失时返回 null，
 * 每个渲染作者的地方（列表卡片、详情页、评论区）都要各自判空并各自决定显示什么 ——
 * 结果必然是三种不同的文案，其中一种很可能是空白。
 *
 * <p>{@code publicId} 为 null 表示无法跳转到个人主页。前端需要据此把作者名渲染成
 * 不可点击的文本，而不是拼出一个指向空白的链接。
 *
 * @param publicId  作者对外标识；账号已注销时为 null
 * @param nickname  展示名；账号已注销时为固定占位文案
 * @param avatarUrl 头像地址，可为 null（前端需处理，不要假设一定有）
 */
public record AuthorResponse(String publicId, String nickname, String avatarUrl) {

    /** 账号已注销时展示的占位名。 */
    private static final String DELETED_AUTHOR_NICKNAME = "已注销用户";

    /**
     * 从用户简介构造。
     *
     * @param brief 用户简介；账号已注销或查不到时为 null
     * @return 作者信息
     */
    public static AuthorResponse from(UserBrief brief) {
        if (brief == null) {
            return new AuthorResponse(null, DELETED_AUTHOR_NICKNAME, null);
        }
        return new AuthorResponse(brief.publicId(), brief.nickname(), brief.avatarUrl());
    }
}
