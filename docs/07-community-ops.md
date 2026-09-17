# 07 · 社区高并发、通知、审核、后台、可观测与压测

> 对应要求：20 Community 高并发方案 · 21 Notification · 22 Content Moderation · 28 Admin Console · 29 Observability · 30 Performance Test

**贯穿本文的原则：先测，再优化；每个优化都必须有 Before/After 数据；没有数据支撑的"优化"不进代码。**

---

## 20. Community 高并发方案

### 20.1 第一步不是加缓存，是建立基线

在写任何缓存代码之前，先在 V3 做一次**基线压测**，拿到真实数据，再决定优化对象。这是本方案与"上来就上全套中间件"的根本区别。

**基线场景（k6）与预期瓶颈：**

| 场景 | 请求混合 | 预期先暴露的问题 |
|---|---|---|
| B1 帖子详情读 | 100% `GET /posts/{id}` | 单行查询不难，难在**关联查询**（作者信息、标签、互动状态、评论数）→ N+1 或多次查询 |
| B2 首页 Feed | 混合热榜 + 推荐 + 最新 | 多模块串行查询 → 延迟叠加 |
| B3 点赞 | 100% `POST /posts/{id}/like`（同一批热点帖） | 行锁竞争 + 计数写放大 |
| B4 评论列表 | `GET /posts/{id}/comments` | 树形结构递归查询 |
| B5 混合真实流量 | 7:2:1 读:写:其它 | 综合表现与资源饱和点 |

**基线必须记录**：QPS、P50/P95/P99、错误率、MySQL QPS 与慢查询数、CPU/内存、连接池等待、GC 次数。

### 20.2 优化目标（只有 3 个接口值得优化）

不做"所有接口都上缓存"。**只优化压测中排名前三的热点**，且每个优化都要能回答"解决了什么实际问题"。

#### 热点 1：`GET /posts/{id}`

```
问题定位（基线数据）：P95 480ms，DB QPS 1200，单请求 6 次 SQL

原方案缺陷：
  · 每次请求查 post + user + tags + stats + 评论数 + 我是否点赞 = 6 次往返
  · 互动状态随登录用户变化，无法整页缓存

新方案：分层缓存 + 请求级合并
  ① 帖子主体（不含用户态）：Caffeine(L1, 10s) → Redis(L2, 5min)
     · 缓存对象为「渲染所需的完整聚合视图」DTO，不是实体
  ② 用户态（是否点赞/收藏）：Redis Hash 单次批查（SMEMBERS 或用 pipeline）
  ③ 计数（点赞/评论/浏览）：只读 Redis 计数，不回表
  ④ 本地缓存用 Caffeine，容量限制 + 记录命中率
  ⑤ 缓存失效：帖子更新时 delete key（Cache Aside）+ 延迟双删（500ms）
     + 版本号字段写入 key，避免并发写导致的脏缓存

技术取舍：
  · 放弃"强一致"：帖子编辑后最长 5 分钟才全局可见（但作者本人实时，走私域直读）
  · 换来 DB QPS 从 1200 → 约 120（命中率 90% 时）

验证：见 EX-001
```

#### 热点 2：`GET /api/feed/home`（首页多模块）

```
问题定位：串行调用 4 个模块（热榜/推荐/最新/Digest）→ 延迟相加 = 620ms

新方案：
  ① 并行化（CompletableFuture + 独立超时）
  ② 每模块独立缓存（各自 TTL 不同：热榜 60s，推荐 5min，Digest 1h）
  ③ 部分失败降级：任一模块超时 → 该模块返回上一版本缓存，不阻塞整页
  ④ 整页结果做短 TTL（30s）缓存，按 (userId 分桶 + 登录态) 区分

验证：P95 620ms → 目标 < 200ms
```

#### 热点 3：`POST /posts/{id}/like`（写热点）

见 20.4。

### 20.3 缓存三大问题（明确只做需要的部分）

