# 06 · 统一搜索、RAG 与 Agent

> 对应要求：17 Search 架构 · 18 RAG 架构 · 26 Agent 架构 · 27 Multi-Agent 的引入条件

---

## 17. 统一搜索架构

### 17.1 为什么"统一"是核心难点

用户搜 "Spring AI"，期望一次看到：社区里有人踩的坑、官方博客的动态、GitHub 上新出的项目、自己空间里存的笔记。

这四个来源：**数据形态不同、写入频率不同、权限规则不同、排序逻辑不同**。统一搜索就是要在这些差异之上给出一份"看起来理所当然"的结果列表。

**解法 = 统一内容投影（`ContentItem`）+ 分路召回 + 融合重排 + 前置权限过滤。**

### 17.2 索引设计

**两个索引，职责分明：**

| 索引 | 存什么 | 用途 |
|---|---|---|
| `content_index` | `ContentItem` 投影（帖子/资源/项目/公开文档的**摘要层**） | 列表级召回与分组 |
| `chunk_index` | 文档切片与长文段落（**细节层**） | 精确证据召回与引用定位 |

**`content_index` 关键 mapping：**

```
id                keyword
item_type         keyword        # POST | RESOURCE | OSS_PROJECT | WORKSPACE_PUBLIC
title             text(ik_max_word) + keyword 子字段
summary           text(ik_max_word)
body              text(ik_max_word)            # 长文才逐段展开，短内容直接进
tags              keyword + text(ik_smart)
category_id       keyword
author_user_id    keyword
author_name       keyword
source_id         keyword
source_name       keyword
published_at      date
quality_score     float          # 排序因子
hot_score         float          # 由互动计算，定期回写
visibility        keyword        # ★ 权限字段
workspace_id      keyword        # ★ 权限字段
owner_user_id     keyword        # ★ 权限字段
status            keyword
embedding         dense_vector(dims=1024, index=true, similarity=cosine, hnsw.m=16, ef_construction=200)
embedding_model   keyword        # 换模型时定位需重建的文档
language          keyword
```

**索引策略判断（一处重要取舍）：** 不做"每类来源一个索引"。理由是跨类型相关性排序需要统一打分，多索引会迫使我们在应用层做二次归一化，反而更复杂且不可控。代价是 mapping 字段偏多、小部分字段稀疏——这个代价可以接受。

### 17.3 查询管线

```
User Query
   │
   ▼
[1] 归一化       全半角、大小写、繁简、去首尾空白、限制长度(≤100 字符)
   │
   ▼
[2] Query Understanding
   │   · 分词（IK）+ 同义词扩展（synonym：AI/人工智能/LLM）
   │   · 意图判定：找资源 / 找讨论 / 找项目 / 找知识（问句）/ 找人
   │   · 时间词抽取："最近"→ 30d，"今天"→ 1d，"2025" → 年区间
   │   · 实体识别：命中标签字典 → 提升对应 tag 权重
   │   · 拼写纠错（可选，基于 suggestion 词典）
   ▼
[3] 多路召回（并行执行，各取 Top-K）
   │   ① BM25 字段加权：title^3, tags^2, summary^2, body^1
   │   ② 向量 kNN：embedding(k=100)
   │   ③ 标签精确：term(tags)  — 保证精确命中的确定性
   │   ④ 热度兜底：hot_score desc（仅无结果或结果不足时补位）
   │   ⑤ 用户私域：workspace 内文档（仅当登录且有授权空间）
   │   · 每路都带 filter（见 17.4）
   ▼
[4] Fusion（RRF 倒数排名融合）
   │   score = Σ_branch  w_branch / (k + rank_branch)      k=60
   │   w: BM25=1.0  Vector=1.2  Tag=1.5  Hot=0.3  Workspace=1.0
   ▼
[5] Permission Filter（前置，见 17.4 — 这一步在召回时就已完成，此处仅做最终校验）
   ▼
[6] Rerank
   │   V2：规则重排（标签命中 + 时效 + 权威度 + 类型偏好权重）
   │   V4：轻量 cross-encoder 或 rerank API（仅对 Top-50 调用，控制延迟）
   ▼
[7] 分组聚合与去重
   │   · 跨源同内容合并（provenance 关联，只展示权威源）
   │   · 按 item_type 分组，前端 Tab 展示
   ▼
[8] 结果返回 + query_log 落库（用于热搜、纠错词典、推荐信号）
```

### 17.4 权限过滤的位置（本项目安全设计的关键决策）

**权限过滤放在 ES query 的 `filter` 子句里，与打分同时发生：**

```json
{
  "query": {
    "bool": {
      "must":   [ { "multi_match": { "query": "...", "fields": [...] } } ],
      "filter": [
        { "term":  { "status": "ACTIVE" } },
        { "bool": { "should": [
            { "term":  { "visibility": "PUBLIC" } },
            { "terms": { "workspace_id": ["101","102"] } }
        ], "minimum_should_match": 1 } }
      ]
    }
  }
}
```

