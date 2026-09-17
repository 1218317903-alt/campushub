# 03 · 领域模型、数据库与权限

> 对应要求：8 核心领域模型 · 9 数据库初步模型 · 10 权限模型

---

## 8. 核心领域模型

### 8.1 统一内容抽象（本方案最关键的建模决策）

平台有四种"内容"来源，但用户在搜索、推荐、Digest、审核时**不应该感知它们的物理差异**：

| 来源 | 生产者 | 可见性 | 是否可评论 |
|---|---|---|---|
| Community 帖子 | 用户 | 公开 | 是 |
| 外部资源（文章/动态/招聘/比赛） | 系统采集 | 公开 | 否（可跳转原站） |
| 开源项目 | 系统采集 / 用户提交 | 公开 | 否 |
| Workspace 公开文档 | 用户（显式发布） | 公开（脱敏后） | 是（作为帖） |

**统一为 `ContentItem`（内容条目投影）：**

```
ContentItem
├─ id, item_type          # POST | RESOURCE | OSS_PROJECT | WORKSPACE_PUBLIC
├─ title, summary         # summary 用于列表卡片与检索摘要
├─ body_ref               # 指向真实正文（post.body / resource 无正文 / document 快照）
├─ author_user_id         # 外部内容为 null
├─ provenance_id          # ★ 来源追溯，USER_GENERATED / PUBLIC_API / ...
├─ visibility             # PUBLIC | WORKSPACE | PRIVATE
├─ workspace_id           # 仅 WORKSPACE / PRIVATE 时非空，用于权限过滤
├─ category_id, tags[]    # 分类与标签
├─ published_at, updated_at
├─ quality_score          # 采集内容的质量分（用于排序与过滤）
└─ status                 # ACTIVE | FOLDED | REMOVED
```

**为什么值得这么做：**

1. **统一搜索**：一次检索命中所有来源，天然支持"分组展示"与"跨类型排序"。
2. **统一推荐**：推荐算法只看 `ContentItem` 的标签/热度/时间，不需为每种来源写一套。
3. **统一权限**：权限过滤只需一个字段（`visibility + workspace_id`），而不是四套逻辑。
4. **统一审核**：Moderation 只有一个处理对象。
5. **Digest**：摘要生成的数据源就是"昨日新增的 ContentItem"。

### 8.2 领域实体全景

#### identity 域
```
User ──1:1── UserCredential
  │
  ├─N── UserRole ──N:1── Role ──N:M── Permission
  ├─N── RefreshToken（可撤销）
  └─N── UserInterest(tag_id, weight, source)   # 兴趣画像，推荐系统输入
```

#### workspace 域
```
Workspace(owner, visibility=PRIVATE|TEAM|PUBLIC_READONLY)
  ├─N── WorkspaceMember(user, role=MEMBER|ADMIN, joined_at)
  ├─N── WorkspaceInvite(code, invitee, expires_at, status)
  ├─N── Document(name, mime, size, storage_key, parse_status, parse_progress)
  │      └─N── DocumentVersion(version, storage_key, parse_summary)
  ├─N── Note(title, markdown, updated_by)        # 轻量 Markdown 笔记
  └─N── WorkspaceTask(title, assignee, status, due_at)   # TODO
```

#### community 域
```
Post(content_item_id, body_md, body_html, category, status)
  ├─N── PostRevision（编辑历史）
  ├─N── Comment(parent_id 两层树, body, status)
  ├─N── Reaction(user, type=LIKE|BOOKMARK)      # 幂等：UNIQUE(user_id, target)
  └─N── ViewLog（明细可选，聚合计数必存）
Report(target_type, target_id, reporter, reason, status)  # 跨域共用
```

#### ingestion / content 域
```
DataSource(type=RSS|API|GITHUB|OPEN_DATA, name, base_url, license, enabled, schedule_cron)
  ├─N── FetchJob（逻辑任务，含游标/ETag/Last-Modified）
  │      └─N── FetchRun（一次执行：开始/结束/状态/新增数/去重数/失败数）
  │             └─N── FetchError（Dead Letter 明细）
  ├─N── CrawlPolicy（per-host 并发、QPS、超时、大小上限）
  └─N── ContentItem（产出的条目）
DataProvenance（source_id, content_hash, license, attribution_required, ...）
```

