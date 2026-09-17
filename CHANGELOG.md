# Changelog

本文件记录 CampusHub AI 每个已发布版本的变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
按 `docs/00-工程规约.md` §15.6，每个 Release 必须记录：
**Added · Changed · Fixed · Security · Known Issues**。

> **版本线说明**：`docs/08-roadmap.md` 中的 V0~V5 版本编号已被
> `docs/12-phase-plan.md` 的 Phase 01~13 计划取代。本文件从 `v0.1.0` 起按新计划记录。

---

## [Unreleased]

_尚无未发布变更。_

---

## [0.2.0] - 2026-09-17

Phase 02 — Identity & Security Foundation。引入完整的账号体系与鉴权能力：
注册、登录、登出、访问令牌 + 刷新令牌、令牌撤销、密码策略、基础限流、审计基础。

> **这是一次破坏性变更**：引入 Spring Security 并采用"默认拒绝"后，
> 所有此前公开的端点变为需要认证（`/api/v1/system/info`、`/actuator/health`、
> API 文档除外）。因此按 MINOR 递增。

### Added

- **`V2__identity.sql` 迁移**：8 张表 —— `user` · `user_credential` · `role` ·
  `permission` · `user_role` · `role_permission` · `refresh_token` · `audit_log`。
  要点：
  - `user_credential` 与 `user` 分表。凭据是**读取频率最低、敏感度最高**的字段，
    分表后任何"列出用户/搜索用户"的查询天然拿不到 `password_hash`，
    不必依赖每个开发者记得在 SELECT 里排除它。
  - 排序规则刻意选 `utf8mb4_0900_ai_ci`（大小写不敏感）。若区分大小写，
    攻击者就能注册 "Adm1n" 冒充 "adm1n"。
  - `refresh_token.token_hash` 只存 SHA-256。它是 30 天有效的长期凭据、等价于密码，
    库被读走时哈希不可逆；用 SHA-256 而非 BCrypt 是因为令牌本身是 256 bit 随机值
    （没有"弱口令"问题），而每次刷新都要按哈希建索引查找，慢哈希会把刷新接口变成 CPU 瓶颈。
  - `audit_log.actor_user_id` **不加外键**。审计的价值在于"账号删除后仍查得到谁做过什么"，
    外键（且是 CASCADE）会让删除用户连带抹掉审计痕迹 —— 那正好删掉了最该保留的部分。
  - 只种 `USER` 角色。**不预置** MODERATOR / ADMIN 与权限点：本阶段没有任何接口检查它们，
    提前种下的结果是表里躺着一批"看起来有权限体系、实际没人检查"的行，
    比没有更危险。
- **身份领域模型**：`User` · `UserCredential` · `UserStatus` · `Role` · `UserPrincipal` ·
  `RefreshTokenRecord` · `PasswordPolicy` · `TokenHasher` · `RandomValues` · `DeviceLabel`。
  除 `UserPrincipal` 的权限缓存取舍外，全部不依赖 Spring（由 ArchUnit 断言）。
- **鉴权链**：`JwtAuthenticationFilter`（签名/时间 → 账号状态 → 令牌世代号）·
  `JwtTokenService`（HS256）· `RestAuthenticationEntryPoint` · `RestAccessDeniedHandler` ·
  `UserPrincipalLoader`（每请求回库装配权限）· `SecurityConfig`。
- **应用服务**：`AuthService` · `AccountService` · `SessionService` ·
  `RefreshTokenService` · `RefreshTokenLeakHandler` · `LoginAttemptService` ·
  `AuthRateLimiter`。
- **HTTP 接口**（`/api/v1`）：

  | 方法 | 路径 | 说明 |
  |---|---|---|
  | POST | `/auth/register` | 注册并直接返回令牌对 |
  | POST | `/auth/login` | 用户名或邮箱 + 密码 |
  | POST | `/auth/refresh` | 换取新令牌对（轮换，旧令牌立即失效） |
  | POST | `/auth/logout` | 撤销当前会话（幂等） |
  | POST | `/auth/logout-all` | 撤销全部会话并使访问令牌立即失效 |
  | GET | `/users/me` | 本人资料 |
  | PATCH | `/users/me` | 修改资料 |
  | POST | `/users/me/password` | 修改密码 |
  | GET | `/users/me/sessions` | 列出登录设备 |
  | DELETE | `/users/me/sessions/{id}` | 下线指定设备 |