| 问题 | 是否会发生 | 对策 | 说明 |
|---|---|---|---|
| **穿透**（查不存在的数据） | 会（`/posts/{随机id}` 被扫描） | ① 参数格式预校验（public_id 长度/字符集）② **空值缓存（TTL 30s）** ③ 限流 | 布隆过滤器在此场景收益有限（ID 空间巨大且稀疏）→ **不做**，避免过度设计 |
| **击穿**（热点 key 过期瞬间并发回源） | 会（热帖） | ① **逻辑过期**：value 内存 `expireAt`，过期后异步重建、旧值先返回 ② 或分布式互斥锁重建（二选一，优先逻辑过期） | 逻辑过期避免了锁等待，更适合读多写少 |
| **雪崩**（大量 key 同时失效） | 会（定时任务批量写） | ① TTL 加随机抖动 ±20% ② 多级缓存 ③ 熔断降级 | 简单有效 |
| **热点 key**（单 key 超高频） | 会（爆款帖） | ① 本地缓存（同一 JVM 内不查 Redis）② 热点探测：滑动窗口统计 key 访问频次 → 超阈值提升为"本地长 TTL 缓存 + 逻辑过期" | 本地缓存是应对热点的最有效手段（无网络往返） |

**明确不做**：不做全量数据的本地缓存（内存风险 + 一致性风险），只对"探测出的热点"做。

### 20.4 点赞 / 收藏：幂等 + 高并发 + 最终一致

```
用户点击
   │
   ▼
[1] 幂等校验（双重）
    · DB 层：UNIQUE(user_id, target_type, target_id, type)  ← 最终防线
    · Redis 层：SADD / SREM 返回 0 或 1 表示状态是否真的变化
   │
   ▼
[2] Lua 脚本原子操作（单次往返完成）
    if SISMEMBER(liked_set, uid) then return {changed:false}
    SADD(liked_set, uid); INCR(like_count); return {changed:true}
   │
   ▼
[3] 立即返回（前端乐观更新 + 服务端确认值）
   │
   ▼
[4] 异步持久化
    · 写 outbox_event（同事务，保证不丢）
    · Worker 批量消费（每 200ms 或攒够 500 条）→ 批量 INSERT IGNORE 到 reaction
    · 计数定期回写 MySQL（每 30s 增量刷）+ 每日全量对账
   │
   ▼
[5] 一致性兜底
    · 每日对账任务：Redis 计数 vs MySQL COUNT(*) 差异 → 以 DB 为准修正 Redis
    · 差异超阈值 → 告警（说明有 bug，而不是"正常误差"）
```

**技术取舍（必须写清楚）：**

- 放弃强一致：极端情况下（Redis 宕机且未持久化）可能丢失少量点赞。**接受**，因为点赞是弱业务语义。
- 为什么不用"直接同步写 MySQL"：热点帖的 `like_count` 行锁会成为瓶颈，且每次点赞 = 2 次写。
- 为什么不用 MQ 也行（V3 前）：DB 任务表 + 定时批量消费已足够，**RabbitMQ 是 V3 的可选升级**。

### 20.5 热榜：Redis ZSet + 时间衰减（不做全量排序）

```
设计：分桶 + 窗口合并 + 定时计算

写：内容发生互动时
   ZINCRBY  hot:bucket:{yyyyMMddHH}  {delta}  {itemId}
   delta = 点赞×3 + 评论×5 + 收藏×4 + 浏览×0.2（权重可配）

读：计算"最近 24 小时榜"
   ZUNIONSTORE hot:merged:24h  4
     hot:bucket:{h-1} hot:bucket:{h-2} ... WEIGHTS 1.0 0.85 0.72 ...
   → 直接读合并结果（由定时任务每 5 分钟预计算，读时零计算）

时间衰减：不依赖 ZSet 排序键，而是"权重衰减 + 分桶淘汰"
   · 桶 TTL 25 小时（自动淘汰，无需清理脚本）
   · 分桶权重体现"越新越重"，避免全量重算 score

兜底：merge key 不存在（冷启动/Redis 清空）→ 从 MySQL content_stats.hot_score 读取
```

