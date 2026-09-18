package ai.camphub.workspace.domain;

import java.time.Instant;

/**
 * 邀请的状态。
 *
 * <h2>为什么"过期"不在这里面</h2>
 * 过期是由 {@code expires_at} 与当前时间比较得出的结论，不是需要被写入的状态。
 * 若把它也做成一个状态值，就必须有一个定时任务去把行从 {@code PENDING} 改成
 * {@code EXPIRED} —— 而这个任务一旦停摆，判断就会错。
 * 与 {@code user_credential.locked_until} 的既有取舍一致：<b>用时间表达时间</b>。
 *
 * <p>刻意没有 {@code REJECTED}：被邀请人拒绝邀请不需要在其中留下记录。
 * 一条 PENDING 且未过期的邀请本来就什么都不代表（被邀请人也是唯一能兑换它的人），
 * 增加"拒绝"只会让邀请列表多出一种需要展示但无人关心的状态。
 */
public enum InviteStatus {

    /** 待兑换。 */
    PENDING,

    /** 已兑换；{@code accepted_at} 有值。 */
    ACCEPTED,

    /** 已撤销（由邀请人或管理员撤回）。 */
    REVOKED;

    /**
     * 判断这条邀请此刻是否可被兑换。
     *
     * @param expiresAt 到期时间
     * @param now       当前时间
     * @return 状态为 PENDING 且尚未过期
     */
    public boolean isRedeemableAt(Instant expiresAt, Instant now) {
        return this == PENDING && expiresAt.isAfter(now);
    }
}