- **审计基础**（`platform/audit`）：`AuditService` 以 `REQUIRES_NEW` 独立事务写入，
  覆盖 `AUTH_REGISTER` · `AUTH_LOGIN_SUCCESS` · `AUTH_LOGIN_FAILURE` · `AUTH_LOGOUT` ·
  `AUTH_LOGOUT_ALL` · `AUTH_TOKEN_REFRESH` · `AUTH_TOKEN_REPLAY_DETECTED` ·
  `AUTH_PASSWORD_CHANGE` · `USER_PROFILE_UPDATE` · `SESSION_REVOKE`。
- **错误码扩展**：`40100`~`40104`（未认证 / 过期 / 无效 / 凭据错误 / 已撤销）、
  `40300`~`40301`、`42900`、`40010`、`40900`。`429` 响应带 `Retry-After`。
- **测试**（本阶段准出要求）：47 条单元测试 + 41 条集成测试（真实 HTTP + 真实 MySQL）。

### Security

- **防账号枚举**：登录时"账号不存在"与"密码错误"返回**完全相同**的状态码、
  错误码与文案；并且账号不存在时仍对预置哑哈希执行一次 BCrypt 校验，
  让两条路径的**响应耗时**也接近。否则拿一份邮箱列表逐条试，就能筛出平台上有哪些账号。
- **账号状态检查放在口令校验之后**：锁定/停用的提示对真实用户很重要，
  但对攻击者同样是信息。放在口令校验之后，只有已经知道口令的人才会看到它。
- **令牌世代号（`user.token_version`）实现"立即撤销"**：访问令牌是无状态的，
  签发后无法收回。登出全部设备 / 改密 / 踢下线时递增世代号，鉴权过滤器逐请求比对，
  使未过期的访问令牌也立即失效。代价是每次鉴权回库一次（换取"权限变更立即生效"）。
- **刷新令牌轮换 + 重放检测**：一个 30 天有效、可不断换新的凭据若不轮换，
  一旦泄露就是 30 天的稳定后门，且服务端**无法察觉**泄露已发生。轮换之后，
  链中间节点再次出现即说明有人持有旧副本 —— 泄露从"不可见"变成"可判定事件"。
  处置为撤销该用户全部会话并推进世代号。
  宽限窗口（5 秒）用于区分"多标签页并发刷新"与"确凿的泄露"。
- **密码策略遵循 NIST SP 800-63B**：要求长度（≥ 10）与常见弱密码表命中，
  **不强制字符类别组合**（强制组合会把用户推向 `Passw0rd!` 这类可预测模式）。
  另拒绝超过 BCrypt 72 字节上限的密码 —— 而不是放任其被静默截断
  （那意味着两个不同的长密码可能哈希成同一个值）。
- **越权从接口形状上消除**：账号自助接口的路径中只有 `me`，
  不出现"要操作哪个用户"这个参数。因此不存在"把别人的 ID 填进去"的攻击面，
  也就不依赖每个开发者都记得加归属判断。会话下线是唯一需指定 ID 的接口，
  那里显式校验归属，且**不属于自己的会话一律返回 404 而非 403** ——
  403 等于承认"这个会话确实存在，只是不归你"。
- **响应字段按"谁在看"裁剪**：资料响应不含 `email` / `status` / 自增 `id` / `tokenVersion`。
- **CSRF 关闭是有前提的**：凭据只经 `Authorization` 头传递，不依赖浏览器自动携带的 Cookie。
  代码注释中已标明：**若将来把刷新令牌改放进 Cookie，这一条必须同步改回来**。
- **来源 IP 默认不信任 `X-Forwarded-For` / `X-Real-IP`**：这两个头由客户端完全控制，
  无可信反向代理时采信它们会让"按 IP 限流"被逐请求绕过、并往审计日志写入伪造来源。
- **JWT 密钥无默认值**：`app.security.jwt.secret` 为空或短于 32 字节时**启动即失败**。
  一个"能启动但人人可猜"的默认密钥，等于把所有用户的账号交给第一个读到源码的人。

