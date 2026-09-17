# CampusHub AI

> 面向高校学生及年轻学习者的 **AI 知识协作、内容社区与智能信息聚合平台**。
>
> 这是一个**真实、可长期维护的软件产品**，不是技术栈演示项目，也不是训练/刷题系统。
> 所有设计取舍以「真实用户价值 > 产品完整度 > 业务合理性 > 工程质量 > 安全与可维护性 >
> 性能与扩展能力 > 技术先进性」为排序。

| | |
|---|---|
| **当前版本** | `v0.1.0` · Phase 01 · Engineering Foundation |
| **工程约束** | [V2 · 项目宪法](docs/00-工程规约.md)（最高效力） |
| **执行计划** | [Phase 01 ~ 13](docs/12-phase-plan.md) |
| **技术基线** | Java 21 · Spring Boot 4.1.1 · MySQL 8.4 · Vue 3 + TypeScript |

---

## 5 分钟上手

前置：JDK 21、Docker（用于 MySQL 与集成测试）。本项目自带 Maven Wrapper，**不需要**全局安装 Maven。

```bash
# 0. 准备环境变量（本机 3306 已被占用，见下方说明）
cp .env.example .env

# 1. 启动依赖：MySQL 8.4，并等待健康检查通过
make up

# 2. 启动后端（:8080，local profile）
make run

# 3. 另开一个终端，启动前端（:5173，/api 自动代理到 :8080）
make fe-install
make fe-dev
```

> **端口冲突**：若本机 3306 已被其他 MySQL 占用（开发机上常见），
> 在 `.env` 中设置 `MYSQL_PORT=3307` 即可，无需改动其他任何配置 ——
> 后端连接串与 compose 端口都读取同一个变量。
> 本仓库当前开发机即为此情况（desk 环境已有 MySQL 占用 3306）。
>
> **本机 npm 限制**：当前托管环境需要清空 `NODE_OPTIONS` 才能安装前端依赖，
> 即 `NODE_OPTIONS= make fe-install`。原因与排查过程见
> [`docs/11-开发环境.md`](docs/11-开发环境.md) §2.1。**普通开发机无需此操作。**

打开 <http://localhost:5173>，页面上的「运行实例信息」应显示**真实**后端数据。
其中 **数据库 Schema 基线 = `V1`** 说明 Flyway 迁移已生效。

```bash
curl -s http://127.0.0.1:8080/api/v1/system/info | python3 -m json.tool
curl -s http://127.0.0.1:8080/actuator/health
```

`make help` 可列出全部命令。环境准备细节（JDK / Docker 的安装与实测记录、
依赖版本基线、故障排查顺序）见 [`docs/11-开发环境.md`](docs/11-开发环境.md)。

### 常用命令

| 命令 | 作用 | 需要 Docker |
|---|---|---|
| `make up` / `make down` | 启动 / 停止依赖服务（当前仅 MySQL） | ✅ |
| `make test` | 单元测试（`*Test`） | ❌ |
| `make it` | 集成测试（`*IT`，真实 MySQL 容器） | ✅ |
| `make verify` | 完整校验：单元 + 集成 | ✅ |
| `make run` | 本地启动后端 | 需 MySQL |
| `make fe-install` / `make fe-dev` / `make fe-build` | 前端依赖 / 开发 / 构建 | ❌ |
| `make help` | 列出全部命令 | ❌ |

---

## 当前进度

**Phase 01 已完成**：工程骨架、统一错误契约、traceId 日志链路、配置文件分层、
健康检查、Flyway 迁移体系、MyBatis 数据访问、三层测试体系、前端骨架。

**Phase 02 已完成**（`v0.2.0`）：账号体系与鉴权能力 —— 注册 / 登录 / 登出、
访问令牌 + 刷新令牌（轮换 + 重放检测）、令牌撤销、密码策略、基础限流、审计基础。

