# 12 · Phase 执行计划（Phase 01 ~ 13）

> **效力**：本文件是当前**权威执行计划**，由用户 2026-09-17 下达，取代 `docs/08-roadmap.md` 中的版本规划部分。
> **推进方式**：按 `00-工程规约.md` §18.1，**从 Phase 01 连续推进至最后一个阶段**，
> 不再逐阶段等待确认；但每阶段的五条准出条件（§18.2）缺一不可。
> **仍然禁止**：跳阶段、合并阶段、提前实现后续阶段的能力（§18.3）。
> **冲突处理**：本文件与技术基线冲突时，以 `00-工程规约.md` §3.4 的实际验证结果为准。

---

## 总览

| Phase | 名称 | 目标版本 | 引入的关键能力 |
|---|---|---|---|
| 01 | Engineering Foundation | `v0.1.0` | 工程骨架、统一异常、配置、日志、Health、测试体系 |
| 02 | Identity & Security Foundation | `v0.2.0` | User/Role/Permission、Token 体系、限流、审计 |
| 03 | Community MVP & Bootstrap | `v0.3.0` | Post/Category/Tag/Comment/Like/Favorite/View、种子数据 |
| 04 | Workspace & Resource Authorization | `v0.4.0` | Workspace/Member/Note/Document、资源级鉴权 |
| 05 | Object Storage & Document Workflow | `v0.5.0` | MinIO、Upload→Parse→Chunk→READY/FAILED |
| 06 | Discover & Data Ingestion | `v0.6.0` | DataSource/Provenance/IngestionTask、RSS/API/GitHub Adapter、SSRF 防护 |
| 07 | Search Platform | `v0.7.0` | MySQL Baseline → Elasticsearch、统一搜索 |
| 08 | RAG Platform | `v0.8.0` | Chunk/Embedding/Vector/Citation/SSE |
| 09 | Backend Performance Engineering | `v0.9.0` | 压测基线 → Redis / 缓存 / 限流 →（必要时）MQ |
| 10 | Notification, Moderation & Admin | — | 通知、举报审核、后台 |
| 11 | Agent Runtime V1 | `v0.10.0` | Single Research Agent、Tool Gateway、SSE 事件协议、Loop Guard |
| 12 | Agent Security & Multi-Agent Evaluation | — | Tool Policy、DLP、Injection 测试集、HITL |
| 13 | Service Extraction, Observability & Reliability | `v1.0.0` | 服务拆分评估、指标/追踪、故障演练、完整文档 |

---

## Phase 01 — Engineering Foundation

**目标**：搭建真正可长期维护的工程基础。

**完成**：Spring Boot · Vue 3 + TypeScript · MySQL · Flyway · Docker Compose · 统一异常 · 统一错误响应 · 配置管理 · 日志 · Health Check · 基础测试体系 · README · Git Ignore · `.env.example`

**同时创建**：`docs/architecture.md` · `docs/adr/0001-modular-monolith.md`

**分支**：建立 `main` · `develop`

**版本**：`v0.1.0`

**提交要求**：必须至少拆分为 `build(...)` · `feat(...)` · `docs(...)` · `test(...)`，**不要一次性 Commit**。

---

## Phase 02 — Identity & Security Foundation

**实现**：User · Role · Permission · Registration · Login · Logout · Access Token · Refresh Token · Token Revocation · Password Hash · User Status · Basic Rate Limit · Audit Base · Spring Security

**重点防**：认证绕过 · 弱密码 · Token 永久有效 · 权限写死在 Controller

**建立安全测试。** 建议版本 `v0.2.0`。

---

## Phase 03 — Community MVP & Bootstrap

**实现**：Post · Category · Tag · Comment · Reply · Like · Favorite · View；Community 首页 · 列表 · 详情 · 发布 · 评论 · 互动

**建立 Synthetic Demo Seed** —— 第一次运行即可看到完整内容。

**当前只使用 MySQL。不要提前 Redis。**

**记录**：Baseline Query Performance。建议版本 `v0.3.0`。

