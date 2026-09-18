package ai.camphub.workspace.api;

import ai.camphub.common.error.BusinessException;
import ai.camphub.common.error.ErrorCode;
import ai.camphub.workspace.domain.WorkspaceVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建或修改空间的请求体。
 *
 * <h2>为什么 {@code visibility} 是字符串而不是枚举</h2>
 * 声明成枚举会让 Spring 的转换器按<b>大小写精确</b>匹配做绑定，失败时抛出的是一句
 * 与技术细节绑定的错误（{@code Failed to convert 'A' ...}），而且它对
 * {@code WorkspaceVisibility.parse} 里已经写好的"空白视为 PRIVATE、无法识别时拒绝"
 * 这套规则一无所知 —— 同一件事就有了两个实现，且只有一个会跑。
 *
 * <p>用字符串把它交给领域枚举的 {@code parse}，是让"取值域与默认值"只有一处定义。
 * 无法识别的取值由 {@link #toVisibility()} 翻成 {@code 40001}，
 * 而不是一个由框架生成的、形状不同的错误。
 *
 * <h2>长度上限写在两处，是有意的</h2>
 * 这里的 {@code @Size} 是<b>格式约束</b>（字段级，与产品策略无关），
 * 服务层的校验读的是配置里的 {@code app.workspace.limits.*}（<b>产品策略</b>，
 * 会随压测与体验调整）。两者当前的数值相同，但它们的上限来源不同：
 * 注解里写的是数据库列的硬上限，配置里写的是产品愿意接受的上限，后者永远 ≤ 前者。
 * 若将来把配置调大却忘了列宽，插入会报错，这正是我们想要的失败方式 ——
 * 而不是框架先抛出一句让人去查注解的"字段超长"。
 *
 * <h2>字段与列的对应（改数据库时唯一会看的对象）</h2>
 * <ul>
 *   <li>{@code name} → {@code workspace.name VARCHAR(80)}（注解上限 80）</li>
 *   <li>{@code description} → {@code workspace.description VARCHAR(500)}（注解上限 500）</li>
 *   <li>{@code visibility} → {@code workspace.visibility VARCHAR(16)}，取值 {@code PRIVATE | TEAM}</li>
 * </ul>
 *
 * @param name        空间名称
 * @param description 空间描述，可为 null
 * @param visibility  可见性，留空时按 PRIVATE；取值不区分大小写
 */
public record WorkspaceRequest(
        @NotBlank(message = "空间名称不能为空")
        @Size(max = 80, message = "空间名称最多 80 个字符")
        String name,

        @Size(max = 500, message = "空间描述最多 500 个字符")
        String description,

        String visibility
) {

    /**
     * 解析可见性。
     *
     * @return 可见性；未指定时为 {@code PRIVATE}
     * @throws BusinessException 取值无法识别时 {@code 40001}
     */
    public WorkspaceVisibility toVisibility() {
        return WorkspaceVisibility.parse(visibility)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST));
    }
}