**为什么不用"简单按浏览量排序"**：浏览量易被刷新刷高、无法表达互动质量、且是累计值没有时间窗。分桶 + 加权互动 + 时间衰减是可用性与公平性的平衡点。

### 20.6 限流与降级（并发保护的最后一道）

| 场景 | 措施 |
|---|---|
| 点赞/评论接口 | 用户级令牌桶（60/分钟）+ 同目标去重（1 秒内重复请求直接返回） |
| 首页 Feed | 系统负载 > 阈值 → 降级为纯热榜（跳过推荐计算） |
| 搜索 | ES 超时 → 返回缓存结果 + 提示 |
| 推荐 | 计算任务积压 → 使用上一次结果，不阻塞请求 |
| 全局 | 提供"只读模式"开关（写接口统一返回 503 + 明确文案） |

---

## 21. Notification

### 21.1 事件驱动（不反向依赖业务模块）

```
community ──发布──► PostLiked / CommentCreated / CommentReplied / PostFavorited
workspace ──发布──► MemberInvited / MemberJoined / DocumentReady
moderation ──发布──► ContentFolded / ContentRemoved
ai ──发布──► DigestReady
                        │
                        ▼（outbox_event 可靠投递）
              ┌───────────────────────┐
              │  Notification Service │
              │  ① 过滤（自己给自己不发）  │
              │  ② 偏好检查              │
              │  ③ 聚合（见 21.2）        │
              │  ④ 落库 notification      │
              │  ⑤ 更新未读数（Redis）    │
              │  ⑥ SSE 推送（在线时）      │
              └───────────────────────┘
```

### 21.2 聚合（防止通知轰炸）

| 场景 | 朴素做法 | 本方案 |
|---|---|---|
| 一条热帖 100 人点赞 | 100 条通知 | **合并为 1 条**："张三 等 100 人赞了你的帖子"，同类型 5 分钟窗口内聚合 |
| 同一人反复评论 | 每条都通知 | 同一用户同一目标 10 分钟内合并 |
| 被移出空间 | 静默 | 必须通知（安全相关，不可聚合不可屏蔽） |

`notification` 表设计要点：`aggregate_key`（如 `POST_LIKE:{postId}:{ownerId}:{5minBucket}`）+ `aggregate_count` + `actor_preview_json`（前 3 个行为人）。

### 21.3 推送通道：只做 SSE

```
GET /api/notifications/stream  (text/event-stream)
  · 鉴权：Token 通过 query 参数或 Sec-WebSocket-Protocol 传递（EventSource 无法自定义头）
  · 心跳：每 30s 发送 comment 保活（防代理断连）
  · 断线重连：前端指数退避重连；重连后拉一次增量（since=lastEventId）
  · 多实例：Redis Pub/Sub 广播，各实例只推给本机连接的用户
  · 在线判定：连接注册到 Redis（user_id → instance_id + conn_id）
  · 离线用户：只落库 + 未读数，下次登录拉取
```

**明确放弃 WebSocket**：本项目通知是单向的，SSE 走标准 HTTP，在鉴权、代理、重连上更简单。（如果将来做协同编辑再评估。）

### 21.4 未读数

- Redis Hash：`unread:{userId}` → `{type: count}` + `total`
- 读时 O(1)；清零操作幂等；与服务端列表首次加载做一次校正
- 前端角标使用总和，分类角标在通知中心内展示

---

## 22. Content Moderation

### 22.1 三级防线

