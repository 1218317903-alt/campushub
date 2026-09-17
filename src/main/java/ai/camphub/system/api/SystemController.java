package ai.camphub.system.api;

import ai.camphub.system.app.SystemInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统信息接口。
 *
 * <p>当前为公开接口（无需登录）：它只暴露版本号与 schema 基线这类非敏感事实，
 * 便于部署后立刻确认"服务起来了、数据库迁移也生效了"。
 *
 * <p>注意：<b>不要</b>把配置项、连接串、依赖地址等加入本接口。后续如需暴露运行细节，
 * 应走需要管理员权限的独立端点，而不是放宽这个公开端点的内容。
 */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "系统信息与健康状态")
public class SystemController {

    private final SystemInfoService systemInfoService;

    /**
     * 构造注入。
     *
     * @param systemInfoService 系统信息服务
     */
    public SystemController(SystemInfoService systemInfoService) {
        this.systemInfoService = systemInfoService;
    }

    /**
     * 查询当前实例信息。
     *
     * @return 系统信息
     */
    @GetMapping("/info")
    @Operation(summary = "查询当前实例信息",
            description = "返回应用版本、生效 profile、Java 版本与数据库 schema 基线，用于部署后自检。")
    public SystemInfoResponse info() {
        return systemInfoService.describe();
    }
}
