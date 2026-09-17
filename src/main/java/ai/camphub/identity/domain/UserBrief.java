package ai.camphub.identity.domain;

/**
 * 用户简介：其他模块能看到的<b>最小</b>用户信息集合。
 *
 * <h2>为什么不让其他模块直接用 {@link User}</h2>
 * 有两个理由，第二个更重要：
 * <ol>
 *   <li>展示一条帖子只需要昵称与头像，把 {@code email}、{@code tokenVersion} 一并递出去，
 *       等于给了每个调用方一个"顺手把邮箱也返回给前端"的机会。字段越少，误用面越小。</li>
 *   <li>这是一个<b>显式的模块契约</b>。当它被单独定义出来时，community 依赖的是
 *       "identity 承诺提供的这几个字段"，而不是 identity 内部那张表的形状 ——
 *       后者一旦加列改名，所有模块都要跟着改。</li>
 * </ol>
 *
 * <p><b>它不包含邮箱、简介之外的联系方式，也不包含账号状态</b>：
 * "这个用户是否被封禁"属于 identity 的判断，其他模块不应该自己看到状态位然后各自决定
 * 怎么处理。需要这类判断时，应由 identity 提供一个回答问题的方法，
 * 而不是把原始状态位暴露出去让各模块各写一遍。
 *
 * @param userId    自增主键，用于与业务表中的 {@code author_id} 对齐
 * @param publicId  对外标识，用于拼个人主页链接；<b>不要把 userId 暴露给前端</b>
 * @param nickname  展示名
 * @param avatarUrl 头像地址，可为空（前端需处理空值，不要假设一定有）
 */
public record UserBrief(
        long userId,
        String publicId,
        String nickname,
        String avatarUrl
) {
}