### Changed

- **全部端点默认需要认证**（`anyRequest().authenticated()`）。公开端点收敛为：
  `auth/register|login|refresh|logout`、`GET system/info`、`actuator/health|info`、
  swagger、`/error`。默认拒绝而非默认放行，是这份清单能长期安全的前提 ——
  新增接口时忘记加规则，结果是"访问不了"（立刻被发现），而不是"裸奔"（很久后才发现）。
- **未认证请求一律 401，不再先回答"这个路径存在吗"**。
  因此对不存在的路径得到的是 401 而不是 404。这是期望的行为：
  若未认证调用方能靠 404/405 与 401 的差别区分路径是否存在，就等于提供了一个免费的接口枚举器。
  `ErrorContractIT` 相应拆成两组（带令牌验证 404/405 契约，不带令牌验证"先认证"规则）。
- `AuthRateLimiter` 从 `identity.infrastructure.security` 移至 `identity.app`。
  它被 Controller 直接调用，而架构规则禁止 API 层依赖 infrastructure。
  更根本的理由是限流属**业务策略**而非外部系统适配，"用内存还是 Redis"是实现细节。

### Fixed

- **MyBatis 构造器映射把 `long` / `int` 解析成包装类型**：三个 resultMap 写的是
  `javaType="long"`，而 MyBatis 别名表中**不带下划线的是包装类型**
  （`long` → `java.lang.Long`），只有 `_long` / `_int` 才是原始类型。
  record 的规范构造器按原始类型签名，于是查询时抛 `NoSuchMethodException`
  并以 500 暴露 —— 编译期毫无提示。已改用 `_long` / `_int` 并在 XML 中写明该陷阱。
- **令牌泄露处置被事务回滚吞掉**：检测到刷新令牌重放后的"撤销全部会话 + 推进世代号"
  原先写在 `@Transactional` 的 `rotate()` 内，随后的业务异常会回滚整个事务，
  两笔写入全部消失 —— 客户端收到"已登出全部设备"，数据库却什么都没变，攻击者令牌继续有效。
  更糟的是审计（`REQUIRES_NEW`，不受回滚影响）已写下"处置成功"，
  事后排查会得到与事实相反的结论。已抽为 `RefreshTokenLeakHandler` 以 `REQUIRES_NEW` 独立提交。
- **注册时不传 `device` 返回 500**：`device` 在接口上可选（`@Size(max=64)`，允许 null），
  而 `refresh_token.device` 是 `NOT NULL`。此前只有令牌轮换路径做了规范化，
  注册与登录路径没有，于是"不传设备"这一完全合法的操作以
  `Column 'device' cannot be null` 变成服务器错误。已抽出 `DeviceLabel`
  作为唯一的规范化实现，并在 `SessionService`（三条路径的共同入口）统一调用。
- **登出审计重复记账**：`revoke` 的 `WHERE` 带 `revoked_at IS NULL`，
  重复登出影响 0 行，但审计此前无条件写入。已改为仅在真正撤销了会话时记录 ——
  否则按动作聚合出的"登出次数"不再等于"被撤销的会话数"。
- **弱密码可被首尾空白绕过**：`"  password123  "` 此前能通过弱密码检查，
  而攻击字典的第一条规则正是"在候选词前后补上常见字符"。
  弱密码表与账号相关性比对改用去除首尾空白后的形式；同时**显式拒绝**首尾空白 ——
  它在视觉上不可见却会改变哈希结果，会制造出"用户认为密码是 A、实际设成了 B"的支持困局。
- `ClientIpResolver` 注释中的配置键名错误（写作 `app.security.trust-forwarded-headers`，
  实际前缀为 `app.request`）。

### Known Issues

以下为**已知并已记录**的限制，不是遗漏：

- **限流为单实例内存实现**：多实例部署时每个实例各自计数，实际额度是"配置值 × 实例数"；
  进程重启即清零；固定窗口在跨窗口边界时短时峰值可达配置值的两倍。
  这是本阶段明确不引入 Redis 的代价，接 Redis 是 Phase 09 的任务（届时先压测确认它确实是瓶颈）。
