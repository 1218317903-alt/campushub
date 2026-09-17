# architecture.md · 工程架构说明（实现级）

> **本文定位**：`docs/02-architecture.md` 回答「我们打算怎么设计」（模块划分、技术选型的取舍）。
> 本文回答「**代码里实际是怎么落地的**」——包结构、请求链路、契约、配置分层、测试策略、本地怎么跑。
>
> **维护纪律**：本文是活文档。每次 Phase 结束、或任何影响链路/契约/目录结构的改动，
> 都必须同步更新本文（`docs/00-工程规约.md` §16.3）。**文档与代码不一致时，以代码为准，并立即修文档**。
>
> **最后更新**：Phase 01（`v0.1.0`）
> **权威程度**：实现级说明；与 `00-工程规约.md` 冲突时以规约为准，与代码冲突时以代码为准。

---

## 1. 运行时构成

当前（Phase 01）只有两个进程 + 一个依赖：

```
┌───────────────────────┐        ┌───────────────────────┐        ┌──────────────────┐
│  frontend (Vite)      │  /api  │  backend (Spring Boot)│  JDBC  │  MySQL 8.4       │
│  Vue 3 + TS           │ ─────► │  内嵌 Tomcat :8080    │ ─────► │  Docker :3306    │
│  dev :5173            │  proxy │  Java 21              │        │  卷 camphub-     │
│                       │        │                       │        │  mysql-data      │
└───────────────────────┘        └───────────────────────┘        └──────────────────┘
     静态产物由 Nginx 托管              单进程，Modular Monolith             唯一外部依赖
```

**为什么还没有 Redis / 消息队列 / 对象存储**：它们是解决**已经出现**的性能与一致性问题的工具。
Phase 01 尚无任何真实负载数据，此时引入只会增加运维面并掩盖真实瓶颈。
引入时机已写入 `docs/12-phase-plan.md`（Redis 在 Phase 09、对象存储在 Phase 05、搜索在 Phase 07），
且**引入前必须先有压测或观测数据**（`00-工程规约.md` §16 / §20）。

---

## 2. 代码组织

### 2.1 仓库结构

```
CampusHubAI/
├── src/main/java/ai/camphub/        后端源码
├── src/main/resources/              配置与迁移脚本
├── src/test/java/ai/camphub/        测试
├── frontend/                        前端源码（独立 package.json，独立构建）
├── docs/                            设计与规约文档
├── docker-compose.yml               依赖服务编排
├── Makefile                         统一命令入口（make help）
├── .env.example                     环境变量样例（可入库）
└── pom.xml                          后端构建
```

**前后端同仓库、但构建彼此独立**：Phase 01 没有「前端构建产物进 jar」的需求，
强行整合只会让本地起前端时也要先跑一遍 Maven。等真正需要单体交付时再引入 frontend-maven-plugin。

### 2.2 后端包结构（模块 → 分层）

模块划分遵循**业务域**，而不是技术层。每个模块内部再分四层：

```
ai.camphub
├── CamphubApplication.java          唯一启动类
├── common/                          共享内核（只能被依赖，不能依赖业务模块）
│   ├── config/                      AppProperties · RequestProperties · OpenApiConfig
│   ├── error/                       ErrorCode · ApiError · BusinessException · GlobalExceptionHandler
│   └── web/                         TraceIdFilter · ClientIpResolver
├── system/                          模块（Phase 01）
│   ├── api/         ← 对外边界：Controller + Request/Response DTO
│   ├── app/         ← 应用服务：事务边界、权限校验、编排
│   ├── domain/      ← 领域模型：不依赖任何 Spring 类
│   └── infrastructure/ ← 技术实现：Mapper 接口 + XML
├── identity/                        模块（Phase 02）：账号体系与鉴权
│   ├── api/                         AuthController · UserController（均不含"操作哪个用户"这类参数）
│   ├── app/                         AuthService · AccountService · SessionService ·
│   │                                RefreshTokenService · RefreshTokenLeakHandler ·
│   │                                LoginAttemptService · AuthRateLimiter
│   ├── config/                      SecurityProperties（安全参数的集中声明处）
│   ├── domain/                      User · UserCredential · UserStatus · UserPrincipal ·
│   │                                Role · RefreshTokenRecord · PasswordPolicy ·
│   │                                TokenHasher · RandomValues · DeviceLabel
│   └── infrastructure/
│       ├── (Mapper + XML)
│       └── security/                JwtTokenService · JwtAuthenticationFilter ·
│                                    UserPrincipalLoader · SecurityConfig ·
│                                    RestAuthenticationEntryPoint · RestAccessDeniedHandler ·
│                                    ApiErrorResponseWriter · CommonPasswordList
└── platform/                        平台能力（非业务域）
    └── audit/                       AuditAction · AuditResult · AuditEntry · AuditService
```

