# 02 · 模块边界、技术选型与范围裁剪

> 对应要求：7 模块边界 · 34 技术选型和取舍 · 35 哪些方案属于过度设计
> 同时承载原需求正文的「二十一、技术路线」与「二十二、架构演进原则」

---

## 7. 模块边界

### 7.1 为什么是 Modular Monolith

不是"暂时不拆微服务"，而是**主动选择**：本项目的复杂度来源是"业务域多 + 异步链路长 + 权限要求高"，不是"部署单元多"。

在这个前提下，微服务带来的是纯成本：

| 引入微服务得到的 | 代价 |
|---|---|
| 独立部署 | 分布式事务 / 最终一致性心智负担 |
| 独立扩容 | 10+ 个仓库、CI/CD 流水线、服务发现、链路追踪配置 |
| 技术异构 | 跨服务调试从"断点"变成"看日志" |
| 故障隔离 | 单机资源反而被 10 个 JVM 瓜分 |

**而 Modular Monolith 能拿到其中 80% 的好处**：模块边界清晰 + 进程级角色分离（api / worker / scheduler 独立启动），剩下 20% 等到真的需要独立机器时再拆——那时拆分成本很低，因为边界已经干净。

### 7.2 模块清单与职责

| 模块 | 职责（一句话） | 拥有的核心表 | 对外暴露的服务接口 |
|---|---|---|---|
| `platform` | 跨模块基础设施：异常、审计、幂等、限流、缓存抽象、事件总线、Provenance | `audit_log`, `outbox_event`, `idempotency_record`, `data_provenance` | `EventBus`, `AuditService`, `IdempotencyGuard`, `ProvenanceService` |
| `identity` | 用户、认证、会话、角色、权限点、兴趣标签 | `user`, `user_credential`, `role`, `permission`, `user_role`, `refresh_token`, `user_interest` | `UserQueryService`, `AuthService`, `AuthorizationService` |
| `workspace` | 空间、成员、邀请、文档元数据、笔记、任务、空间级权限 | `workspace`, `workspace_member`, `workspace_invite`, `document`, `document_version`, `note`, `workspace_task` | `WorkspaceAccessService`, `DocumentService`, `WorkspaceQueryService` |
| `content` | **统一内容投影**：把 Community 帖、外部资源、开源项目、Workspace 公开内容归一为 `ContentItem` | `content_item`, `content_item_tag`, `tag`, `category`, `content_stats` | `ContentQueryService`, `ContentIndexWriter` |
| `community` | 发帖、评论、互动、热榜、Feed | `post`, `post_revision`, `comment`, `reaction`, `view_log`, `report` | `PostService`, `CommentService`, `ReactionService` |
| `ingestion` | 数据源、采集任务、去重、清洗、分类入库 | `data_source`, `fetch_job`, `fetch_run`, `fetch_error`, `dedup_record`, `crawl_policy` | `IngestionService`, `SourceAdminService` |
| `search` | 索引管理、查询理解、多路召回、融合、权限过滤、重排 | `search_doc`(ES), `chunk`(ES), `query_log`, `search_suggestion` | `SearchService`, `IndexAdminService` |
| `ai` | 会话、RAG、工具调用、Agent、Digest、模型配置、Token 计量、评测 | `ai_conversation`, `ai_message`, `ai_citation`, `ai_run`, `ai_tool_call`, `model_config`, `token_usage`, `eval_case` | `AiChatService`, `RagRetrievalService`, `DigestService` |
| `notification` | 通知生产、聚合、未读数、SSE 推送 | `notification`, `notification_pref`, `sse_session` | `NotificationService`（仅消费事件，不被其他模块反向调用） |
| `moderation` | 举报受理、审核队列、敏感词、处置裁定 | `moderation_task`, `moderation_verdict`, `sensitive_word` | `ModerationService`（产出裁定事件） |
| `admin` | 后台装配层：聚合各模块的只读视图 + 运维操作入口 | 无自有表（只读） | 无（最上层） |