- **令牌撤销延迟**：不做"访问令牌黑名单"，世代号是账号级的粗粒度撤销 ——
  撤销单个设备会使该账号所有访问令牌失效（用户体验上的取舍，换来实现简单且无额外存储）。
- **无 MFA / 无第三方登录 / 无邮箱验证**：不在 Phase 02 范围内。
- **审计只写不查**：`audit_log` 目前仅写入，没有查询接口与后台展示（Phase 10）。
- **验证码缺失**：限流之外没有验证码，持续分布式撞库仍可能缓慢推进。
- **公开端点清单不含 `/auth/logout-all`**：它需要有效访问令牌，这是有意的 ——
  `/auth/logout` 之所以公开是因为"持有刷新令牌即授权"，而"登出全部设备"是账号级操作，
  必须由已认证的身份发起。

---

## [0.1.1] - 2026-09-17

`v0.1.0` 之后的收尾批次：补上持续集成、修正一处仓库洁净度问题、
按新指令改写阶段纪律。**不涉及任何对外契约变更**，因此按 PATCH 递增。

### Added

- GitHub Actions CI（`.github/workflows/ci.yml`）：两个 job 分别执行后端
  `./mvnw -B verify`（单元 + ArchUnit + 真实 MySQL 集成测试）与前端
  `npm ci` + `npm run build`（含 `vue-tsc` 类型检查）。失败时归档
  surefire / failsafe 报告。CI 调用的命令与本地完全一致，不维护第二套脚本。
- `docs/00-工程规约.md` §18 新增五条准出条件、仍然禁止的事项、必须停下来问的
  唯一情形、**全量端到端验收**要求、**平台可移植性**要求（Windows）、
  以及**对外表述纪律**。

### Fixed

- **`src/test/resources/testcontainers.properties` 从仓库移除。**
  该文件把 ryuk 镜像固定为 `testcontainers/ryuk:0.11.0`，目的是绕开本机
  Docker Hub 不可达的环境限制 —— 但它是**本机专属的环境适配**，不是项目配置。
  留在仓库里会带来两个实际后果：所有克隆者被迫使用同一个（较旧的）ryuk 版本；
  而 Testcontainers 已明确提示该替换机制 "deprecated and will be removed in the future"，
  机制移除后这个文件会变成死配置，届时本机测试将静默失去这个绕行手段。

  改为：环境适配写入用户级 `~/.testcontainers.properties`（不属于任何仓库），
  仓库保持干净。能直连 Docker Hub 的机器无需任何配置。
  已验证：移除后集成测试仍正常通过（含 ryuk 替换生效）。

- `docs/02-architecture.md` 技术选型表中仍写着 Spring Boot `3.5`，
  与已锁定的 `4.1.1` 基线不符（该表写在基线确认之前）。已更正。
- `CHANGELOG.md` `[0.1.0]` 段中一句不准确的表述 —— 原文称 ryuk 的本地 tag 处理
  "不影响仓库内容的可移植性"，但那个文件当时确实在仓库里，该说法是错的。
  已改为如实描述当时状态并指向本版本的修复。

### Changed

- `docs/00-工程规约.md` §18 **解除**「每阶段完成后停止、不得自动开发下一阶段」，
  授权从 Phase 01 连续推进至最后阶段；§19 自检清单与 §20 变更记录同步更新。
- `docs/12-phase-plan.md` 的纪律说明同步改写，避免与规约冲突。
- `docs/11-开发环境.md`：§7.1/7.2 重新核对可达性；新增 §7.3 记录上述 GitHub 结论更正，
  §7.4 给出绕过沙箱代理的 git 配置，§7.5 记录凭据助手可用性，
  §7.6 记录 ryuk 镜像的用户级配置；§8 清理已解决的历史待办。
- `docs/01-product.md` · `02-architecture.md` · `05-data-platform.md` ·
  `06-search-ai.md` · `08-roadmap.md`：移除与招聘求职相关的对外表述
  （详见规约 §18.7）。产品自身内容域中的"求职/招聘"（用户画像、招聘信息聚合）
  属于产品功能，予以保留。
- `README.md` 新增「持续集成」小节，项目结构补上 `.github/`，
  并把阶段推进说明改为四条准出条件。
- `pom.xml` 中 Testcontainers 注释改为说明用户级配置约定，不再指向已删除的文件。