**为什么不能放在最后：**

| 后置过滤的问题 | 后果 |
|---|---|
| 召回 Top-50 中 40 条是私有内容 | 用户只看到 10 条，且**召回质量被严重稀释** |
| 私有内容已进入 LLM 上下文 | 存在泄漏路径（摘要、引用、措辞泄露） |
| 需要逐条二次查询权限 | N+1 鉴权，延迟不可控 |

**`authorizedWorkspaceIds` 来自 `AuthorizationService`（唯一实现）**，短 TTL 缓存（60s）；成员被移除时主动失效缓存 key，保证"立刻失去访问权"。

### 17.5 结果呈现（"统一"要让用户看得见）

```
搜索结果页
├─ 综合（融合排序，混合类型）
├─ 社区讨论（POST）        12 条
├─ 公开资源（RESOURCE）     28 条
├─ 开源项目（OSS_PROJECT）  9 条
└─ 我的空间（WORKSPACE）    5 条   ← 仅登录且有权时出现

每条卡片统一展示：标题 / 摘要高亮 / 类型徽章 / 来源（含外部来源名）/ 时间 / 互动数
外部内容额外展示：来源平台 + "跳转原文" + 许可标记（如需署名）
```

### 17.6 性能与降级

| 项 | 目标 / 策略 |
|---|---|
| 延迟目标 | P95 < 300ms（不含 LLM），P99 < 800ms |
| 并行召回 | 各路召回并行执行（`CompletableFuture` + 超时控制，单路超时 200ms 则丢弃该路） |
| 结果缓存 | 热门 query（归一化后）缓存 60s；**登录用户结果因权限不同而按 authorizedScope 分桶缓存**，避免缓存泄漏 |
| 深翻页 | 游标分页，最大返回 500 条；超出提示"请细化条件" |
| ES 不可用 | 降级到 MySQL `FULLTEXT`（ngram）+ 标签 LIKE，返回结果并提示"检索能力降级" |
| 向量不可用 | 降级为仅 BM25 |
| Rerank 超时 | 使用 RRF 融合分 |
| 热搜/联想 | Redis ZSet；联想词基于 `search_suggestion` + 前缀树（进程内缓存） |

---

## 18. RAG 架构

### 18.1 三类问答场景（不同的权限与检索范围）

| 场景 | 检索范围 | 依据要求 | 典型入口 |
|---|---|---|---|
| **Workspace RAG** | 仅当前空间（且我有权限）的文档 + 笔记 | 强制引用文档与页码 | 空间内 AI 面板 |
| **公共知识问答** | `visibility=PUBLIC` 的全平台内容 + 公开资源 | 强制引用来源链接 | 全局 AI |
| **Research（研究型）** | 公共知识 + 开源项目 + 社区 + 时间过滤，多轮检索 | 强制引用 + 时间范围说明 | `/ai` 或话题页 |

> **刻意不做"全域混合问答"**：混合范围会让权限判定与引用展示都变得模糊，用户体验上也难以理解"为什么答案里混进了我的私人笔记"。

### 18.2 完整流程

```
用户问题 + 上下文（会话 / 当前 workspace）
   │
   ▼
[0] Scope 解析
   │   · 解析出 authorizedScope：{ public: true, workspaceIds: [...] }
   │   · 调用 AuthorizationService.authorizedWorkspaceIds()  ★ 唯一权限入口
   │   · 若为 WORKSPACE 模式但无权限 → 直接 404（不进入检索）
   ▼
[1] Query 改写（受控，非自由发挥）
   │   · 指代消解：结合近 3 轮对话把"它的性能如何" → "Spring AI 的性能如何"
   │   · 去口语化、补全领域词
   │   · 明确不做多轮 HyDE（V4 前属于过度设计）
   ▼
[2] 多路检索（均带 authorizedScope filter）
   │   ① BM25（chunk_index + content_index）
   │   ② 向量 kNN（chunk_index.embedding，k=50）
   │   ③ 标签/元数据过滤（用户显式指定范围时）
   ▼
[3] 权限过滤（已在 [2] 内完成，此处仅断言校验）
   ▼
[4] Rerank（V2 规则 / V4 模型）→ Top-8~12
   ▼
[5] Context Engineering
   │   · 去重：同一文档多 chunk → 合并为带页码的连续段落
   │   · 多样性：MMR，避免全部来自同一文档（防止单源偏差）
   │   · 预算分配：system 5% / query 5% / context 70% / output 20%
   │   · 超预算时按 rerank 分截断，保留来源标注
   ▼
[6] Prompt 组装（信任分层，见 04-security.md 25.2）
   ▼
[7] LLM 调用（超时 30s / 失败重试 1 次 / 熔断）
   ▼
[8] 引用校验（见 04-security.md 25.3）
   ▼
[9] 输出 + 落库（ai_message / ai_citation / ai_run）
```