### 7.3 依赖方向（硬约束，用 ArchUnit 测试守护）

```
                         ┌──────────────┐
                         │    admin     │  装配层（只读聚合）
                         └──────┬───────┘
    ┌───────────┬───────────┬───┴───────┬────────────┬──────────┐
    ▼           ▼           ▼           ▼            ▼          ▼
┌────────┐ ┌────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│community│ │ingestion│ │ search │ │   ai     │ │notification│ │moderation│
└───┬────┘ └───┬────┘ └───┬────┘ └────┬─────┘ └────┬─────┘ └───┬──────┘
    │ 写投影   │ 写投影    │ 读索引      │ 读检索+权限 │ 只消费事件  │ 读内容+出事件
    └──────────┴─────┬─────┴────────────┴────────────┘            │
                     ▼                                              │
              ┌─────────────┐      ┌──────────────┐                │
              │   content   │◄─────│  workspace   │◄───────────────┘
              └──────┬──────┘      └──────┬───────┘
                     ▼                    ▼
              ┌────────────────────────────────┐
              │           identity             │
              └───────────────┬────────────────┘
                              ▼
              ┌────────────────────────────────┐
              │           platform             │
              └────────────────────────────────┘
```

**六条铁律：**

1. **禁止跨模块直接访问对方的数据表。** 必须通过对方暴露的 `*Service` / `*QueryService` 接口。
2. **禁止反向依赖。** `content` 不知道 `community` 存在（社区通过领域事件写入投影）；`notification` 不认识 `community`（只订阅事件名）。
3. **`identity` 是纯依赖方**，任何模块都可只读引用，但没人反过来依赖业务模块。
4. **跨模块写操作只用领域事件**（`PostPublished`、`DocumentReady`、`VerdictIssued`…），事件经 `outbox_event` 表保证可靠投递。
5. **同步调用只用于读路径**（要立刻拿到结果），写路径与副作用一律异步。
6. **ArchUnit 固化以上规则**，违反即 CI 失败 —— 让"架构边界"从文档里的一句约定，变成构建期不可绕过的约束。

### 7.4 模块内分层（每个模块都是这个形状）

```
com.campushub.<module>
├─ api/             Controller、Request/Response DTO、参数校验        （依赖 application）
├─ application/     应用服务、用例编排、事务边界                        （依赖 domain + infrastructure 接口）
├─ domain/          实体、值对象、领域规则、领域事件（不依赖 Spring）    （零框架依赖）
└─ infrastructure/  Mapper、ES Client、Redis、MQ Producer/Consumer     （实现 domain 定义的端口）
```

**领域层零框架依赖**是刻意的：它让"业务规则"可以被纯 JUnit 测试，也让未来拆分时可以直接搬走。

### 7.5 运行角色（一个代码库，多种进程形态）

| 角色 | 启动方式 | 承担 | 扩容维度 |
|---|---|---|---|
| `api` | `--spring.profiles.active=api` | HTTP 接口、SSE、鉴权 | QPS / 连接数 |
| `doc-worker` | `--spring.profiles.active=doc-worker` | 文档解析 / 切片 / 向量化 | 文档吞吐、CPU |
| `ai-worker` | `--spring.profiles.active=ai-worker` | embedding 批处理、Digest 生成、Agent 长任务 | LLM 并发、Token 预算 |
| `scheduler` | `--spring.profiles.active=scheduler` | 采集调度、热榜计算、点赞对账、索引清理 | 定时任务数量（单实例） |
| `web` | 前端构建产物 | 静态资源 | CDN |

> 这就是"未来微服务"的低成本孵化器：**角色已经分离，只是暂时共享一个 JAR。** 当某个角色需要独占机器时，改一行部署配置即可。

---

## 34. 技术选型与取舍