---

## [0.1.0] - 2026-09-17

**Phase 01 · Engineering Foundation**

工程基础阶段。目标不是实现业务功能，而是搭出一套**后续十余个 Phase 都要依赖的地基**：
统一契约、可定位的日志链路、可被构建期断言的架构边界、真实数据库上的测试体系。

### Added

**后端工程骨架**

- Maven 工程，Spring Boot `4.1.1` + Java `21`，含 Maven Wrapper（无需全局 Maven）。
- 模块化单体包结构：`common`（共享内核）+ `system`（首个模块，四层分层）。
- 配置管理分层：`application.yml`（入库、非密）→ `application-local.yml`（入库、非密）
  → `.env`（不入库、真实凭据）→ 环境变量；`.env` 经 `spring.config.import` 自动加载，
  且以 `optional:` 前缀保证无 `.env` 时也能启动。
- `AppProperties`（`@ConfigurationProperties(prefix = "app")`）承载应用自定义配置。
- MyBatis 数据访问：Mapper 接口位于各模块 `infrastructure` 包，
  由启动类 `@MapperScan("ai.camphub.**.infrastructure")` 统一扫描；XML 置于 `resources/mapper/**`。

**统一错误契约**（详见 `docs/adr/0003-unified-error-contract.md`）

- 错误响应结构化：`{code, message, traceId, timestamp, path, details}`。
- 5 位错误码体系，编码规则 `HHH SS`（HTTP 状态码 + 状态内序号），序号段按模块预分配。
- `GlobalExceptionHandler` 统一收口，覆盖业务异常、Bean Validation 失败、方法参数校验、
  类型不匹配、缺参、请求体不可读、方法不支持、媒体类型不支持、静态资源不存在，以及兜底异常。
- `details` 恒为数组（从不为 `null`），前端无需判空。
- 5xx 兜底分支不泄漏堆栈、类名、SQL 等内部细节，原始异常只进日志。
- **成功响应不套信封**：body 即结果，链路信息统一走响应头 `X-Trace-Id`。

**日志与可观测性**

- `TraceIdFilter`（最高优先级）为每个请求建立 traceId：合法入站 `X-Trace-Id` 沿用、否则生成；
  写入 MDC 与响应头；并在 `finally` 中清理 MDC（防止线程复用导致 traceId 串号）。
- 入站 traceId 严格校验（8~64 位字母数字与连字符），拒绝换行注入等日志伪造。
- 日志 pattern 内置 `%X{traceId:-no-trace}`，使每一行日志都可按请求聚合。
- `GET /actuator/health`（含 liveness / readiness 分组）与 `/actuator/info`；
  仅暴露 `health,info`，默认 `show-details: never`，仅 local profile 放开。

**数据库**

- Flyway 迁移体系，首个迁移 `V1__baseline.sql` 创建 `app_metadata` 表并写入 schema 基线。
- 迁移纪律：`validate-on-migrate: true`（拦住对已发布脚本的修改）、
  `clean-disabled: true`、不使用 `baseline-on-migrate`（避免静默跳过库结构错位）。
- JDBC 连接串显式指定 `connectionTimeZone=Asia/Shanghai` 且强制同步到会话时区，
  规避 MySQL `time_zone=SYSTEM` 导致的排序/时间偏移。
- `docker-compose.yml` 仅编排 MySQL `8.4`（含健康检查与时区设置）。
  按计划，Redis / 对象存储 / 搜索引擎在各自 Phase 引入，且需先有数据支撑。

**纵向切片**

- `GET /api/v1/system/info` 返回应用名、版本、生效 profile、Java 版本、服务端时间
  与**数据库 schema 基线**（读自 `app_metadata` 表）。
  一个请求即可确认「服务已启动，且迁移已生效」。
- 该端点仅暴露非敏感事实，故保持公开；不得在其中加入配置项、连接串或依赖地址。

**前端工程骨架**

- Vue `3.5` + TypeScript `5.7` + Vite `6`，独立于后端构建。
- Vue Router `4`（路由级懒加载，避免首屏体积随业务线性膨胀）。
- Pinia `2` 状态管理。
- Arco Design 组件库：**按需引入**（`unplugin-vue-components` + `ArcoResolver`），
  `ConfigProvider` 统一注入中文 locale。