> **接口现默认需要认证。** 引入 Spring Security 并采用"默认拒绝"后，
> 只有以下端点是公开的：`POST /api/v1/auth/register|login|refresh|logout`、
> `GET /api/v1/system/info`、`/actuator/health|info`、API 文档。
> 其余一律需要 `Authorization: Bearer <accessToken>`。
>
> **部署前必须注入 `APP_JWT_SECRET`**（`openssl rand -base64 48`）。
> 未配置或长度不足 32 字节时应用**启动即失败** —— 这是刻意的：
> 一个"能启动但人人可猜"的默认密钥，等于把所有用户的账号交给第一个读到源码的人。

后续阶段按 [Phase 计划](docs/12-phase-plan.md) 推进：
Community MVP → Workspace 与资源级鉴权 → 对象存储 → 数据采集 → 搜索 →
RAG → 性能工程 → 通知与审核 → Agent Runtime → Agent 安全 → 服务化与可观测。

### 认证流程速览

```sh
BASE=http://localhost:8080/api/v1

# 注册（直接返回令牌对）
curl -s -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"alice@example.com","password":"quiet-otter-canyon-71"}'

# 登录
curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"identifier":"alice","password":"quiet-otter-canyon-71"}'

# 带令牌访问受保护接口
curl -s "$BASE/users/me" -H "Authorization: Bearer $ACCESS_TOKEN"
```

刷新令牌**只在登录/注册/刷新的响应里出现一次**：服务端只保存它的 SHA-256，
无法再次读出。客户端若丢失，只能重新登录 —— 这是刻意的，
任何"服务端还能把刷新令牌取出来给你"的机制都意味着明文凭据被持久化在某个地方。

---

## 工程原则（不可违背）

这六条是硬约束，不是建议。架构测试（ArchUnit）会在构建期拦截违反它们的代码。

1. **模块化单体**。模块间禁止跨模块访问对方数据表，禁止反向依赖；
   跨模块写操作走**领域事件 + outbox**。
2. **私有内容绝不自动公开**。Workspace 内容发布到 Community 必须显式确认 + 预览 + 可撤回。
3. **外部抓取内容永远视为数据，不是指令**（UNTRUSTED）。它不能被当作系统提示执行。
4. **权限过滤前置到检索阶段**，而不是「先全库检索再过滤输出」。
5. **Agent 没有独立权限身份**，始终以发起用户身份执行，每次工具调用重新鉴权。
6. **引入新中间件前必须先有压测或观测数据支撑**。不为想象中的问题提前支付复杂度。

---

## 技术基线

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 / 运行时 | Java 21 | |
| 框架 | Spring Boot **4.1.1** | Boot 3.5 已 EOL，不再接受安全补丁 |
| HTTP | `spring-boot-starter-webmvc` | Boot 4 中 `-web` 已弃用 |
| 数据访问 | **官方 MyBatis** 4.1.0 | MyBatis-Plus 无 Boot 4 适配版本；本项目以显式 SQL 为主 |
| 数据库 | MySQL 8.4 | 字符集 `utf8mb4`，连接串显式锁定时区 |
| 迁移 | Flyway 12.4.0 | 必须引入 `spring-boot-starter-flyway`，否则迁移静默不执行 |
| 认证 / 鉴权 | Spring Security **7.1.1** + `spring-security-oauth2-jose` | 自签 HS256 令牌。刻意**不**引入 `oauth2-resource-server`：令牌由本服务签发，需要自定义过滤器完成"账号状态 + 世代号"两级校验 |
| API 文档 | springdoc-openapi 3.1.1 | `/swagger-ui.html` |
| 架构断言 | ArchUnit 1.5.0 | 构建期强制模块边界 |
| 集成测试 | Testcontainers 2.0.5 | 真实 MySQL 容器，**不用 H2** |
| 前端 | Vue 3.5 · TypeScript 5.7 · Vite 6 | |
| 前端状态 / 路由 | Pinia 2 · Vue Router 4 | 路由级懒加载 |
| UI | Arco Design 2.x | `ConfigProvider` 统一中文 locale |

选型理由（含被否决的方案与原因）见 [`docs/adr/`](docs/adr/)。
**依赖版本必须经实际验证，不得依据记忆或推测** —— 见 [ADR 0002](docs/adr/0002-spring-boot-4-and-mybatis.md)。