`platform.audit` 归属 `platform` 而非 `identity`：审计是**横向能力**，
后续 community / workspace / ai 都要写审计。放进 `identity` 会让每个模块都反向依赖它。

各层职责与**禁止事项**：

| 层 | 职责 | 禁止 |
|---|---|---|
| `api` | 协议适配：解析入参、调用应用服务、组装响应 | 直接访问 `infrastructure`（会绕过权限与事务） |
| `app` | 事务边界、跨实体编排、权限判定 | 拼装 HTTP 相关对象 |
| `domain` | 业务规则与数据结构 | 依赖 `org.springframework.*` |
| `infrastructure` | SQL、外部系统调用 | 被 `api` 直接引用 |

### 2.3 边界由构建期断言守护

上述约束**不是靠评审时的自觉**，而是写成 ArchUnit 测试（`src/test/java/ai/camphub/architecture/ModuleBoundaryTest.java`），
越界直接让 `mvn test` 失败：

| 规则 | 拦住的问题 |
|---|---|
| 模块间无循环依赖 | 边界名存实亡、拆分无从下手 |
| `common` 不依赖任何业务模块 | `common` 退化成「万能垃圾桶」 |
| `..api..` 不依赖 `..infrastructure..` | 绕过权限校验与事务边界（IDOR 类漏洞的成因） |
| `..domain..` 不依赖 `org.springframework..` | 领域逻辑被框架细节渗透，无法脱容器测试 |
| `@RestController` 必须位于 `..api..` | 上面那条规则因包名不匹配而失效 |
| 禁止 `@Autowired` 字段注入 | 依赖不可见、对象无法在容器外构造 |

包名模式是**通配**的（`..api..` / `..domain..`），因此 Phase 02 起新增模块时**无需修改这个测试**，
规则自动覆盖 —— 这正是把它写成通配规则而非逐模块枚举的价值。

### 2.4 前端结构与构建产物

```
frontend/src/
├── api/
│   ├── types.ts        后端错误契约的 TS 镜像
│   ├── http.ts         fetch 封装：ApiError / NetworkError / 超时 / 取消
│   └── system.ts       按领域分文件的接口定义
├── stores/             Pinia（当前仅 app store）
├── router/             路由表（全部为动态 import，路由级懒加载）
├── layouts/            应用外壳（顶栏 + 内容区 + 页脚）
├── views/              页面
├── components.d.ts     由 unplugin-vue-components 生成，**需要入库**
└── styles/main.css     全局重置、排版基线、CSS 变量
```

**Arco 组件按需引入**（`unplugin-vue-components` + `ArcoResolver`），
因此 `main.ts` 中既没有 `app.use(ArcoVue)`，也没有 `import '.../arco.css'`。
只有命令式 API（`Message` / `Notification` / `Modal`）需要按需单独 import ——
它们不经模板解析，插件无法感知。

**实测量化**（Phase 01 页面，`npm run build`）：

| 方案 | JS（gzip） | CSS（gzip） |
|---|---|---|
| 全量引入 Arco | 1 243.79 kB（310.65 kB） | 405.39 kB（50.09 kB） |
| 按需引入（当前） | 221.85 kB（84.02 kB） | 81.25 kB（12.85 kB） |