#### ai 域
```
AiConversation(owner, scope=GLOBAL|WORKSPACE, workspace_id)
  └─N── AiMessage(role, content, token_in/out, latency_ms)
         └─N── AiCitation(message_id, content_item_id | chunk_id, snippet, score)
AiRun（一次推理的 trace：检索耗时/召回数/重排耗时/模型耗时/工具调用链）
AiToolCall(run_id, tool_name, args_hash, authorized_by, result_status)
ModelConfig(provider, model, purpose=CHAT|EMBED|RERANK, params, enabled, weight)
TokenUsage(user, day, model, prompt_tokens, completion_tokens, cost)
EvalCase / EvalRun（黄金集与回归结果）
```

### 8.3 聚合与一致性边界

| 聚合根 | 事务边界 | 跨聚合一致性手段 |
|---|---|---|
| `User` | identity 内 | — |
| `Workspace` | workspace 内（成员、文档元数据） | 文档解析经事件异步 |
| `Post` | community 内 | 计数走 Redis 异步刷库 |
| `ContentItem` | content 内 | 由 `PostPublished` 等事件驱动投影（最终一致） |
| `Document` | workspace 内 | 解析管道经 MQ，状态机驱动 |
| `DataSource` | ingestion 内 | 采集→入库经事件 |

> 原则：**跨聚合一律最终一致**，用 `outbox_event` 保证"业务写入与事件发布"的原子性（同一本地事务写业务表 + outbox，再由投递器发送）。

---

## 9. 数据库初步模型

### 9.1 全局约定

- 字符集 `utf8mb4` / 排序 `utf8mb4_0900_ai_ci`
- 主键：`BIGINT AUTO_INCREMENT`（内部使用）+ `public_id CHAR(22)`（对外暴露，防 ID 遍历）——**这一条直接消灭一类 IDOR 风险**
- 时间：`created_at` / `updated_at`（`DATETIME(3)`），统一 UTC 存储
- 软删除：`deleted_at`（内容类表），索引包含 `deleted_at IS NULL` 条件
- 所有表带 `workspace_id`（若属于空间域）——**数据层强制作用域的地基**
- Flyway 迁移文件：`V1__identity.sql`、`V2__workspace.sql` …
- 所有采集类表带 `provenance_id` 或 `source_id`

### 9.2 表清单（36 张，按模块）