```
L1 · 自动（发布时，同步，<50ms）
    · 敏感词（Aho-Corasick，词库热更新）
    · 正则规则（联系方式、外链轰炸、广告话术模板）
    · 频率异常（短时间内大量相似内容 → 疑似机器人）
    → 命中：拦截（明确原因）/ 标记待审（不阻塞发布但折叠）
L2 · 用户举报（异步）
    · 单条内容被 3 个不同用户举报 → 自动折叠 + 进人工队列
    · 被举报者进入"高风险窗口"，其新内容默认进队列
L3 · 人工审核（Moderator）
    · 队列按「举报数 + 内容热度 + 作者风险分」排序（先处理影响大的）
    · 处置：通过 / 折叠 / 删除 / 警告 / 禁言 / 封禁
    · 每次处置必须填写理由（进入 audit_log，可申诉）
```

### 22.2 新用户与高风险内容

| 规则 | 说明 |
|---|---|
| 新用户前 3 帖先审 | 减少首轮垃圾内容（体验代价小） |
| 含外链的帖子加权检查 | 广告主要形态 |
| 大改编辑（内容变化 >70%）重新过 L1 | 防"先发正常内容再改成广告" |
| 处置与内容状态联动 | `content_item.status: ACTIVE → FOLDED / REMOVED`，**同时从搜索索引中移除**（REMOVED 时） |
| 申诉 | 用户可见处置理由 + 申诉入口 → 复审（由不同 Moderator 处理，避免自审） |
| LLM 辅助判定（V4） | 对高举报内容做分类辅助（含 `trust_level=UNTRUSTED` 处理，输入不进系统指令）；**结论仍由人确认** |

### 22.3 明确不做

- 不自研内容分类模型（成本远超收益）
- 不做实时视频/图片识别（本平台图片量级不需）
- 不做"AI 全自动删除"（风险不可控，且容易造成严重误伤）

---

## 28. Admin Console

### 28.1 首页指标（含定义，避免"看起来专业但没意义"的数字）

| 指标 | 定义 | 数据来源 | 为什么看它 |
|---|---|---|---|
| DAU | 当日有任一鉴权请求的去重用户 | `audit_log` / 访问日志聚合 | 基础活跃 |
| 新注册 | 当日新增用户 | `user` | 增长 |
| Posts / Comments | 当日新增 | 业务表 | 内容供给 |
| 互动率 | 互动数 / 内容曝光数 | Redis 计数 + view_log | 内容质量信号 |
| AI Requests | 当日 AI 调用次数 + 成功率 | `ai_run` | AI 使用与稳定性 |
| Token Usage / Cost | 按用户、模型、日聚合 | `token_usage` | **成本控制** |
| Search Requests | 搜索次数 + 无结果率 + 点击率 | `query_log` | 检索质量（无结果率是关键） |
| Crawler Success Rate | 成功 run / 总 run | `fetch_run` | 数据供给健康度 |
| Crawler Failure | 失败数 + DLQ 数量 | `fetch_error` | 需要人工介入的量 |
| Cache Hit Rate | 按 key 前缀分组 | Micrometer 缓存指标 | 缓存是否有效（**含本地/远程分层**） |
| MQ / 任务积压 | 各队列待处理数 + 最老任务年龄 | 队列统计 | 异步链路是否卡住 |
| Document Tasks | 各状态文档数 + 平均处理时长 + 失败率 | `document` | 文档管道健康度 |
| API 延迟 | P50/P95/P99 按接口 | Micrometer | 性能回归发现 |
| 慢 SQL 数 | 每分钟 >200ms 查询数 | MySQL slow log 采集 | 隐患预警 |

### 28.2 页面清单