**`vueCompilerOptions.strictTemplates: true` 是必需的**，不是可选项。
默认配置下，模板里写错的组件名（如 `<a-buttn>`）**不会报错** —— Vue 会把它当成原生自定义元素。
开启后配合 `components.d.ts`，拼错的组件名与传错的 prop 类型都会在 `npm run build` 阶段被拦下。
这也意味着 **`components.d.ts` 必须入库**：缺少它时，`strictTemplates` 会对所有 Arco 组件
报「属性不存在」的误报，导致干净检出后无法通过类型检查。
（以上两条均已在本阶段实测确认，不是推断。）

---

## 3. 一次请求的完整链路

以 `GET /api/v1/system/info` 为例：

```
1  TraceIdFilter（@Order(HIGHEST_PRECEDENCE)）
   ├─ 读入站 X-Trace-Id；合法（8~64 位字母数字/连字符）则沿用，否则生成 UUID
   ├─ MDC.put("traceId", id)        → 之后每一行日志都带它
   └─ 写响应头 X-Trace-Id            → 前端与用户都能拿到
        ↓
2  DispatcherServlet → SystemController.info()
   ├─ 只做协议适配，不含业务判断
        ↓
3  SystemInfoService.describe()      @Transactional(readOnly = true)
   ├─ 读配置（application / version / profiles / javaVersion）
   └─ 读 app_metadata 表的 schema.baseline
        ↓
4  AppMetadataMapper.findByKey("schema.baseline")   MyBatis
        ↓
5  SELECT meta_value FROM app_metadata WHERE meta_key = ?   →  MySQL
        ↑
6  返回 SystemInfoResponse（record，无信封）
        ↓
7  finally: MDC.remove("traceId")   ← 必须清理，线程池会复用线程
```

### 3.1 为什么入站 `X-Trace-Id` 必须校验

请求头是**不可信输入**。若不校验就写进日志，攻击者可以塞入换行符伪造日志行（log injection），
把虚假记录混进真实日志。因此只接受严格字符集，其余一律丢弃并重新生成。

### 3.2 受保护接口的链路（Phase 02 起）

以 `GET /api/v1/users/me` 为例。与上面那条链路的关键差别是：**鉴权发生在 DispatcherServlet 之前**，
因此错误响应也必须由过滤链自己写出（否则会退化成容器的空白 401，破坏统一错误契约）。

```
1  TraceIdFilter                      写 MDC + X-Trace-Id
        ↓
2  JwtAuthenticationFilter（OncePerRequestFilter，位于 UsernamePasswordAuthenticationFilter 之前）
   ├─ 无 Authorization 头 → 直接放行，不写任何响应（可能是公开端点）
   ├─ decode() 校验签名 / exp / nbf / iss / 必需声明存在性
   │    └─ 失败 → 把 ErrorCode 放进请求属性，**仍然放行**
   ├─ UserPrincipalLoader.load(uid)     每请求回库读账号 + 角色 + 权限
   │    ├─ 账号不存在        → TOKEN_REVOKED
   │    ├─ 账号非 ACTIVE     → ACCOUNT_NOT_USABLE
   │    └─ ver ≠ user.token_version → TOKEN_REVOKED
   └─ 全部通过 → SecurityContextHolder.setAuthentication(principal)
        ↓
3  AuthorizationFilter（Spring Security）
   ├─ 未认证 + 受保护端点 → RestAuthenticationEntryPoint
   │    └─ 读请求属性里的 ErrorCode；无则 UNAUTHENTICATED
   │       → ApiErrorResponseWriter 写统一错误信封（401 或 403，由错误码决定）
   ├─ 已认证但权限不足 → RestAccessDeniedHandler
   └─ 通过 → 继续
        ↓
4  DispatcherServlet → UserController.me(@AuthenticationPrincipal UserPrincipal)
        ↓
5  AccountService.getSelf(principal.userId())        ← 身份来自上下文，不是 URL 参数
        ↓
6  UserMapper.findById(userId) → SELECT … FROM `user` WHERE id = ? AND deleted_at IS NULL
        ↑
7  返回 UserProfileResponse（无邮箱、无状态、无自增 ID）
```