---

## Phase 04 — Workspace & Resource Authorization

**实现**：Workspace · WorkspaceMember · WorkspaceRole · Note · Document Metadata

**重点**：Resource-Level Authorization。

必须添加**跨用户攻击测试**：用户 A **无法**读取 / 修改 / 删除 / 下载 用户 B 的 Workspace 资源。

完成 `docs/resource-authorization.md`。建议版本 `v0.4.0`。

---

## Phase 05 — Object Storage & Document Workflow

**实现**：MinIO / S3 Compatible Storage

```
Upload → Document Task → Parse → Chunk → READY / FAILED
```

**支持**：PDF · TXT · Markdown

**重点**：异步处理 · 任务状态 · Retry · Idempotency · Signed URL · File Security · Delete Cleanup

**此阶段暂不强制 MQ。先找出现有异步方案的瓶颈。** 建议版本 `v0.5.0`。

---

## Phase 06 — Discover & Data Ingestion

**实现**：DataSource · ExternalContent · DataProvenance · IngestionTask

**Adapters**：RSS · Public API · GitHub API

**支持**：Incremental Update · Deduplication · Rate Limit · Retry · Timeout · ETag / Last-Modified · Failure State

**加入 Crawler SSRF 防护。** 完成 Discover 前端。建议版本 `v0.6.0`。

---

## Phase 07 — Search Platform

先做 **MySQL 搜索 Baseline**，然后评估并引入 **Elasticsearch**。

**统一搜索**：Community · Discover · Workspace（**Workspace 必须权限过滤**）

**考虑**：索引更新 · 删除 · 重建 · 最终一致性

**建立固定 Search Benchmark Dataset。** 建议版本 `v0.7.0`。

---

## Phase 08 — RAG Platform

**实现**：Document Chunk · Embedding · Vector Retrieval · Citation · Conversation · SSE

**正确路径**：

```
Authorization → Allowed Document Scope → Retrieval → Context → LLM
```

**禁止先全库搜索。**

建立 **RAG Evaluation Dataset**，对比 **Vector Baseline**；如果必要再做 Hybrid Search / Rerank。

**不得无数据对比直接说效果更好。** 建议版本 `v0.8.0`。

---

## Phase 09 — Backend Performance Engineering

**先压测**：Hot Post · Post Detail · Like Burst · Comment · Home Feed → 建立 Baseline。

**根据瓶颈引入**：Redis · Cache Aside · Hot Key Protection · Rate Limit。

**如果同步处理确实成为瓶颈，再加入 MQ。** MQ 可逐渐用于：Notification · Like Aggregation · Document Task · Search Index Update。

**处理**：Idempotency · Retry · DLQ · Backlog

**保留优化前后真实指标。** 建议版本 `v0.9.0`。

---

## Phase 10 — Notification, Moderation & Admin

**实现**：Notification · Comment/Reply/Like Notification · Workspace Invite

**评估 SSE / WebSocket，选择合适方案。**

**Moderation**：Report · Review Queue · Rule Engine · Moderator · Human Review

**Admin**：Users · Content · Workspace · Data Source · Task · AI Usage · Audit

**敏感管理员操作必须 Audit。**

---

## Phase 11 — Agent Runtime V1（AI 纵深核心）

**不要直接 Multi-Agent。先实现一个可靠的 Single Research Agent。**

**建立 AgentRun**，状态：`CREATED` `RUNNING` `CANCELLING` `CANCELLED` `COMPLETED` `FAILED`

**实现**：Tool Calling · Agent State · Max Steps · Run Timeout · Tool Timeout · Token Budget · Tool Call Budget

**统一 Tool**：`searchCommunity` `searchDiscover` `searchOpenSource` `getContentDetail`

**Agent 不允许直接访问数据库。实现 Tool Gateway。**

### Streaming

统一 SSE Agent Event Protocol：`AGENT_STARTED` `STEP_STARTED` `TOOL_STARTED` `TOOL_FINISHED` `TEXT_DELTA` `WARNING` `RUN_COMPLETED` `RUN_FAILED`

