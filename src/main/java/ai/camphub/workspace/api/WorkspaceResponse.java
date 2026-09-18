package ai.camphub.workspace.api;

import ai.camphub.workspace.app.WorkspaceSummary;
import ai.camphub.workspace.domain.Workspace;
import ai.camphub.workspace.domain.WorkspaceRoleInContext;
import java.time.Instant;

/**
 * 空间的对外表示，附带调用者在其中的身份。
 *
 * <h2>为什么带 {@code myRole}</h2>
 * 界面上的每一个操作按钮都取决于调用者在这个空间里的身份：拥有者看到"空间设置"与
 * "删除空间"，管理员看到"邀请成员"，普通成员两样都看不到。没有这个字段，
 * 前端就必须再请求一次成员列表、在其中找到自己 —— 那既是一次额外往返，
 * 也表现为"按钮先出现、半秒后才消失"。
 *
 * <p>它<b>不是</b>权限判定的替代：第二层防线仍然逐次判定，这个字段只是让界面不必去猜。
 * 即便客户端伪造它也毫无收益 —— 它只影响按钮显不显示，判定发生在服务端。
 *
 * <h2>为什么没有 {@code owner} 字段</h2>
 * 加上它意味着列表接口要为每一行查一次拥有者的昵称，也就是一个页面级 N+1。
 * 而"谁是拥有者"在成员列表里已经回答了 —— 它排在第一行，角色是
 * {@link WorkspaceRoleInContext#OWNER}。与其在两处回答同一个问题，
 * 不如让详情页从成员列表里读。
 *
 * <h2>字段与列的对应</h2>
 * {@code publicId} / {@code name} / {@code description} / {@code visibility} /
 * {@code createdAt} / {@code updatedAt} 一一对应 {@code workspace} 表的同名列；
 * {@code myRole} 是算出来的，库里没有对应列。
 *
 * @param publicId    空间对外标识
 * @param name        名称
 * @param description 描述，可为 null
 * @param visibility  可见性
 * @param myRole      调用者在该空间内的身份
 * @param createdAt   创建时间
 * @param updatedAt   最后修改时间
 */
public record WorkspaceResponse(
        String publicId,
        String name,
        String description,
        String visibility,
        String myRole,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 从应用层视图构造。
     *
     * @param summary 空间与身份
     * @return 响应
     */
    public static WorkspaceResponse from(WorkspaceSummary summary) {
        Workspace workspace = summary.workspace();
        return new WorkspaceResponse(
                workspace.publicId(),
                workspace.name(),
                workspace.description(),
                workspace.visibility().name(),
                summary.myRole().name(),
                workspace.createdAt(),
                workspace.updatedAt());
    }
}