**为什么令牌无效时不直接返回 401**：本过滤器只把错误码写进请求属性后继续放行。
原因有二 —— 受保护端点后续的授权过滤器会拒绝该请求，未认证处理器读到该属性后
客户端拿到的是"令牌已过期"而不是笼统的"未登录"，这两者对前端意味着不同动作
（静默刷新 vs 要求重新登录）；公开端点（如 `/auth/login`）则正常执行 ——
这一点很关键：若过滤器直接拦下无效令牌，一个令牌已过期的客户端连"重新登录"
这个接口都调不到，会陷入无法自救的状态。

> 一句话：**无效令牌只说明"这次请求不是已认证身份"，不说明"整个请求非法"。**

**为什么未认证的未知路径返回 401 而不是 404**：请求在过滤链就被拦下，
从未走到路由匹配。这是期望的行为 —— 若未认证调用方能靠 404/405 与 401 的差别
区分路径是否存在，就等于提供了一个免费的接口枚举器。

### 3.3 令牌体系

| 令牌 | 形态 | 有效期 | 服务端状态 | 撤销方式 |
|---|---|---|---|---|
| 访问令牌 | JWT（HS256 自签） | 15 分钟 | **无状态** | 世代号比对（粗粒度，账号级） |
| 刷新令牌 | 32 字节随机值（Base64URL） | 30 天 | **有状态**：只存 SHA-256 | 逐条撤销 / 全部撤销 + 轮换 |

令牌中**刻意不放角色与权限**：令牌签发后无法收回，把权限写进令牌意味着撤销一个管理员的权限
要等他的令牌自然过期。改为每次鉴权回库读取，权限变更立即生效 —— 代价是每请求一次数据库往返。

**为什么是 HS256 而不是 RS256**：RS256 的价值在于"验证方拿不到签发密钥"（公私钥分离），
适用于 A 服务签发、B/C 服务验证的场景。当前是单体应用，签发与验证在同一进程内，
引入非对称密钥只增加密钥分发与轮换的复杂度，不带来实际安全收益。
若将来 AI Runtime 需要独立验证令牌，那次演进会同时需要密钥分发机制，到那时再换 RS256 才是有依据的决定。

**轮换与重放检测**：每次刷新都签发新令牌并作废旧令牌（`revoked_at` + `rotated_from` 串成链）。
被轮换掉的旧令牌若再次出现，说明令牌已泄露到客户端之外。判定时用两条判据区分两种情形：

| 情形 | 数据特征 | 处置 |
|---|---|---|
| 多标签页并发刷新 | 后继令牌存在、仍有效、且**设备标识相同**、旧令牌撤销在 5 秒内 | 不判泄露，返回"请使用最新令牌" |
| 确凿的泄露 | 其余（含跨设备、后继已失效、超出宽限窗口） | 撤销该账号**全部**会话 + 推进世代号 + 记 `AUTH_TOKEN_REPLAY_DETECTED` |

实时序见 `RefreshTokenService` 与 `RefreshTokenLeakHandler` 的类注释。

### 3.4 事务边界：三类"必须独立提交"的写入

本项目有一个反复出现的模式，值得单独记下来 —— **写入之后代码必然抛异常的路径，
不能挂在会回滚的事务上**：

| 组件 | 事务 | 不这样做会发生什么 |
|---|---|---|
| `AuditService` | `REQUIRES_NEW` | 失败路径的审计随业务回滚一起消失，而那恰是审计里最有价值的一类记录 |
| `LoginAttemptService` | `REQUIRES_NEW` | 失败计数永远停在 0，"连续失败 5 次锁定"在生产上永不生效 |
| `RefreshTokenLeakHandler` | `REQUIRES_NEW` | 泄露处置（撤销全部会话 + 推进世代号）被回滚吞掉，客户端收到"已登出全部设备"而数据库什么都没变 |