| 层 | 选择 | 主要备选 | 选它的理由 | 明确付出的代价 |
|---|---|---|---|---|
| 语言/运行时 | **Java 21** | Kotlin / Go | 虚拟线程（Loom）让高并发 IO 大幅简化；JVM 生态的库、运维与故障排查工具链最完整 | 启动慢、内存占用高（用 CDS + GraalVM 可选优化） |
| 应用框架 | **Spring Boot 4.1.1** | Quarkus / Micronaut | Spring Security / Data / 测试支持一体化，且本项目所需能力均在一等公民范围内 | 约定优于配置带来"看不见的行为"，需要靠 ArchUnit + 显式配置把边界重新显性化 |
| Web | Spring MVC（虚拟线程模式） | WebFlux | 本项目瓶颈在 DB/LLM 而非线程；虚拟线程已能支撑高并发阻塞式调用；**响应式带来的心智成本远超收益** | 极端长连接场景需谨慎 |
| 持久层 | **MyBatis-Plus** + 手写复杂 SQL | JPA / JOOQ | SQL 完全可控（性能优化与压测演示需要精细 SQL）；避免 JPA 的 N+1 与懒加载陷阱 | 需要手写更多样板代码 |
| 主库 | **MySQL 8.0** | PostgreSQL | 生态与运维熟悉度；JSON 列 + 全文索引足够兜底 | 向量/全文能力弱于 PG（所以把这两件事交给 ES） |
| 缓存 | **Redis 7** | 仅本地 Caffeine | 分布式计数、ZSet 热榜、限流、Stream、分布式锁一站解决 | 引入一致性负担（用 Cache Aside + 逻辑过期 + 对账收敛） |
| 检索 | **Elasticsearch 8.x** | Meilisearch / OpenSearch / PG 全文 | 一个组件同时提供 BM25、`dense_vector` kNN、filter、聚合、RRF | 运维成本高、内存要求大（部署门槛，用 Docker Compose 缓解） |
| 向量检索 | **ES `dense_vector` (HNSW)** | Milvus / Qdrant / pgvector | chunk 量级在百万内，ES 完全够用；**权限 filter 与向量在同一查询里完成**，避免"先检索后过滤"的泄漏风险 | 超大规模（>千万级）需要迁移，届时再评估 |
| 消息队列 | **V0-V2: DB 任务表 + 延迟唤醒；V3: RabbitMQ** | Kafka / RocketMQ / Redis Stream | 需求是任务语义（重试 / 延迟 / 死信），RabbitMQ 的 DLX + TTL 天然匹配 | Kafka 的吞吐/回放能力用不上，**引入即负担** |
| 对象存储 | **MinIO（本地 S3 兼容）** | 云 OSS / 本地磁盘 | 本地可跑通，接口与 OSS 一致，未来切云零改动 | 需要自建运维 |
| 文档解析 | **Apache Tika + PDFBox + POI** | Python Worker（unstructured / LangChain） | 纯 Java，无需第二语言栈；Tika 覆盖 PDF/DOCX/PPTX/XLSX/HTML/MD | OCR 能力弱（扫描版 PDF 后期再考虑）
| AI 框架 | **Spring AI 1.x** | LangChain4j / 裸 HTTP | 与 Spring 生命周期/DTO/可观测性天然集成；**ChatClient + Advisor 抽象干净** | 生态年轻，部分能力需自己补 |
| 模型接入 | OpenAI 兼容协议 + **DashScope（通义）** | 单一厂商 | 可切换、可降级；国内可达性 | 需要统一 `ModelConfig` 抽象与降级策略 |
| Embedding | `text-embedding-v3`(DashScope) / `bge-m3`(本地可选) | 自训 | 中文效果与成本平衡 | 换模型需全量重建索引（用 `embedding_model` 字段做版本管理） |
| 前端 | **Vue 3 + Vite + TS + Pinia + Arco Design** | React + Next.js | 开发效率高、组件齐全、国内团队通用；**SPA 足够**（内容站 SEO 可通过预渲染兜底） | SSR/SEO 弱于 Next.js（V3 后视数据再决策） |
| 实时推送 | **SSE** | WebSocket | 通知是单向的；SSE 走 HTTP，代理/鉴权/断线重连都更简单 | 双向场景（协同编辑）需要换方案（本项目不做） |
| 鉴权 | JWT Access Token(15min) + Refresh Token(30d, 可撤销) | Session | 无状态易水平扩展；配合 `refresh_token` 表实现撤销 | 需要自己处理吊销与轮换 |
| 可观测 | **Micrometer + Prometheus + Grafana + OTel** | SkyWalking / 自研 | 指标维度与压测数据天然对口 | 需要写 Dashboard 与告警规则 |
| 压测 | **k6** | JMeter | 脚本即代码，可版本化、可 CI 复现（压测记录可验证性要求） | 场景编写需要一点 JS |
| 部署 | **Docker Compose** | K8s | 单机 8 个容器足够演示；K8s 对个人项目是纯负担 | 无法演示弹性伸缩（用角色分离 + 多实例模拟） |
| 数据迁移 | **Flyway** | Liquibase | 与 Spring Boot 集成好，SQL 直白可审计 | 复杂分支回滚需手工处理 |