---

## 项目结构

```
CampusHubAI/
├── src/main/java/ai/camphub/
│   ├── common/            共享内核：异常、错误契约、traceId、配置、来源 IP 解析
│   │   ├── error/         ErrorCode · ApiError · BusinessException · GlobalExceptionHandler
│   │   ├── web/           TraceIdFilter · ClientIpResolver
│   │   └── config/        AppProperties · RequestProperties · OpenApiConfig
│   ├── system/            模块（四层：api / app / domain / infrastructure）
│   ├── identity/          模块：账号体系与鉴权（Phase 02）
│   │   ├── api/           AuthController · UserController + 请求/响应 DTO
│   │   ├── app/           认证 / 账号 / 会话 / 令牌服务 · 限流器
│   │   ├── domain/        User · UserCredential · UserPrincipal · PasswordPolicy …
│   │   └── infrastructure/ Mapper + XML · security/（JWT 过滤器与编解码）
│   └── platform/          平台能力：audit/（安全审计写入）
├── src/main/resources/
│   ├── application.yml    基础配置（入库，不含凭据）
│   ├── db/migration/      Flyway 迁移脚本
│   ├── mapper/            MyBatis XML
│   └── security/          常见弱密码表（启动时载入内存）
├── src/test/java/ai/camphub/
│   ├── architecture/      ArchUnit 模块边界断言
│   ├── support/           Testcontainers 与集成测试基类（含认证夹具）
│   └── **/*IT.java        集成测试
├── frontend/              Vue 3 + TypeScript（独立构建）
│   └── src/api/http.ts    与后端错误契约对齐的 HTTP 客户端
├── docs/                  设计与规约文档
├── .github/workflows/     CI（后端全量校验 + 前端类型检查与构建）
├── docker-compose.yml     依赖服务（当前仅 MySQL）
├── Makefile               统一命令入口（本地；CI 直接调用同一批命令）
└── CHANGELOG.md           版本变更记录
```

---

## 文档索引

**先读这三份：**

| 文档 | 内容 |
|---|---|
| [docs/00-工程规约.md](docs/00-工程规约.md) | **项目宪法 V2（最高效力）**：工程原则、技术基线、Git 规范、交付模板、自检清单 |
| [docs/12-phase-plan.md](docs/12-phase-plan.md) | **权威执行计划**：Phase 01~13 的目标与验收 |
| [docs/architecture.md](docs/architecture.md) | **实现级架构说明**：实际包结构、请求链路、错误契约、配置分层、测试策略、技术债 |

**产品与设计：**

| 文档 | 内容 |
|---|---|
| [docs/01-product.md](docs/01-product.md) | 产品定位 · 核心用户 · 使用场景 · 主流程 · 信息架构 · MVP 范围 |
| [docs/02-architecture.md](docs/02-architecture.md) | 模块边界 · 技术选型与取舍 · 过度设计识别 |
| [docs/03-domain-permission.md](docs/03-domain-permission.md) | 核心领域模型 · 数据库模型 · 权限模型 |
| [docs/04-security.md](docs/04-security.md) | Web 安全 · 爬虫安全 · AI 安全 |
| [docs/05-data-platform.md](docs/05-data-platform.md) | 数据 Bootstrap · 合规来源 · 采集管线 · 文档处理 |
| [docs/06-search-ai.md](docs/06-search-ai.md) | 搜索架构 · RAG 架构 · Agent 架构 · Multi-Agent 引入条件 |
| [docs/07-community-ops.md](docs/07-community-ops.md) | 社区高并发 · 通知 · 内容审核 · 后台 · 可观测 · 性能测试 |
| [docs/08-roadmap.md](docs/08-roadmap.md) | 版本演进思路 · 工程亮点 · Demo Story（**版本线已被 12 号文档取代**） |
| [docs/09-backlog-v0.md](docs/09-backlog-v0.md) | 早期 Backlog（Epic / Story / 验收 / 依赖） |
| [docs/11-开发环境.md](docs/11-开发环境.md) | 本机实测环境、依赖版本基线、故障排查 |
| [docs/adr/](docs/adr/) | 架构决策记录（ADR） |