第三种是本阶段实际发生过的缺陷（见 `CHANGELOG.md` 0.2.0 / Fixed），也是最隐蔽的一种：
处置确实执行了、审计也确实写下了"处置成功"，只有事务回滚在静默地撤销它。

`LoginAttemptService` 与 `RefreshTokenLeakHandler` 单独成类还有一个共同理由：
Spring 的事务基于代理，**同一个类内部的方法自调用不会走代理**，`@Transactional` 会被静默忽略。
拆成独立 Bean 是让注解真正生效的前提，而这个坑不会报错，只会让事务边界悄悄失效。

### 3.5 为什么 MDC 必须在 `finally` 清理

Tomcat 复用线程。不清理会把上一个请求的 traceId 带到下一个请求的日志里 ——
这种「串号」比没有 traceId 更糟：它会引导排查到错误的地方。

---

## 4. 统一错误契约

**所有**失败响应（含框架抛出的、含 5xx）都是同一个形状，前端只需实现一套错误处理：

```json
{
  "code": 40400,
  "message": "资源不存在",
  "traceId": "3f1a9c2e4b7d4f1a8e6c0b2d5a7f3e91",
  "timestamp": "2026-09-17T08:00:00Z",
  "path": "/api/v1/posts/123",
  "details": [{ "field": "title", "reason": "不能为空" }]
}
```

### 4.1 错误码编码规则

```
HHH SS
│   └─ 同一 HTTP 状态内的序号（00 起）
└───── HTTP 状态码，一眼看出传输语义
```

`40400` = HTTP 404 的第 0 号错误。序号段按模块预分配，避免各模块撞号：

| 序号段 | 归属 | 状态 |
|---|---|---|
| `40000 ~ 40009` | 通用请求错误（参数 / 校验 / 类型） | Phase 01 已用 |
| `40010 ~ 40019` | `identity` | Phase 02 起 |
| `40020 ~ 40029` | `workspace` | Phase 04 起 |
| `40030 ~ 40039` | `community` | Phase 03 起 |
| `40040 ~ 40049` | `ingestion` / `discover` | Phase 06 起 |
| `40050 ~ 40059` | `ai` | Phase 08 起 |

当前已定义：`40000` 校验失败 · `40001` 请求不合法 · `40002` 类型错误 · `40003` 缺参 ·
`40400` 不存在 · `40500` 方法不支持 · `41500` 内容类型不支持 · `50000` 内部错误 · `50300` 依赖不可用。

### 4.2 三个刻意的设计取舍

**① 成功响应不套 `{code, data, message}` 信封。**
错误需要额外元信息（错误码、traceId、字段明细），所以错误有结构；成功响应的 body 本身就是结果，
再套一层只让前端多一次解包、让 OpenAPI 文档多一层缩进。需要链路信息时用响应头 `X-Trace-Id` ——
**用正确的载体承载正确的信息**。

**② `details` 恒为数组，从不为 `null`。**
让前端可以无条件写 `for (const d of err.details)`。为省几个字节而把判空负担转移给每个调用方，不划算。

**③ 5xx 绝不泄漏内部细节。**
`GlobalExceptionHandler` 的兜底分支只回固定文案 + traceId，异常堆栈只进日志。
判据：**traceId 是给用户拿去反馈的，堆栈不是。**

### 4.3 前端如何消费

`frontend/src/api/http.ts` 把上述结构映射为 `ApiError` 类，并提供 `fieldMessages()`
直接产出 `{ 字段名: 提示 }`，可绑定到表单。同时把「服务端返回了错误」（`ApiError`）与
「连不上 / 超时 / 响应非 JSON」（`NetworkError`）**明确区分** ——
这两类故障的排查方向完全不同，混成一句「加载失败」等于没有信息。

