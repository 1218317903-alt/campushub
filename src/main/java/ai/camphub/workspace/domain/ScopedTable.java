package ai.camphub.workspace.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 Mapper 方法，声明它的 SQL 必须被自动限制在"调用者被授权的空间"范围内。
 *
 * <h2>它是第三层防线（docs/03 §10.3）的开关</h2>
 * 被标注的方法，其执行的 SQL 会由 {@code WorkspaceScopeInterceptor} 在
 * {@code StatementHandler.prepare} 阶段就地追加一条
 * {@code AND <column> IN (<调用者被授权的空间主键>)}；调用者一个空间都没有时
 * 追加 {@code AND 1 = 0}，结果是空集而不是全部。
 *
 * <h2>为什么标在方法上，而不是标在表上</h2>
 * 原始设计（docs/03 §10.3）的表述是"对带 {@code workspace_id} 的表自动追加"。
 * 按表自动生效有一个致命问题：<b>并非所有写到这张表的查询都应该被这样限制</b>。
 * 兑换邀请码就是反例 —— 被邀请人在兑换成功之前<b>还不是</b>空间成员，
 * 若那条查询也被限制成"只能看到自己已加入的空间"，邀请码将永远无法兑换，
 * 而这个 bug 只在"邀请一个新人"时出现，日常测试（邀请已有成员）发现不了。
 *
 * <p>标在方法上之后，"这条查询该不该被限制"变成每个查询都必须显式回答的问题；
 * 而"有没有人忘了回答"由 {@code WorkspaceScopeCoverageTest} 在构建期强制 ——
 * 它枚举全部 Mapper 方法，凡是 SQL 里出现私有表的，必须要么标注本注解、
 * 要么标注 {@link Unscoped} 并写明理由。
 *
 * <h2>为什么用列名而不是固定 {@code workspace_id}</h2>
 * {@code workspace} 表自己也要被限制（否则任何人都能按 public_id 读到别人的空间），
 * 而它的主键列叫 {@code id} 而不是 {@code workspace_id}。默认值覆盖绝大多数情形，
 * 个别表用 {@link #column()} 覆盖。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ScopedTable {

    /**
     * 需要被限制到"调用者已授权空间集合"的列名。
     *
     * @return 列名，默认 {@code workspace_id}
     */
    String column() default "workspace_id";
}