### 34.1 技术引入的版本节奏（不要一次全上）

| 版本 | 新增技术 | 明确不引入 |
|---|---|---|
| V0 | Spring Boot, MySQL, MyBatis-Plus, Flyway, Vue3, Docker Compose | Redis / ES / MQ / AI / **对象存储** 全部不引入（V0 用 MySQL 全文索引 + LIKE 兜底搜索；配图存本地卷，V1 迁 MinIO） |
| V1 | Redis（会话/计数）、Spring AI、对象存储、SSE | ES / MQ |
| V2 | Elasticsearch、Scheduler、Ingestion 全链路 | RabbitMQ |
| V3 | RabbitMQ、Prometheus/Grafana、k6、多级缓存 | 向量数据库 |
| V4 | Rerank、Agent、MCP、评测回归 | 微服务 |
| V5 | 混沌/故障演练工具链 | K8s |

> **每一版只引入"当前问题真正需要"的组件。** 这份节奏表本身就是一个可讲述的工程判断力证明。

---

## 35. 哪些方案目前属于过度设计

逐条判定。**"过度设计"的定义：解决了当前不存在的问题，或者用更高的复杂度换取了更低的收益。**

### 35.1 明确判定为过度设计（本阶段不做）

| # | 方案 | 判定 | 替代方案 | 什么时候才值得做 |
|---|---|---|---|---|
| O1 | **Kafka / Pulsar 事件流平台** | 过度 | DB 任务表 → RabbitMQ | 需要事件回放、多消费组独立消费、跨团队事件契约治理时 |
| O2 | **独立向量数据库（Milvus/Qdrant 集群）** | 过度 | ES `dense_vector` | chunk 超过 1000 万，或需要多路向量混检 + 高 QPS 低延迟时 |
| O3 | **Multi-Agent 编排（Orchestrator + 5 个 Agent）** | 过度 | Workflow + Tool Calling + 单 Agent | 出现"职责与上下文真实冲突"的案例 ≥3 个时（判定条件见 06 文档） |
| O4 | **微服务拆分** | 过度 | Modular Monolith + 运行角色分离 | 出现独立扩容/故障隔离/团队分治的真实诉求时 |
| O5 | **分布式事务（Seata / TCC / SAGA 框架）** | 过度 | Outbox 表 + 幂等消费 + 定时对账 | 出现跨库强一致的资金类场景时（本项目不存在） |
| O6 | **所有热点数据都上多级缓存 + 布隆过滤器** | 过度 | 先压测定位真实热点，**只优化 Top 3 热接口** | 压测证明确实存在穿透/击穿/雪崩时 |
| O7 | **通用网页爬虫 + 全站适配** | 过度且违规风险高 | 白名单域 + 官方 API/RSS 优先，`WebPageAdapter` 默认关闭 | 有明确授权与合规审查后（本项目不建议） |
| O8 | **深度学习推荐（双塔召回 + 排序模型）** | 过度 | 规则 + 热度 + 时间衰减 + MMR | 有 ≥10 万次真实互动行为、且规则方案指标饱和时 |
| O9 | **协同编辑（OT / CRDT）** | 过度 | 乐观锁 + 版本历史 + 冲突提示 | 真实用户抱怨"不能同时编辑"时 |
| O10 | **Python 文档 Worker** | 过度 | Tika/PDFBox/POI | 需要 OCR、复杂版面还原、或专用模型推理时 |
| O11 | **AI Evaluation 平台（可视化评测 + 数据集管理）** | 过度 | 30 条黄金集 + 离线脚本 + CI 回归 | 有 ≥3 个 Prompt 变体需要 A/B 对比时 |
| O12 | **多租户 Schema/DB 级隔离** | 过度 | 行级 `workspace_id` + 强制作用域注入 | 出现企业级付费客户要求物理隔离时 |
| O13 | **全链路灰度 / 功能开关平台** | 过度 | 配置文件 + Feature 常量 | 有多版本并行发布需求时 |
| O14 | **K8s + Helm + 服务网格** | 过度 | Docker Compose | 需要多机弹性时 |
| O15 | **用户关注关系 + 私信 + 好友** | 过度（V1-V2） | "关注话题"替代"关注人" | 社区有稳定 DAU 且用户主动要求时 |
| O16 | **每次请求都做 Query Rewrite + HyDE + 多轮改写** | 过度（V4 前） | 仅对"短查询/代词指代"做轻量改写 | 评测集证明改写带来显著提升时 |