前端**刻意不做全局自动重试**：对非幂等请求（POST）无条件重试会产生重复副作用。
需要重试的调用方应自行确认幂等后再重试。

---

## 5. 配置管理

### 5.1 分层

| 来源 | 文件 | 是否入库 | 存什么 |
|---|---|---|---|
| 基础配置 | `src/main/resources/application.yml` | ✅ 入库 | 所有非密配置；可能含密的项一律 `${ENV:默认值}` |
| 本地增强 | `application-local.yml` | ✅ 入库 | `show-details: always`、DEBUG 日志等**非密**的开发便利项 |
| 本地密钥 | `.env` | ❌ 忽略 | 真实密码、Key（与 `.env.example` 对应） |
| 运行环境 | 环境变量 | — | 容器/CI 注入，优先级最高 |

`application.yml` 中通过 `spring.config.import: optional:file:./.env[.properties]` 让 `.env` 自动生效，
无需手工 `source`。`optional:` 前缀保证**没有 `.env` 时也能正常启动**（例如 CI 里只给环境变量）。

### 5.2 两个刻意的决定

**`.env.example` 里刻意不提供 `SPRING_PROFILES_ACTIVE`。**
它会与集成测试的 `@ActiveProfiles("test")` 争夺 profile 决定权，导致测试行为不确定 ——
这类「配置造成的偶发失败」极难排查。需要切 profile 时请在启动命令前直接给环境变量。

**不使用 `flyway.baseline-on-migrate`。**
它会把「库结构不符合预期」静默跳过，属于把配置错误伪装成正常运行。
宁可启动失败，也不要静默错位（`clean-disabled: true` 同理）。

---

## 6. 日志与可观测性

### 6.1 日志格式

```
%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{traceId:-no-trace}] %logger{40} - %msg%n
```

`%X{traceId:-no-trace}` 由 `TraceIdFilter` 写入 MDC。**没有它就只能靠时间戳在日志里猜**。
有了它，用户报来的一个 ID 就能捞出该请求的全部日志。

### 6.2 健康检查

| 端点 | 用途 |
|---|---|
| `GET /actuator/health` | 存活/就绪探针（含 `liveness` / `readiness` 分组） |
| `GET /actuator/info` | 应用名与版本 |

**只暴露 `health,info`**。放开 `*` 会把 `beans` / `env` / `heapdump` 变成攻击面。
默认 `show-details: never`（不对外暴露依赖拓扑），仅 `local` profile 放开。

### 6.3 业务自检端点

`GET /api/v1/system/info` 返回版本、生效 profile、Java 版本与**数据库 schema 基线**
（读自 `app_metadata` 表）。它的价值是：部署后一个请求就能确认「服务起来了，且迁移生效了」。

> 该端点公开可见，因为它只含非敏感事实。**不要**往里加配置项、连接串或依赖地址；
> 将来要暴露运行细节，应新开一个需要管理员权限的端点，而不是放宽这个公开端点。

---

## 7. 数据访问与迁移

- **所有 schema 变更必须走 Flyway**（`src/main/resources/db/migration/V{n}__{name}.sql`）。
  禁止手工改库后不记录；**禁止修改已发布的迁移脚本**，后续改动一律用新的 version ——
  `validate-on-migrate: true` 会帮我们拦住对历史脚本的改动。
- **MyBatis 配置**：`map-underscore-to-camel-case: true`，XML 放在 `resources/mapper/**/*.xml`。
  Mapper 接口位于各模块的 `infrastructure` 包，由启动类 `@MapperScan("ai.camphub.**.infrastructure")` 统一扫描。
- **选择官方 MyBatis 而非 MyBatis-Plus**：MyBatis-Plus 当前没有 Spring Boot 4 的适配版本。
  为了「少写几行 CRUD」而停在 Boot 3 上不划算；且本项目的数据访问以明确 SQL 为主，用不上它的动态能力。