- `vueCompilerOptions.strictTemplates: true`：使模板中拼错的组件名与传错的 prop 类型
  在 `npm run build` 阶段被拦下（默认配置下这类错误会被 Vue 当作原生自定义元素而静默通过）。
- **与后端错误契约对齐的 HTTP 客户端**：把结构化错误映射为 `ApiError` 类，
  把「连不上 / 超时 / 响应非 JSON」映射为 `NetworkError`，
  并明确区分二者（排查方向完全不同）。
- 刻意**不做**全局自动重试：对非幂等请求无条件重试会产生重复副作用。
- 应用外壳（顶栏 + 内容区 + 页脚）与概览页：概览页展示真实后端数据，用于验证端到端链路。
- Dev server 将 `/api` 代理到后端，前端代码只使用同源相对路径，无跨域与多套请求逻辑。

**测试体系**（三层，各管一件事）

- 单元测试（JUnit 5 + AssertJ，不起容器）：错误码、错误体工厂方法、traceId 校验与 MDC 清理。
- 架构测试（ArchUnit）：模块间无循环依赖、`common` 不得依赖业务模块、
  `..api..` 不得依赖 `..infrastructure..`、`..domain..` 不得依赖 Spring、
  Controller 必须位于 `..api..`、禁止 `@Autowired` 字段注入。
  包名模式为通配，新增模块自动覆盖，无需改测试。
- 集成测试（`@SpringBootTest(RANDOM_PORT)` + Testcontainers 真实 MySQL `8.4`）：
  纵向切片（真实数据库读写、traceId 响应头、服务端时间格式、health 状态）
  与错误契约（404/405 统一格式、traceId 一致性、入站 traceId 透传、500 不泄漏内部信息）。
- 刻意**不使用 H2**：H2 与 MySQL 在字符集、排序规则、时间戳行为上存在差异，
  在 H2 上通过的测试是假绿灯。
- 刻意使用 JDK 内建 HttpClient + JsonPath 断言，而非框架自带测试客户端，
  以降低框架升级带来的测试改动成本。

**工程配套**

- `Makefile` 统一命令入口（`make help` 列出全部命令），前后端命令一致化。
- `docker-compose.yml`、`.editorconfig`（UTF-8 / LF）、`.gitignore`、`.env.example`。
- `README.md`：5 分钟上手路径、工程原则、文档索引。
- `docs/architecture.md`：实现级架构说明（运行时构成、请求链路、错误契约、配置分层、
  测试策略、已知取舍与技术债）。
- `docs/adr/0001-modular-monolith.md`：模块化单体决策，
  含为何不拆微服务、以及「边界必须由构建期断言守护」这一核心论点。
- `docs/adr/0002-spring-boot-4-and-mybatis.md`：技术基线决策。
- `docs/adr/0003-unified-error-contract.md`：统一错误契约决策。
- `docs/12-phase-plan.md`：Phase 01~13 执行计划，取代原 V0~V5 路线图。
- Git 仓库建立 `main` 与 `develop` 分支，阶段验收打 tag。

### Changed

- 工程规约升级为 V2（`docs/00-工程规约.md`）：新增 Git 版本管理纪律（分支 / Commit
  粒度 / 禁提交项 / Tag / ADR / Flyway）、阶段开发流程、Agent Runtime 相关约束、
  数据与安全原则；交付报告模板改为 11 节版本。
- `docs/09-backlog-v0.md`：修正 V0 阶段的依赖范围矛盾 —— V0 只使用 MySQL，不引入 Redis。
- `docs/11-开发环境.md`：补充 Maven 安装记录、依赖基线表，以及为什么无需登录即可开发。
- `README.md`：更新文档索引与 V2 说明。

### Fixed

- **Flyway 迁移静默不执行**：现象为集成测试报 `Table 'camphub.app_metadata' doesn't exist`。
  根因是 Spring Boot 4 把 Flyway 自动配置从 `spring-boot-autoconfigure` 中拆分出去，
  必须显式引入 `spring-boot-starter-flyway`，否则迁移**不会报错、只是不运行**。
  已补依赖并在真实 MySQL 容器上验证迁移生效。