支持 `POST /agent/runs/{runId}/cancel`，**真正停止后端 Run**。

### Loop Guard

实现 Repeated Tool Call Detection：`toolName + normalizedArguments → fingerprint`，重复达到阈值 → `LOOP_DETECTED`。**必须有对应测试。**

完成 `docs/agent-runtime.md`。建议版本 `v0.10.0`。

---

## Phase 12 — Agent Security & Multi-Agent Evaluation

**优先强化安全，不要马上拆多个 Agent。**

建立：Tool Policy Engine · Resource Authorization · Input Guard · Output DLP · Security Audit · Secret Request Detection

防止请求：数据库密码 · 管理员 Token · API Key · 其他用户私有文档

**敏感信息不能仅靠 Prompt 防守。Agent 应根本没有访问 Secret 的 Tool。**

Output DLP 检测 Password / API Key / Access Token / Private Key / Connection String → `REDACT + AUDIT`

### Prompt Injection

建立测试集：Direct · Indirect · RAG Injection · Tool Output Injection
**Retrieved Content 永远标记为 Untrusted Data。**

### HITL

为高风险操作建立 Approval 模型。若 Agent 后续支持 `delete` / `ban` / `permission change`，必须 `WAITING_APPROVAL`。

### Multi-Agent

现在**评估** Single Agent 是否出现：工具空间过大 · 职责冲突 · Context 污染 · 权限冲突 · 模型策略冲突

有证据 → 再拆 Orchestrator / Research / Community / Repository / Reviewer
**没有明显收益 → 不拆。必须把"不拆 Multi-Agent"视为合法结果。**

完成 `docs/agent-security.md`。

---

## Phase 13 — Service Extraction, Observability & Reliability

**首先评估：现在是否真的需要微服务。**

- 如果没有 → **继续 Modular Monolith**
- 如果存在 AI Runtime 长连接 / 独立扩容 / 故障隔离 → 可优先拆 **AI Runtime Service**
- 如果 Data Ingestion 资源模型明显不同 → 再考虑 **Data Service**
- **整个系统不应轻易超过 3~4 个主要服务**

**同时完善**：Metrics · Logs · Trace · Correlation ID · Health · Readiness

**至少监控**：API P95/P99 · DB Pool · Redis · MQ Lag · Crawler Failure · Document Task · LLM Latency · Token Usage · Agent Steps · Loop Detection · Security Blocks

**故障演练**：Slow SQL · Redis Failure · MQ Backlog · Thread Pool Exhaustion · LLM Timeout · Tool Timeout · External API Failure · Document Failure · Agent Loop · Client Disconnect

**最后完成**：`docs/system-design.md` · `docs/security.md` · `docs/performance.md` · `docs/data-governance.md` · `docs/ai-architecture.md` · `docs/runbook.md` · `CHANGELOG.md` · Release Notes

**执行完整**：Clean Build · Unit Test · Integration Test · Security Test · Frontend Build

**验证：从全新环境根据 README 能够成功部署。** 满足稳定条件后 → Tag `v1.0.0`。

---

## 与旧计划（`08-roadmap.md`）的关系

| 项 | 旧（V0~V5） | 新（Phase 01~13） |
|---|---|---|
| 粒度 | 6 个大版本 | **13 个阶段**，每阶段一个可验收形态 |
| 起步 | V0 = "启动即有内容"（含 Bootstrap 种子） | **Phase 01 = 纯工程基础**（无业务），种子数据推迟到 Phase 03 |
| AI | V1 就引入 Spring AI | 推迟到 **Phase 08（RAG）/ Phase 11（Agent）** |
| Git 纪律 | 未细化 | **明确分支/Commit/粒度/禁提交/Tag/ADR/Flyway 纪律** |
| 交付模板 | 9 节 | **11 节（V2）**，新增 Git History / Security / Performance |

> `08-roadmap.md` 中的**工程亮点设计、Demo Story、性能实验设计**仍然有效，作为 Phase 09~13 的素材保留。