- **时间与时区**：JDBC 连接串显式指定 `connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true`。
  MySQL 默认 `time_zone=SYSTEM`，容器时区与 JVM 时区不一致时，「发布时间 / 时间衰减排序」会出现整体偏移。

---

## 8. 测试体系

三层，各管一件事，不重复覆盖同一个风险：

| 层 | 位置 | 手段 | 覆盖的风险 |
|---|---|---|---|
| 单元测试 | `**/*Test.java` | JUnit 5 + AssertJ，不起容器 | 纯逻辑：错误码、错误体工厂、traceId 校验 |
| 架构测试 | `architecture/` | ArchUnit | 模块边界与分层的**长期退化** |
| 集成测试 | `**/*IT.java` | `@SpringBootTest(RANDOM_PORT)` + Testcontainers | 真实链路：HTTP 契约、DB 迁移、序列化 |

### 8.1 三个关键取舍

**① 集成测试用真实 MySQL 容器，不用 H2。**
H2 与 MySQL 在字符集、排序规则、`ON UPDATE CURRENT_TIMESTAMP`、JSON 函数上行为不同。
用 H2 跑的绿灯是**假绿灯** —— 它验证的是「在 H2 上能跑」，而生产跑的是 MySQL。

**② 断言用 JDK 内建 HttpClient + Jackson，不用 RestAssured / MockMvc 做端到端。**
目的是让测试对框架版本升级不敏感。Boot 4 期间测试工具链变动频繁，
把测试绑在框架抽象上会让升级成本翻倍。**用真实 HTTP 打真实端口**，最接近真实调用方。
JSON 解析复用容器里那个 `ObjectMapper`（Boot 4 起是 Jackson 3 的 `tools.jackson`），
这样"测试怎么解析响应"与"应用怎么序列化响应"用的是同一套配置。

**③ 用 `127.0.0.1` 而非 `localhost`。**
`localhost` 在部分环境下会先解析到 IPv6 `::1`，而容器端口映射在 IPv4 上，
表现为「偶发的连接被拒绝」—— 这类不确定性排查成本极高，从源头避免。

### 8.2 安全测试的组织方式（Phase 02）

安全断言与功能断言分开成类，因为**它们的价值来源不同**：功能断言在有反馈，
安全断言在"不能用的地方确实不能用"，上线时永远不会有反馈 —— 只有攻击者会告诉你漏了哪一条。

| 类 | 聚焦 |
|---|---|
| `AuthFlowIT` | 正向链路 + 契约边界。除"能注册能登录"外，重点断言**响应与库里到底存了什么**（密码只落 BCrypt cost=12 的哈希、刷新令牌只落 SHA-256） |
| `TokenLifecycleIT` | 轮换、重放、撤销、过期。最有价值的断言不是"接口返回了什么"，而是**"事后那个令牌还能不能用"** |
| `AccountSecurityIT` | 越权、改密、锁定、限流。验证 404 而非 403 的越权语义、改密后其它设备立即失效、连输 5 次锁定、429 + `Retry-After` |
| `PasswordPolicyTest` · `DeviceLabelTest` · `ClientIpResolverTest` | 纯逻辑，不起容器。`ClientIpResolverTest` 钉住的是**"默认不信任代理头"**这条安全属性 —— 集成测试跑在信任代理头的配置下，那条属性只能在这里被覆盖 |

**自己签发令牌**：过期、缺声明、账号不存在这三条分支无法靠正常流程触发（正常令牌 15 分钟才过期，
等不起）。测试用**同密钥、同签发者**自造令牌精准命中校验链的每一环 ——
用同一密钥而不是随便一个，是为了确保失败原因就是被测的那一环，而不是被"签名不符"提前拦掉。

**每个用例一个来源 IP**：限流按 IP 分桶，注册桶只有 5 次/小时。若所有用例都从容器网关的
`127.0.0.1` 发起，几十个用例会互相消耗额度，产生"单跑通过、全量跑 429"这类最难排查的失败。
测试 profile 开启 `trust-forwarded-headers`，让用例用 `X-Forwarded-For` 各占一个桶
（RFC 5737 的 `198.51.100.0/24`，一眼能看出不是真实来源），而限流逻辑本身仍是被真实执行的那份代码。