| 页面 | 能力 | 权限 |
|---|---|---|
| Dashboard | 上述指标 + 趋势图 + 告警状态 | ADMIN |
| User Management | 列表/搜索/详情/角色调整/禁言/封禁（**危险操作二次确认 + 审计**） | ADMIN |
| Workspace Management | 列表/概览/异常空间（超大、超量）/冻结 | ADMIN |
| Community Management | 内容列表/筛选/删除/置顶/加精 | MODERATOR+ |
| Report & Moderation | 审核队列/处置/理由录入/申诉复审 | MODERATOR |
| Data Source Management | 增删改数据源、许可信息、启用停用、测试抓取 | ADMIN |
| Crawler Tasks | 任务列表/运行历史/失败明细/DLQ 重放/手动触发 | ADMIN |
| Search Index | 索引状态/文档数/一致性校验/重建/别名切换 | ADMIN |
| Knowledge Source | 公共知识库内容清单 + 来源追溯钻取 | ADMIN |
| AI Usage | Token 用量/成本/按用户与模型/异常用量 | ADMIN |
| Model Configuration | 模型启停/权重/降级顺序/Prompt 模板版本 | ADMIN |
| Rate Limit | 各维度限流配置（改配置而非改代码） | ADMIN |
| System Monitoring | 指标快照 + 依赖健康（MySQL/Redis/ES/MinIO）+ 队列 | ADMIN |
| Audit Log | 全量操作审计（可筛选、不可删除） | ADMIN |

**设计原则**：后台不是"把表 dump 出来"，每一个页面都要能回答一个**运维问题**（"现在有什么不对劲？我要做什么？"）。

---

## 29. Observability

### 29.1 三类信号

| 类型 | 内容 | 技术 |
|---|---|---|
| **Metrics** | 四大黄金信号（延迟/流量/错误/饱和）+ 业务指标 | Micrometer → Prometheus → Grafana |
| **Logs** | 结构化 JSON，含 `traceId / userId / module / event` | Logback + MDC；异常不打印敏感信息 |
| **Traces** | 跨模块链路（HTTP → Service → DB → LLM） | Micrometer Tracing + OTel（采样率可配） |

### 29.2 关键指标清单

```
HTTP:        http_server_requests_seconds{uri,method,status}  → P50/P95/P99, 错误率
DB:          hikaricp_connections_* , 慢查询计数, 事务回滚数
Redis:       cache_hits/misses{cache}, 命令延迟, 连接数
ES:          search_latency, 索引刷新延迟, 集群健康, bulk 拒绝数
MQ/任务:     queue_depth, oldest_task_age_seconds, dlq_size, consume_rate
AI:          ai_request_duration{stage}, llm_tokens_total{model}, citation_invalid_total
Crawler:     crawler_run_total{source,status}, items_inserted, dedup_ratio, http_errors
文档管道:     document_task_duration{stage}, document_status_gauge{status}
JVM:         jvm_gc_pause_seconds, jvm_memory_used, threads（虚拟线程模式需额外观测载体线程）
业务:         posts_created, comments_created, likes_total, searches, no_result_search_ratio
```

### 29.3 告警规则（先做 8 条，不做几十条）

| # | 规则 | 阈值（示例） | 级别 |
|---|---|---|---|
| A1 | API P95 延迟 | > 1s 持续 5min | P2 |
| A2 | API 5xx 错误率 | > 1% 持续 3min | P1 |
| A3 | 慢 SQL 数量 | > 10/min | P2 |
| A4 | 缓存命中率 | < 70% 持续 10min | P3 |
| A5 | 队列积压 | 最老任务 > 5min 或 DLQ > 100 | P2 |
| A6 | 采集失败率 | 单源连续 3 轮 > 50% | P2 |
| A7 | LLM 错误率 | > 20% 持续 5min | P2 |
| A8 | 磁盘 / 内存 | 磁盘 > 85% 或 OOM 重启 | P1 |

### 29.4 Dashboards（5 个，够用即止）

1. **API 健康**：请求量、延迟分位、错误率、Top 慢接口
2. **数据与缓存**：MySQL 连接与慢查询、Redis 命中率与内存、ES 健康
3. **异步链路**：队列深度、任务年龄、DLQ、文档管道各状态
4. **数据采集**：各源成功率、新增量、去重率、失败分布
5. **AI 与成本**：请求量、各阶段耗时、Token 消耗、引用校验失利率

---

## 30. Performance Test

### 30.1 方法与纪律

```
假设 → 基线测量 → 定位瓶颈 → 实现优化 → 回归测量 → 结论与局限
```