| 模块 | 表 | 关键字段 / 索引要点 |
|---|---|---|
| platform | `audit_log` | actor, action, target_type, target_id, ip, ua, result, detail_json；索引(actor, created_at), (target_type, target_id) |
| platform | `outbox_event` | event_type, payload_json, status(PENDING/SENT/FAILED), retry_count, next_retry_at；索引(status, next_retry_at) |
| platform | `idempotency_record` | key UK, request_hash, response_json, expire_at；**点赞/发布/上传幂等靠它** |
| platform | `data_provenance` | source_id, source_type, source_name, source_url, author, license, license_url, published_at, retrieved_at, content_hash UK, attribution_required, redistribution_allowed, status |
| identity | `user` | public_id UK, username UK, email UK, nickname, avatar, bio, status, interest_vector_json |
| identity | `user_credential` | user_id UK, password_hash(BCrypt), mfa_secret, last_login_at |
| identity | `role` / `permission` / `user_role` / `role_permission` | 标准 RBAC 四表 |
| identity | `refresh_token` | token_hash UK, user_id, device, expires_at, revoked_at, rotated_from |
| identity | `user_interest` | user_id, tag_id, weight, source(EXPLICIT/BEHAVIOR), updated_at；UK(user_id, tag_id) |
| workspace | `workspace` | public_id UK, name, owner_id, visibility, description, member_count, doc_count；索引(owner_id), (visibility, updated_at) |
| workspace | `workspace_member` | workspace_id, user_id, role, status；UK(workspace_id, user_id)；索引(user_id) |
| workspace | `workspace_invite` | code UK, workspace_id, invitee_user_id, role, expires_at, status |
| workspace | `document` | public_id UK, workspace_id, name, mime, size, storage_key, sha256, parse_status, parse_progress, chunk_count, error_msg；索引(workspace_id, status), (sha256) |
| workspace | `document_version` | document_id, version, storage_key, parser_version, chunk_strategy |
| workspace | `note` | public_id, workspace_id, title, markdown, updated_by；**FULLTEXT(title, markdown) with ngram 兜底搜索** |
| workspace | `workspace_task` | workspace_id, title, assignee_id, status, priority, due_at |
| content | `content_item` | public_id UK, item_type, title, summary, body_ref, author_user_id, provenance_id, visibility, workspace_id, category_id, quality_score, published_at, status；**索引(status, visibility, published_at DESC), (item_type, published_at), (workspace_id), (provenance_id)** |
| content | `tag` / `category` | name UK, slug UK, parent_id, sort, hot_score |
| content | `content_item_tag` | item_id, tag_id；UK(item_id, tag_id)；索引(tag_id, item_id) |
| content | `content_stats` | item_id UK, view_count, like_count, comment_count, bookmark_count, hot_score, updated_at（**汇总表，避免每次聚合**） |
| community | `post` | content_item_id UK, body_md, body_html, word_count, status, published_at；FULLTEXT 兜底 |
| community | `post_revision` | post_id, version, body_md, editor_id, created_at |
| community | `comment` | public_id, post_id, parent_id, root_id, author_id, body, like_count, status；索引(post_id, root_id, created_at) |
| community | `reaction` | user_id, target_type, target_id, type；**UK(user_id, target_type, target_id, type) 保证幂等** |
| community | `view_log` | 分区表或近 7 天滚动；item_id, user_id, ip_hash, created_at |
| community | `report` | target_type, target_id, reporter_id, reason, detail, status；UK(reporter_id, target_type, target_id) |
| ingestion | `data_source` | name UK, adapter_type, base_url, license, license_url, enabled, schedule_cron, trust_level, config_json |
| ingestion | `fetch_job` | source_id, name, cursor_json, etag, last_modified, enabled, next_run_at；索引(enabled, next_run_at) |
| ingestion | `fetch_run` | job_id, status, started_at, finished_at, fetched, inserted, deduped, failed, error_summary |
| ingestion | `fetch_error` | run_id, url, stage(FETCH/PARSE/CLEAN/INDEX), error_type, message, retry_count, dead_letter |
| ingestion | `dedup_record` | content_hash UK, canonical_url_hash UK, first_seen_at, source_id（**增量更新的核心**） |
| ingestion | `crawl_policy` | host UK, max_qps, max_concurrency, timeout_ms, max_bytes, respect_robots |
| search | `query_log` | user_id, raw_query, normalized, intent, result_count, clicked_item_id, latency_ms；索引(created_at), (user_id) |
| search | `search_suggestion` | term UK, freq, last_seen_at（热搜与联想词） |
| ai | `ai_conversation` / `ai_message` / `ai_citation` | 见 8.2；citation 保留 snippet 与 score 以便引用校验 |
| ai | `ai_run` / `ai_tool_call` | run 存各阶段耗时与召回统计；tool_call 存授权结果 |
| ai | `model_config` / `token_usage` / `eval_case` / `eval_run` | 模型路由、成本计量、评测回归 |
| moderation | `moderation_task` / `moderation_verdict` / `sensitive_word` | 队列、裁定（APPROVE/FOLD/REMOVE/WARN）、词库（Aho-Corasick 加载） |

### 9.3 关键索引与容量预估（用于验证设计合理性）

| 表 | 预期规模（1 年） | 主要索引 | 说明 |
|---|---|---|---|
| `content_item` | 500 万（外部内容为主） | (status, visibility, published_at) | 列表页主索引；避免全表排序 |
| `chunk`（ES） | 1500 万 | HNSW(dims=1024, m=16) | ES 侧，不做 MySQL 存储 |
| `reaction` | 200 万 | UK(user,target,type) | 幂等约束即索引 |
| `view_log` | 3000 万 | 按月分区 / 7 天滚动删除 | **必须有清理策略，否则是隐患** |
| `outbox_event` | 100 万/月 | (status, next_retry_at) | 发送成功即归档/删除 |

> **主动指出风险**：`view_log` 和 `outbox_event` 是这类项目最容易变成"表膨胀"的地方。设计阶段就定好分区与归档策略，而不是等出事。

### 9.4 与检索层的职责划分

| 数据 | 归属 | 理由 |
|---|---|---|
| 业务真值（帖子正文、评论、权限） | MySQL | 事务、约束、强一致 |
| 全文检索与向量 | ES | MySQL 全文能力弱，且不适合做向量 |
| 热点计数（点赞数、浏览数、热榜分） | Redis（+ 定期刷 MySQL） | 高频写不能直压 MySQL |
| 文件二进制 | 对象存储 | DB 不存 BLOB |
| 会话与对话历史 | MySQL（近期）+ 归档 | 需要可审计 |

---

## 10. 权限模型

### 10.1 角色体系（6 个角色，但要分清"角色"和"身份"）

