# Phase 01–03 代码质量复查与修复

日期：2026-09-18。范围：工程基础、身份认证、社区及其前端；不实施 Phase 04。
基线：`79d8329`；工作分支：`fix/phase-01-03-quality`。本轮为未发布修复，不改写既有版本 tag。

## Implemented

代码整体遵循按业务域分层、构造器注入、不可变 DTO、Flyway 迁移、统一错误响应及小步提交。
主要不足在于边界条件、并发状态和测试覆盖，而非需要替换架构。

| 问题 | 改进与已实施结果 | 依据 |
|---|---|---|
| 同一刷新令牌并发轮换可生成多条链 | 检查条件撤销影响行数，仅竞争成功者签发后继 | `RefreshTokenService.rotate`；确定性旧快照单测 + 并发 HTTP 集成测试 |
| JWT 必需声明未明确要求 exp | 缺 exp 拒绝为 40102 | `SecurityConfig`；同密钥签发缺 exp 的令牌后请求受保护接口 |
| 网络、限流、服务端故障触发误登出 | 暂时故障继续抛出实际错误，确认凭据失效才清状态 | `auth.ts` + `http.ts`；前端回归测试 |
| 刷新与登出、其他标签页存在竞争 | 同页合并、Web Locks、storage 同步及会话修订号 | `auth.ts`；迟到刷新不恢复已登出状态的测试 |
| 帖子删除后仍可通过评论 ID 读回复 | 评论查询同时要求所属帖子未删除 | `CommentMapper.xml`；删除前 200、删除后 404 的 HTTP 测试 |
| 大页码 OFFSET 溢出、截断 size 后 OFFSET 不一致 | long 运算、使用有效 size；相同评论时间戳按 ID 排序 | `FeedQuery`、两个 Service 与 Mapper；四种分页接口边界测试 |
| 限流器上限只是清理触发值 | 真正限制一万条键，容量满时拒绝新来源，各桶使用自身到期时间 | `AuthRateLimiter`；容量、跨桶清理、独立配额测试 |
| 架构测试没有完整覆盖规约中的边界 | 增加跨模块持久层隔离、领域层不得反向依赖外层 | `ModuleBoundaryTest`，共九条规则 |
| 首页仍展示 Phase 01 工程说明 | `/` 进入社区；`/system` 独立展示真实运行信息 | Router、AppLayout、SystemStatusView |

## Architecture Decisions

保留模块化单体及 MySQL。令牌轮换利用既有条件 UPDATE，不引入分布式锁或 Redis。
限流器只在内存 Map 操作期间加锁，保证准入上限；不在锁内执行数据库或网络操作。
前端使用已有 Vite 编译实际 TS 模块，再使用 Node 自带测试运行器，避免为七个回归场景增加框架依赖。
本轮未改变公共 API 响应结构。评论归属帖子检查属于同模块 SQL，不跨模块读表。

## Git History

| Commit | 内容 |
|---|---|
| `22c31e8` | security(identity): enforce single-use refresh rotation and token expiration |
| `92a8b78` | fix(web): preserve sessions on transient refresh failures and coordinate tabs |
| `1c13b2b` | fix(community): hide deleted post discussions and prevent pagination overflow |
| `0a64610` | fix(identity): bound rate limiter memory and preserve bucket expiry |
| `4d4f0f9` | test(architecture): enforce module-private persistence and domain boundaries |
| `477fb6f` | fix(web): make community the home page and separate system status |

交付报告、README、CHANGELOG 与架构文档另行提交。未推送、合并或打发布 tag。

## Database Changes

无新增迁移，未修改 V1–V4。原有数据保留，项目 MySQL 与 desk 容器均保留运行。
测试使用 Testcontainers 隔离 MySQL，不在开发库中构造攻击用例。

## API

无新增 API。以下行为修正：并发 `/auth/refresh` 仅允许一次成功；
无 exp 的访问令牌拒绝；已删除帖子下的 `/comments/{id}/replies` 返回 404；
大页码返回空结果而非整数溢出导致的错误。
前端 `/` 重定向 `/community`，运行状态改为 `/system`。

## Tests

实际运行：

```text
./mvnw -B clean verify
[INFO] Tests run: 108, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:05 min
```

合计 193 项后端测试（原 182 项，新增 11 项）。日志位于本机 `/tmp/campushub-quality-verify.log`。

```text
npm test
ℹ tests 7
ℹ pass 7
ℹ fail 0

npm run build
> vue-tsc --noEmit -p tsconfig.app.json
✓ built in 1.34s
```

前端测试已接入 CI。本机采用 Codex 提供的 Node 和已安装的 npm CLI；
ChatGPT 自带的签名 Node 无法加载本项目 Rollup 原生扩展，换用兼容运行时后构建通过。
限流单测初次因沙箱禁止 Mockito JVM attach 而失败，完整权限环境复验通过；完整验证同样通过。

`git diff --check` 通过。项目尚未配置独立 ESLint / Checkstyle，不能把类型检查和架构测试表述为 lint 已通过。

运行验证：项目 MySQL healthy，后端 `/actuator/health` 返回 UP；
真实浏览器访问 `/` 自动进入社区且显示数据库内容，点击「运行状态」进入 `/system`，
显示版本 0.3.0、数据库基线 V4 和服务端时间。
演示账号 demo01 登录成功，点击登出后返回社区并恢复「登录」入口；浏览器控制台未见 error。

## Security

修复一次性刷新令牌并发分叉和缺少过期声明；删除内容的回复入口遵循相同可见性。
保留现有 Spring Security、密码哈希、统一错误模型、审计与来源 IP 信任策略。
未新增真实凭据，未修改 .env，未改变生产访问权限。

## Performance

Not benchmarked yet。本轮未重跑查询基线或压力测试，不宣称性能提升。
新增评论 JOIN 使用所属 post 主键；限流满容量扫描最多一万条，仍需后续压力测试评估。

## Known Limitations

- 本轮是针对性代码审查，不是“全部安全风险已消除”的证明。
- Refresh Token 仍存 localStorage，后续应将 HttpOnly Cookie 与 CSRF 整体迁移。
- 无 Web Locks 支持时不能保证跨标签页串行；会话恢复仍需处理浏览器兼容性。
- 前端自动化测试覆盖认证逻辑与 HTTP 重试，不覆盖所有 Vue 组件交互。
- 未在 Windows 实机验证；无新增平台专用的核心构建命令。
- 既有演示种子在中途失败后无法完整恢复；本轮未清空开发库或重写种子模型。
- Phase 04 仍未实现。

## Technical Debt

建议下一轮按优先级处理：

1. Cookie + CSRF 凭据方案、跨标签页异常恢复；需要共同设计端到端协议并覆盖迁移。
2. 对演示种子引入可恢复的批次标记，避免“已有任意帖子就跳过”的不完整初始化。
3. 给前后端建立独立 lint / 格式化门禁，先限定配置与渐进修正范围，避免一次性格式噪声。
4. 清理旧阶段文档与注释的重复叙述，包括旧版本映射；让注释集中解释约束及取舍。

## Next Phase Dependencies

Phase 04 应基于本轮稳定后的认证及边界规则实现 Workspace / Member / Role / Note / Document Metadata，
并补齐跨用户读取、修改、删除、下载攻击测试及资源鉴权文档。
本次用户授权为 Phase 01–03 检查修复，因此不提前实现这些能力。