**三条纪律：**
1. 没有基线数据不优化。
2. 每次只改一个变量（否则无法归因）。
3. 所有结果落盘到 `docs/experiments/EX-xxx.md`，**包含环境、脚本、原始数据**，任何人可复现。

### 30.2 实验记录模板

```markdown
# EX-001 · 帖子详情接口多级缓存优化

## 假设
帖子详情 P95 延迟主要来自单请求 6 次数据库往返；引入两级缓存可将 DB QPS 降低 ≥80%。

## 环境
- 机器：MacBook Pro M3 / 16GB（单机，应用 2 实例）
- 版本：commit 9f2c1ab
- 依赖：MySQL 8.0.36(4C/2GB) / Redis 7.2(1C/1GB)
- 数据量：content_item 12,000 / reaction 180,000 / comment 60,000

## 脚本
k6 run scripts/load/post-detail-baseline.js --vus 200 --duration 3m

## 结果
| 指标 | Before | After | 变化 |
|---|---|---|---|
| QPS | 1,240 | 5,180 | +318% |
| P50  | 62ms  | 11ms  | -82%  |
| P95  | 480ms | 46ms  | -90%  |
| P99  | 910ms | 132ms | -85%  |
| 错误率 | 0.42% | 0% | — |
| DB QPS | 1,180 | 96 | -92% |
| 缓存命中率 | — | 94.1% | — |
| CPU（应用） | 78% | 41% | — |

## 结论
假设成立。代价：帖子编辑后最长 5 分钟全局可见（作者实时）。

## 局限
- 单机环境，未验证多实例下本地缓存一致性问题
- 未测试 Redis 宕机场景（见 EX-007）
- 热点 key 场景未覆盖（见 EX-005）
```

### 30.3 计划中的实验清单（V3-V5）

| 编号 | 实验 | 类型 |
|---|---|---|
| EX-001 | 帖子详情多级缓存 | 优化 |
| EX-002 | 首页 Feed 并行化与模块降级 | 优化 |
| EX-003 | 点赞 Lua + 异步落库 | 优化 |
| EX-004 | 热榜分桶计算 vs 全量排序 | 优化对比 |
| EX-005 | 热点 key 本地点缓存效果 | 优化 |
| EX-006 | 缓存击穿（逻辑过期 vs 互斥重建） | 对比 |
| EX-007 | Redis 宕机故障演练 | 故障 |
| EX-008 | 慢 SQL 定位与索引优化 | 优化 |
| EX-009 | MQ 积压恢复能力（生产者 >> 消费者） | 故障 |
| EX-010 | 线程池耗尽与虚拟线程对比 | 故障/优化 |
| EX-011 | LLM 超时与熔断降级 | 故障 |
| EX-012 | 采集端外部 API 失败与熔断 | 故障 |
| EX-013 | 内存泄漏定位（OOM 前的堆分析） | 故障 |
| EX-014 | 文档管道吞吐与并发度调优 | 优化 |

### 30.4 压测场景库（k6 脚本）

| 脚本 | 目的 | 关键参数 |
|---|---|---|
| `browse.js` | 浏览型负载（读为主） | 7:2:1 读:写 |
| `hotspot.js` | 热点帖 + 高频点赞 | 单 key 集中 |
| `search.js` | 搜索混合查询 | 20 个真实 query |
| `upload.js` | 文档上传与处理 | 并发上传 + 观察任务队列 |
| `ai-chat.js` | AI 问答（需 mock LLM 或限速） | 控制 Token 消耗 |
| `crawler.js` | 采集链路压力（mock 外部源） | 观察出站隔离效果 |
| `mixed-soak.js` | 长时混合（30min） | 发现内存泄漏与连接泄漏 |

> **禁止伪造性能数据**：所有展示的数字必须能指向某个 `EX-xxx.md`，其中包含可复现的脚本与配置。这条纪律写进 `CONTRIBUTING.md`。
