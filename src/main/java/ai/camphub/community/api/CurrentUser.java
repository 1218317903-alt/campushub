package ai.camphub.community.api;

import ai.camphub.identity.domain.UserPrincipal;

/**
 * 从鉴权上下文中取出当前用户，并容忍"当前没有用户"。
 *
 * <h2>为什么需要它（而不是各处直接写 {@code principal.userId()}）</h2>
 * 社区的读接口是公开的（浏览内容不需要登录），但它们的结果里有一部分是
 * <b>因人而异</b>的：这条帖子我点过赞吗、我收藏过吗、这条内容是我发的吗。
 * 于是这些接口都必须接住"匿名访问"这一情形。
 *
 * <p>把 {@code principal == null ? null : principal.userId()} 抄在每个接口里有两个问题：
 * 一是四处都要记得写，漏掉一处就是一个 {@code NullPointerException}，
 * 而且只在匿名访问时出现 —— 本地开发时自己总是带着登录态，很难碰上；
 * 二是{@code null} 在这里是一个<b>有含义的值</b>（"本次请求没有身份"），
 * 而不是一个待处理的意外。把它的含义写在一个地方，比在四处重复一个三元表达式更清楚。
 *
 * <p><b>写接口绝不使用它</b>：那些接口在安全过滤链上被要求必须已认证，
 * 主体不可能为空，直接用 {@code principal.userId()} 反而更安全 ——
 * 一旦哪天有人误把某个写接口放进了公开清单，直接取值会立刻以 500 暴露出来，
 * 而传 {@code null} 进去只会让"作者是谁"变成一个需要顺着链路排查的问题。
 */
final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * 取当前用户主键。
     *
     * @param principal 当前用户；匿名访问时为 null
     * @return 用户自增主键；匿名访问时为 null
     */
    static Long idOf(UserPrincipal principal) {
        return principal == null ? null : principal.userId();
    }
}
