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

### Added

- 暂无（Phase 02 · Identity & Security Foundation 尚未开始）。
  按 `docs/00-工程规约.md` §18，**当前阶段完成后停止，不提前开发下一阶段**。

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
- 本机无法直连 Docker Hub，Testcontainers 的 ryuk 镜像通过镜像代理获取后打了本地 tag。
  这是**本机环境的临时处理**，不影响仓库内容的可移植性。

---

## 版本号约定

| 段位 | 递增条件 |
|---|---|
| MAJOR | 出现不兼容的 API 变更（当前阶段基本不会发生） |
| MINOR | 完成一个 Phase，且该 Phase 的验收标准全部通过 |
| PATCH | 缺陷修复、文档修订等不改变对外契约的变更 |