### 18.3 Scope-first Retrieval（本方案的安全核心）

```
错误做法（明确拒绝）                    本方案
─────────────────────────────        ─────────────────────────────
检索全库 Top-K                       解析 authorizedScope
   ↓                                     ↓
塞进 Prompt                          在 ES filter 中注入 scope
   ↓                                     ↓
Prompt 里写"不要泄露私有内容"           检索（天然只召回授权内容）
   ↓                                     ↓
输出后再过滤/隐藏                      引用校验 → 输出
                                     ↓
                                  检索链路中不存在未授权数据
```

**配套要求：**
1. 检索服务的方法签名强制要求 `AuthorizedScope` 参数（**编译期就无法忘记传**，这是用类型系统做安全）。
2. 无授权 scope 时（如未登录全局问答），`workspaceIds` 为空集合，filter 自动退化为 `visibility=PUBLIC`。
3. `authorizedScope` 在**一次请求内**只计算一次并传递，避免多次计算导致的不一致。
4. 每次检索把 `scope_hash` 记入 `ai_run`，便于事后审计"这次回答用了哪些授权范围"。

### 18.4 对话记忆

| 层 | 内容 | 实现 |
|---|---|---|
| 短期 | 最近 6 轮原文 | 直接拼接（控制 token） |
| 中期 | 更早对话的滚动摘要 | 超过 10 轮后由 LLM 压缩为 200 字摘要 |
| 长期 | 用户兴趣与偏好 | `user_interest` + 显式设置（如"回答简洁些"） |
| 隔离 | 会话数据严格按 `owner_user_id` + `workspace_id` 隔离 | 会话查询带作用域条件 |

### 18.5 成本与性能控制

| 项 | 策略 |
|---|---|
| 缓存 | 相同问题 + 相同 scope 的检索结果缓存 5 分钟 |
| 流式输出 | SSE 流式返回，首 token 目标 < 2s |
| 模型路由 | 简单问题用小模型、复杂问题用大模型（`ModelConfig.purpose` 路由） |
| 预算 | 单用户日 Token 上限；单次请求 context 上限 |
| 可观测 | `ai_run` 记录各阶段耗时（检索/Rerank/LLM）、召回数、Token 数 → 后台可查 |
| 降级 | LLM 不可用 → 返回检索结果列表 + "AI 暂不可用"；不返回空 |

### 18.6 评测（降级版但真实）

- **30 条黄金集**：覆盖"能答 / 不能答 / 应拒绝 / 注入攻击"四类
- 指标：`检索命中率@8`、`引用准确率`、`无依据断言率`、`注入抵抗率`、`P95 延迟`
- 运行方式：`./scripts/eval.sh` → 输出 JSON 对比基线，**CI 中做回归**（指标下降超阈值即失败）
- 这就是把"AI 质量"从主观感受变成可测量

---

## 26. Agent 架构

### 26.1 先明确：什么时候不是 Agent

**V1-V2 阶段，AI 功能全部是 Workflow（固定管线），不是 Agent。** 这不是能力不足，而是正确的工程判断：

| 形态 | 定义 | 本项目对应 |
|---|---|---|
| **Workflow** | 步骤预先确定，LLM 只在固定节点做生成 | Workspace RAG 问答、AI 摘要、Digest 生成 |
| **Tool Calling** | LLM 决定调哪个工具，但流程是单轮 | "帮我总结这篇帖子的评论" |
| **Agent** | LLM 自主决定**下一步做什么、做几次、何时停止** | Research Agent（V4） |

**判定规则：如果我能用固定管线写出答案，就不该做 Agent。** Agent 引入的是不确定性、成本与调试难度，只有"探索路径本身无法预先确定"时才值得。

### 26.2 V4 单 Agent 设计

```
ResearchAgent
├─ 目标：回答需要多源、多轮、时效性的研究型问题
├─ 步数上限：6 步（硬限制）
├─ 工具集（全部只读，全部带权限）：
│   ├─ search_public_content(query, type, time_range)     → 平台公开内容
│   ├─ search_workspace(query, workspace_id)              → ★ 需 workspace 权限
│   ├─ get_oss_trending(language, since, limit)           → 开源项目趋势
│   ├─ get_content_detail(id)                             → 详情（含评论）
│   ├─ get_digest(topic, date)                            → 已生成的 Digest
│   └─ get_user_interests()                               → 个性化（用于排序而非内容）
├─ 循环：Think → 选工具 → 执行（鉴权）→ 观察 → 是否足够 → 否则继续
├─ 终止条件：步数上限 / 时效满足 / 连续两步无新增信息
└─ 输出：Summary + Citation[] + 「检索过程」可见（用了哪些工具、查了什么）
```