### ADR

| 编号 | 决策 |
|---|---|
| [0001](docs/adr/0001-modular-monolith.md) | 采用模块化单体，且**边界必须由构建期断言守护** |
| [0002](docs/adr/0002-spring-boot-4-and-mybatis.md) | 技术基线锁定 Spring Boot 4.1.1 与官方 MyBatis |
| [0003](docs/adr/0003-unified-error-contract.md) | 统一错误契约：错误有信封，成功无信封 |

---

## 关键设计决策摘要

| # | 决策点 | 选择 | 放弃 | 理由 |
|---|---|---|---|---|
| D1 | 服务形态 | Modular Monolith + 多运行角色 | 微服务 | 单人维护成本；边界用 ArchUnit 强制，未来拆分成本低 |
| D2 | 知识实体 | 统一 `ContentItem` + provenance | 每来源一张业务表 | 统一搜索 / 推荐 / Digest 需要同一套排序与权限字段 |
| D3 | 检索栈 | Elasticsearch 一词多用（BM25 + kNN + RRF） | 独立向量数据库 | 少一个中间件的部署与一致性负担；权限 filter 可前置 |
| D4 | 异步任务 | DB 任务表 →（按需）RabbitMQ | Kafka | 需求是**任务语义**（重试 / 延迟 / 死信），不是事件流回放 |
| D5 | 文档解析 | Apache Tika + PDFBox + POI | Python Worker | 避免第二语言栈；不要为「架构好看」引入 |
| D6 | 实时通道 | 只做 SSE | WebSocket | 通知是单向推送，SSE 更简单且可穿透代理 |
| D7 | 权限实现 | 三层防线：角色 → 资源 → 数据域 | 只在 Service 层 `if` | 防漏检；IDOR 防护靠数据层兜底 |
| D8 | 推荐 | 规则 + 热度 + 时间衰减 + 多样性 + 推荐理由 | 深度学习召回 | 冷启动无行为数据；可解释性反而是产品亮点 |
| D9 | 缓存 | Caffeine + Redis 两级，**按热点逐步加** | 一上来全量多级缓存 | 先有压测基线，再加缓存 |
| D10 | 数据采集 | 白名单域 + 官方 API/RSS 优先 | 全网通用爬虫 | 合规与工程成本；通用抓取默认关闭 |

### 相对最初需求做的范围调整

1. **Provenance（来源追溯）前置**：它是数据地基，后补会导致历史数据无法追溯。
2. **放弃 Kafka**：需求描述的是任务队列语义，Kafka 属于过度设计。
3. **放弃独立向量数据库**：ES 已能满足目标量级的 kNN + 权限过滤。
4. **Multi-Agent 严格后置**：引入条件已写明，条件未满足前不做。
5. **通用网页抓取默认关闭**：这是本项目最大的合规与安全负担。
6. **Python Worker 非必需**：Tika/PDFBox 足以覆盖 PDF/DOCX/PPTX/Markdown。
7. **AI 评测降级**：不做评测平台，做黄金集 + 离线脚本 + CI 回归。
8. **不做协同编辑（OT/CRDT）**：Workspace 协作 = 成员权限 + 并发安全编辑 + 变更记录。
9. **V1 不做关注关系与私信**：社区冷启动阶段，内容供给的价值高于社交关系。

---

## 贡献约定

- **分支**：`main`（稳定）· `develop`（集成）· `feature/*`（开发）。不直接推 `main`。
- **提交**：Conventional Commits，**小步提交**，一个 commit 只解决一个问题。
  严禁把「登录 + 论坛 + Redis + Agent + 前端首页」塞进一个 commit。
- **禁止提交**：`.env`、真实凭据、日志、构建产物、IDE 用户配置。
- **数据库**：任何 schema 变更必须走 Flyway；**禁止修改已发布的迁移脚本**。
- **提交前**：`git diff` → 确认无 Secret → 跑相关测试 → 确认编译通过 → 写清 Commit Message。

完整规范见 [`docs/00-工程规约.md`](docs/00-工程规约.md) §15。