### 35.2 判定为"值得做，但要控制深度"的方案

| # | 方案 | 控制方式 |
|---|---|---|
| K1 | 统一搜索（BM25 + 向量 + 融合 + 重排） | V2 只做 BM25 + 向量 + RRF；rerank 用轻量规则（标签命中/时效/权威度）而非模型 |
| K2 | 可观测性 | 只做 5 个核心 Dashboard + 8 条告警规则，不做全指标覆盖 |
| K3 | 权限三层防线 | 数据层用统一拦截器而非逐表手写，避免样板代码爆炸 |
| K4 | AI 安全（Prompt Injection 防护） | 做"内容分级 + 指令隔离 + 引用校验"三件事，不做对抗训练/专门检测模型 |
| K5 | Content Moderation | 敏感词 + 规则 + 人工队列；**不做自研内容分类模型**，必要时用 LLM 辅助判定 |
| K6 | 数据采集 | 3-5 个高质量来源做深，而不是 30 个来源做浅 |

### 35.3 我建议**加强**（你的需求里低估了的部分）

| # | 项目 | 为什么加强 |
|---|---|---|
| U1 | **Provenance 提前到 V0** | 你的需求把 Data Provenance 放在第四节，但版本规划里数据平台是 V2。地基不能后补：`content_item` 从第一行数据就要带 `provenance_id`，否则历史数据无法追溯，补数据成本极高 |
| U2 | **发布到 Community 的"可见性确认"做成产品化流程** | 这是"私有内容绝不自动公开"的落地方式：预览 + 字段级脱敏提示 + 撤回 24 小时窗口。这是产品差异化点，不只是安全要求 |
| U3 | **推荐理由可解释性** | 你的需求说"可解释方案"，我建议进一步把它做成 UI 上的"因为你收藏过 X"标签——这是低成本高感知的亮点 |
| U4 | **Bootstrap 的"幂等 + 可重放"** | Demo 数据不是一次性脚本，而是可重复执行的初始化任务（可重置、可增量、可指定规模） |
| U5 | **压测实验记录目录化** | `docs/experiments/EX-xxx.md` 统一模板，这是"禁止伪造性能数据"最有效的执行方式 |