### 8.3 命令

```bash
make test      # 仅单元测试（*Test），不需要 Docker
make it        # 仅集成测试（*IT），需要 Docker
make verify    # 全量：单元 + 集成
```

---

## 9. 本地运行

```bash
# 0. 一次性：准备环境变量
cp .env.example .env          # 然后按需修改（本机默认值通常可直接用）

# 1. 起依赖（当前仅 MySQL 8.4），并等待健康检查通过
make up

# 2. 起后端（默认 local profile，:8080）
make run

# 3. 起前端（:5173，/api 自动代理到 :8080）
make fe-install && make fe-dev
```

验证：打开 `http://localhost:5173`，页面上的「运行实例信息」应显示真实后端数据，
其中 **数据库 Schema 基线 = `V1`** 说明 Flyway 迁移已生效。

详细的环境准备（JDK / Maven / Docker 的安装与验证记录）见 `docs/11-开发环境.md`；
若页面取不到数据，按 `docs/11-开发环境.md` §6 的排查顺序逐项确认。

---

## 10. 已知取舍与技术债

按「现在不做，什么时候做」记录。**不为未发生的问题提前支付复杂度**。

| 项 | 现状 | 触发条件 / 计划 |
|---|---|---|
| 前端 API 类型手写 | 与后端 record 手工对齐 | 接口数量上来、契约频繁变动；届时用 `openapi-typescript` 从 `/v3/api-docs` 生成 |
| 无 CORS 配置 | 前端走 dev proxy / 同源反代 | 出现真实的跨域部署形态时再配，且必须白名单而非 `*` |
| 限流为单实例内存实现 | 多实例部署时额度为"配置值 × 实例数"；重启清零；固定窗口有边界效应 | Phase 09 引入 Redis；**前提是先压测确认它确实是瓶颈** |
| 不做访问令牌黑名单 | 撤销是账号级粗粒度：撤销单个设备会使该账号所有访问令牌失效 | 需要"精准撤销单个访问令牌"时再引入，需额外的存储与清理任务 |
| 每请求回库装配权限 | 无权限缓存，换来实现简单、权限变更立即生效 | Phase 09 压测后按实测决定是否加缓存及失效策略 |
| 审计只写不查 | `audit_log` 仅写入 | Phase 10 提供查询接口与后台展示 |
| 无 MFA / 第三方登录 / 邮箱验证 | — | 不在 Phase 02 范围；有明确需求时单独立项 |
| 连接池起步值保守（max 10） | 无负载数据支撑调参 | Phase 09 压测后按实测调整 |
| 前端无组件测试 | 仅类型检查 + 构建校验 | 业务组件出现后引入 Vitest，避免为测试骨架而测试 |
| 前端无 ESLint / Prettier | 依靠 `strict` TS 与 `strictTemplates` | 多人协作或格式化争议出现时引入 |
| 前端 chunk 划分仅拆出 vue 三件套 | Arco 按页面路由自然分段 | 出现体积告警时细化 `manualChunks` |
| `components.d.ts` 为生成物但需入库 | 已入库，改动时会出现在 diff | 无更好方案：`strictTemplates` 依赖它。仅在组件增减时变化 |

---

## 11. 变更记录

| 日期 | Version | 变更 |
|---|---|---|
| 2026-09-17 | `v0.1.0` | 初版。Phase 01：工程骨架、统一错误契约、traceId 链路、Flyway、MyBatis、三层测试体系、前端骨架 |
| 2026-09-17 | `v0.2.0` | Phase 02：新增 §3.2 受保护接口链路与 §3.3 令牌体系与 §3.4 事务边界、§8.2 安全测试组织；包结构补入 `identity` 与 `platform.audit`；取舍表更新认证与限流状态 |