**"检索过程可见"是产品亮点**：用户能看到 Agent 做了什么，这是信任的前提，也让失败可诊断。

### 26.3 为什么某个能力应该是 Tool 而不是 Agent（三问判定表）

以 "Community Agent" 为例，逐条回答你的要求：

| 问题 | 回答 | 结论 |
|---|---|---|
| 它需要自己决定下一步做什么吗？ | 不需要。评论、发帖、点赞的触发条件是确定的 | → 不该是 Agent |
| 它需要独立的上下文吗？ | 不需要。它只需当前帖子 + 用户输入 | → 不该是 Agent |
| 独立后解决了什么问题？ | 没有。反而增加一次 LLM 调用、增加延迟、增加不确定性 | → 保持为 Tool |
| 那么它应该是什么？ | **Tool**（`create_comment` / `search_community`）+ **Workflow**（审核管线） | ✅ |

同理判定：

| 候选 Agent | 是否需要自主决策 | 独立上下文？ | 独立后解决的问题 | 结论 |
|---|---|---|---|---|
| Knowledge/Tutor Agent | 部分（多轮追问） | 是（教学上下文） | 教学状态与检索状态分离，避免互相污染 | **V4 可作为独立上下文，但仍由单 Agent + 不同 System Prompt 实现** |
| Research Agent | **是**（探索路径不可预知） | 是 | 需要在多个数据源间做多轮决策 | **V4 引入（唯一真正需要的）** |
| Community Agent | 否 | 否 | 无 | **保持为 Tool** |
| Recommendation Agent | 否（排序是确定性计算） | 否 | 无（规则推荐更可控可解释） | **保持为 Service，不用 LLM** |
| Moderation Agent | 部分（判定违规） | 是 | 审核上下文与用户对话上下文必须隔离 | **V3 作为独立 Workflow + LLM 判定节点（不是 Agent）** |

> **结论：V4 阶段"Agent 数量 = 1"。** Multi-Agent 的引入条件见第 27 节。

---

## 27. Multi-Agent 的引入条件

**当前答案：不引入。** 下面是"什么情况下才引入"的量化判据——**不满足就不做，且有据可依**。

### 27.1 硬性触发条件（需同时满足 ≥3 条才能启动 Multi-Agent 改造）

| # | 条件 | 量化标准 | 当前状态 |
|---|---|---|---|
| C1 | **上下文冲突真实存在** | 出现 ≥3 个案例：同一 Agent 因上下文太长导致回答质量下降（如教学上下文 + 检索上下文混在一起超过窗口 60%） | 未出现 |
| C2 | **工具集规模超限** | 单 Agent 工具数 > 15，且模型选错工具的比例 > 10% | 工具 6 个，未超 |
| C3 | **并行收益显著** | 存在可并行的独立子任务，串行执行导致 P95 延迟 > 15s | 未出现 |
| C4 | **权限边界必须物理隔离** | 某类任务必须运行在**不同权限域**（如审核必须看不到用户私有内容，而教学 Agent 需要看） | 部分相关，但可用 scope 参数解决 |
| C5 | **评测证明收益** | A/B 对比中 Multi-Agent 在黄金集上的综合指标提升 > 15%，且延迟增幅 < 30% | 无数据 |
| C6 | **单 Agent 已到能力上限** | 通过 Prompt / 工具设计 / 上下文工程优化后仍无法解决 | 未尝试尽 |

### 27.2 引入时的演进路径（不是一次性重构）

```
Phase 1  单 Agent + 路由（一个 Agent，按意图切换 System Prompt + 工具集）
           ↓ 仍不满足
Phase 2  显式 Workflow 串联两个"角色"（代码控制流转，不是模型决定）
           ↓ 仍不满足
Phase 3  Orchestrator + 2 个子 Agent（仅 Research + Knowledge 分离）
           ↓ 仍不满足
Phase 4  多 Agent（每个新增 Agent 必须书面回答"为什么不能是 Tool / Workflow Node"）
```

**Phase 4 的准入文档模板（每个 Agent 必填）：**

```
Agent 名称：
1. 为什么它应该独立？（证据：具体案例 + 指标）
2. 为什么不能只是 Tool？（工具无法做到的：状态/多轮/上下文隔离）
3. 为什么不能只是 Workflow Node？（固定管线为什么表达不了）
4. 独立之后解决了什么问题？（可测量的改善）
5. 独立之后新增了什么成本？（延迟/Tokens/调试复杂度/失败模式）
6. 代码位置与权限域：
```

> **这份模板本身就是"不为 Multi-Agent 而 Multi-Agent"的制度化实现。** 它同时也是这份设计文档的自证部分：结论必须是"有证据才拆"，而不是把"拆了"当成成果。