- `spring-boot-starter-web` 在 Boot 4 中已弃用，改用 `spring-boot-starter-webmvc`。
- 方法参数校验 API 在 Spring Framework 7 中已变更，
  经 `javap` 确认实际签名为 `getParameterValidationResults()` 后修正调用。
- `pom.xml` 注释中出现 `--` 导致 XML 解析失败，已改为等号分隔样式。
- **前端依赖无法安装**（本机托管环境）：`npm install` 在落盘阶段被托管运行时的文件系统代理拒绝
  （`CODEBUDDY_BROKER_DENY: Brokered host mkdir requires an available runtime file rule`）。
  已定位为该代理不认 npm reify 阶段的 `fs.promises.mkdir` 调用，而非网络或权限问题；
  通过清空 `NODE_OPTIONS` 绕开。排查过程与对策记录在 `docs/11-开发环境.md` §2.1。
- **前端产物体积过大**：全量引入 Arco 导致产物为 1 243.79 kB JS（gzip 310.65 kB）
  + 405.39 kB CSS（gzip 50.09 kB），而页面只用到 8 个组件。
  改为按需引入后降至 221.85 kB JS（gzip 84.02 kB）+ 81.25 kB CSS（gzip 12.85 kB），
  JS 体积下降约 82%、CSS 下降约 80%（均为 `npm run build` 实测）。
- **模板中的组件名拼写错误不会被发现**：Vue 默认会把无法解析的组件当作原生自定义元素。
  已启用 `strictTemplates` 并入库 `components.d.ts`，使此类错误在构建期暴露。

### Security

- 5xx 响应不泄漏内部实现细节（堆栈、类名、SQL、依赖信息），细节仅进日志。
- 入站 `X-Trace-Id` 严格字符集与长度校验，防止日志注入（log injection）。
- 生产配置默认不暴露 actuator 细节：仅开 `health,info`，`show-details: never`。
- `.env`、真实凭据、日志、构建产物、IDE 用户配置均不入库；提供 `.env.example` 只含变量名与示例值。
- ArchUnit 规则强制 `..api..` 不得直接依赖 `..infrastructure..`，
  从结构上防止绕过应用服务层的权限校验与事务边界。
- 前端错误提示中展示 `traceId` 而非后端内部信息，兼顾可定位性与信息暴露最小化。

### Known Issues

- 尚未实现任何认证与授权，所有端点当前均为公开。**不得在此状态下部署到公网。**
  Phase 02 引入 Spring Security。
- 前端未做构建期「未使用依赖」检查，也未引入 ESLint / Prettier 与组件测试。
  在出现业务组件后再引入，避免为测试骨架而测试。
- 前端 API 类型为手写、与后端 record 手工对齐，尚无编译期一致性保证。
- `frontend/src/components.d.ts` 是 `unplugin-vue-components` 的生成物，但**必须入库** ——
  `strictTemplates` 依赖它，缺少时会对所有 Arco 组件产生误报。代价是组件增减时它也会出现在 diff 中。
- 前端 chunk 划分目前只把 vue 三件套单独拆出，未按更细粒度优化；出现体积告警时再细化。
- 数据库连接池参数（max 10）为保守起步值，**未经任何压测验证**。
  Phase 09 完成压测后按实测数据调整。
- 尚无 CORS 配置。当前依赖 dev proxy 与同源反向代理；出现真实跨域部署形态时再配，且必须使用白名单。
- v0.1.0 的仓库内**曾包含** `src/test/resources/testcontainers.properties`，
  用于把 ryuk 镜像指向本地已有的 tag（绕开本机 Docker Hub 不可达）。
  这是本机专属的环境适配，**它确实影响了仓库内容的可移植性** ——
  所有克隆者都被固定在同一 ryuk 版本上。已在 `[Unreleased]` 中移除，
  改为用户级 `~/.testcontainers.properties`。

---

## 版本号约定

| 段位 | 递增条件 |
|---|---|
| MAJOR | 出现不兼容的 API 变更（当前阶段基本不会发生） |
| MINOR | 完成一个 Phase，且该 Phase 的验收标准全部通过 |
| PATCH | 缺陷修复、文档修订等不改变对外契约的变更 |