| 角色 | 本质 | 能做什么 | 不能做什么 |
|---|---|---|---|
| `VISITOR` | 未登录身份（不是 DB 角色） | 浏览公开内容、搜索公开内容 | 任何写操作、任何私人内容 |
| `USER` | 所有注册用户的基础角色 | 建空间、发帖、评论、点赞、用 AI | 看别人的私有空间 |
| `WORKSPACE_MEMBER` | **上下文角色**（在某空间内） | 读该空间资源、参与协作 | 管理成员、删除空间 |
| `WORKSPACE_ADMIN` | **上下文角色** | 空间内全部操作、邀请/移除成员 | 影响其他空间 |
| `MODERATOR` | 平台角色 | 处理举报、折叠/删除内容、封禁用户 | 改系统配置、看数据源密钥 |
| `ADMIN` | 平台角色 | 全部后台能力 | —（但**关键操作全部记审计日志**） |

> **重要的建模认知**：`WORKSPACE_MEMBER` / `WORKSPACE_ADMIN` **不是全局 Role**，而是 `(user, workspace)` 上的关系属性。把它们做成全局 Role 是常见设计错误，会导致"用户在 A 空间是管理员，于是能管理 B 空间"的越权。

### 10.2 权限点设计（Permission Registry）

不用字符串散落各处，而是**集中注册 + 编译期常量**：

```
post:create          post:update:self     post:delete:self     post:delete:any
comment:create       comment:delete:self  comment:delete:any
workspace:create     workspace:read       workspace:update     workspace:delete
workspace:member:invite   workspace:member:remove   workspace:member:role:update
document:upload      document:read        document:delete      document:download
ai:chat              ai:workspace:chat    ai:admin:model:update
search:public        search:workspace
moderation:report    moderation:review    moderation:verdict
admin:dashboard      admin:source:manage  admin:audit:read
```

Role → Permission 映射存 DB（可运营调整），默认种子化。

### 10.3 三层防线（本方案的核心安全设计之一）

```
第 1 层 · 角色（Controller 层）
  @PreAuthorize("hasPermission(#req.workspaceId, 'workspace:member:invite')")
  作用：拦住"身份不够"的请求
  失败：403

第 2 层 · 资源（Service 层）
  WorkspaceAccessService.assertCanRead(userId, workspaceId, ResourceType.DOCUMENT)
  作用：校验"具体资源是否属于我可访问的空间"，含 visibility / 成员状态 / 是否被移除
  失败：404（★ 故意返回 404 而非 403，避免资源存在性泄露）

第 3 层 · 数据域（Repository 层，强制注入）
  WorkspaceScopeInterceptor：对带 workspace_id 的表，自动追加
  AND workspace_id IN (:authorizedWorkspaceIds)
  作用：兜底。即使前两层漏写，SQL 也查不出越权数据
  失败：返回空集（静默安全）
```

**第三层是"防护网"而非"主防线"**——它的价值在于**消除"人总会漏写一个 if"的风险**。实现方式：MyBatis 拦截器 + 注解标记（`@ScopedTable`），只对标记的表生效，避免误伤。

### 10.4 资源级授权统一入口

```java
public interface AuthorizationService {
    // 断言：不可访问则抛 AccessDeniedException(404 in API layer)
    void assertCan(UserPrincipal user, ResourceRef ref, Action action);
    // 判定：用于列表过滤与 UI 能力渲染
    Decision decide(UserPrincipal user, ResourceRef ref, Action action);
    // 批量：列表页用，避免 N+1 鉴权
    Map<Long, Decision> decideBatch(UserPrincipal user, List<ResourceRef> refs, Action action);
    // 作用域集合：检索与列表查询用（★ AI 检索复用同一入口）
    Set<Long> authorizedWorkspaceIds(UserPrincipal user, Action action);
}
```

`authorizedWorkspaceIds()` 是**权限与检索的接缝**：AI 的 RAG 检索、统一搜索的 workspace 分组、推荐候选集，全部复用这一个方法，保证"权限判定只有一处实现"。

### 10.5 六个必须守住的具体规则

| 规则 | 落地方式 |
|---|---|
| 猜到 URL 也读不到别人的文档 | 对外用 `public_id`；Service 层 404；Repository 层作用域注入 |
| 被移出空间后立即失去访问权 | 权限判定不缓存 user 身份之外的成员关系超过 60s；主动失效缓存 key |
| 空间公开文档 ≠ 空间内全部文档 | 发布是**单文档级**动作，生成脱敏快照，不是"把空间设为公开" |
| 管理员也不能看用户私有内容（除合规流程） | 后台只能看元数据 + 举报上下文；正文访问走独立审批动作并记审计 |
| AI 不得跨空间检索 | 检索请求构造时注入 `workspace_id IN (authorized)`；无授权集合时退化为仅公共知识 |
| 分享/导出链接有有效期 | 签名 URL + 短 TTL + 绑定用户（可选） |
