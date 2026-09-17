# 05 · 数据平台（Bootstrap / 采集 / Discover / 文档管道）

> 对应要求：11 Data Bootstrap · 12 第一批 Demo 数据方案 · 13 合规公开数据来源类别 · 14 Data Ingestion Pipeline · 15 Crawler 架构 · 16 Discover 架构 · 19 Document Pipeline

---

## 11. Data Bootstrap System

### 11.1 设计目标

| 目标 | 量化验收 |
|---|---|
| 一条命令完成初始化 | `make bootstrap` 或 `java -jar app.jar --spring.profiles.active=bootstrap` |
| 10 分钟内可完整 Demo | 首次执行（含镜像拉取）≤ 10 分钟；已有镜像 ≤ 4 分钟 |
| 幂等 | 重复执行不产生重复数据（基于业务键 upsert） |
| 可重放 | `--reset-seed` 只清理 `origin=SEED` 的数据，**绝不动真实用户数据** |
| 可观测 | 每个阶段打印耗时与产出条数，失败可定位到具体阶段 |
| 可跳过 | `--skip=crawl,embed` 支持分阶段执行（离线 Demo 场景） |

### 11.2 执行阶段

```
Stage 0  infra        docker compose up -d  （MySQL / Redis / ES / MinIO / RabbitMQ(可选)）
Stage 1  migrate      Flyway 执行全部迁移
Stage 2  dict         RBAC 角色权限、分类、标签字典、敏感词库、CrawlPolicy 默认值
Stage 3  users        8 个 Demo 用户（含 1 Admin / 1 Moderator / 6 普通用户）+ 兴趣标签
Stage 4  workspaces   4 个 Demo Workspace + 成员 + 邀请 + 任务 + 笔记
Stage 5  documents    每空间 2-4 份真实可解析的演示文档（含一份中文 PDF、一份 DOCX）
Stage 6  community    80 条帖子 + 300 条评论 + 互动数据（标记 SYNTHETIC_DEMO）
Stage 7  crawl        5 个真实公开源首次采集（GitHub / HN / arXiv / OpenAlex / RSS×6）
Stage 8  index        构建 ES 索引 + 向量化 + 校验索引条数与 DB 一致
Stage 9  compute      热榜计算 + 推荐候选池 + Topic Digest 生成
Stage 10 verify       健康检查 + 关键页面数据量断言 + 打印 Demo 账号与入口
```

**Stage 10 的断言是这个系统的护栏**：如果 `content_item` 少于 200 条、或首页 Feed 为空，Bootstrap 必须**报错失败**，而不是"看起来成功了但首页是空的"。

### 11.3 实现要点

```java
public interface BootstrapTask {
    String name();                 // 阶段名
    int order();                   // 执行顺序
    BootstrapResult run(BootstrapContext ctx);   // 返回 {inserted, updated, skipped, durationMs}
    boolean supports(BootstrapContext ctx);      // 支持按 --skip/--only 过滤
}
```

- 所有 Task 实现同一接口，Spring 自动收集并按 `order()` 排序 → **新增种子任务零成本**
- 每个 Task 自动记录 `bootstrap_run_log`（可查询哪次初始化做了什么）
- 数据来源字段：
  - 字典/演示数据 → `origin = SYNTHETIC_DEMO` 或 `SEED`
  - 真实采集数据 → 走完整 Ingestion Pipeline（**Bootstrap 复用生产链路，不写第二套逻辑**）

> **关键设计判断：Bootstrap 的第 7 阶段必须调用真实的 Ingestion Pipeline。** 如果为了省事写一个"专用导入脚本"，那么演示成功不代表生产链路可用——这是最容易骗到自己的地方。

---

## 12. 第一批 Demo 数据方案

### 12.1 内容配比总览

| 类别 | 目标量 | 来源标记 | 说明 |
|---|---|---|---|
| Demo 用户 | 8 | `SYNTHETIC_DEMO` | 有人设、有头像、有简介、有互相关注话题 |
| Demo Workspace | 4 | `SYNTHETIC_DEMO` | 覆盖课程 / 竞赛 / 毕设 / 自学四种场景 |
| Demo 文档 | 12 | `SYNTHETIC_DEMO` | 真实可解析文件，确保文档管道有东西可演示 |
| Community 帖子 | 80 | `SYNTHETIC_DEMO` | 分布见下 |
| 评论 | 300 | `SYNTHETIC_DEMO` | 覆盖 2 层树形结构 |
| 互动数据（点赞/收藏/浏览） | 5000+ | `SYNTHETIC_DEMO` | 用于热榜与推荐可演示 |
| 真实公开资源 | 300+ | `PUBLIC_API` / `EXTERNAL_METADATA` | 首次采集产出 |
| Topic Digest | 5 篇 | `SELF_CREATED`（基于真实采集内容生成） | 让首页第一屏有"AI 感" |

### 12.2 Demo 用户人设（不是随机字符串）

| 用户名 | 人设 | 作用 |
|---|---|---|
| `admin` | 平台管理员 | 演示 Admin Console |
| `moderator` | 社区志愿者 | 演示审核队列 |
| `lin_spring` | 后端方向大三学生，Spring 深度用户 | 高质量技术帖作者 |
| `zhao_math` | 数学建模队长 | 竞赛 Workspace 拥有者 |
| `chen_ai` | 保研 AI 方向，读论文多 | AI 话题内容源、长文作者 |
| `wu_front` | 前端爱好者，大二 | 前端标签内容、评论活跃 |
| `sun_career` | 大四求职中，已拿 offer | 求职话题、经验帖 |
| `guest_new` | 刚注册的新手 | 演示"新用户视角"，兴趣标签为空 → 验证冷启动推荐 |

### 12.3 Demo Workspace

| 名称 | 类型 | 成员 | 内容 | 演示价值 |
|---|---|---|---|---|
| 数据结构期末冲刺 | 课程 | 3 人 | 3 份课件 PDF + 复习笔记 + 5 个 TODO | 个人+小团队学习场景 |
| 数学建模国赛备战 | 竞赛 | 4 人（2 管理员） | 历年优秀论文 + 选题思路 + 分工任务 | **权限演示**：非成员看不到 |
| 毕业设计 · 校园推荐系统 | 毕设 | 2 人 | 开题报告 + 文献综述 + 数据说明 | 演示"文档 → 发布到社区" |
| Spring AI 自学笔记 | 自学 | 1 人 | 8 篇 Markdown 笔记 | 演示 RAG 问答与引用 |

### 12.4 Community 帖子分布（80 条）

| 分类 | 条数 | 内容特征 |
|---|---|---|
| 编程技术 | 18 | 含代码块、性能对比、踩坑记录 |
| 人工智能 | 14 | 含论文解读、工具评测 |
| 开源项目 | 10 | 项目介绍、贡献指南 |
| 课程学习 | 12 | 复习方法、资料分享、课程评价 |
| 竞赛 | 8 | 赛题复盘、组队 |
| 项目经验 | 8 | 技术选型、架构复盘 |
| 求职与职业 | 6 | 时间线、面经（**不含具体公司面试题原文**，避免版权/保密问题） |
| 校园生活 | 4 | 轻量内容，保证生态真实感 |

其中：**5 条"热帖"**（高互动、有长评论串，用于演示热榜与通知），**10 条长文**（>1500 字，用于演示摘要与搜索），**8 条带图片**。

### 12.5 数据诚实性规范（必须遵守）

1. 所有合成数据带 `origin = SYNTHETIC_DEMO`，在 DB 层可一次性筛出。
2. 前端对 Demo 用户与 Demo 帖子展示 **"演示内容"徽章**（不是隐藏，是公开标注）。
3. Admin Dashboard 中，由种子数据产生的指标**单独分区展示**，不与会话产生的真实指标混淆。
4. 任何对外展示（README、演示材料、对外发布的文档）中出现的性能与行为数据**必须来自真实压测**，Demo 行为数据不作为产品成效宣称。
5. `docs/DEMO_DATA.md` 记录每类种子数据的生成方式与规模，可被他人复现。

---

## 13. 合规公开数据来源类别

**分级原则：许可明确的可以直接入库（可能仅存元数据）；许可不明确的默认只存 Metadata；政策不明的直接不接。**

### 13.1 首批接入清单（V2 目标 8-11 个源）

| 来源 | Adapter | 许可 / 条款 | 存储范围 | 再分发 | 风险 |
|---|---|---|---|---|---|
| **GitHub REST API** | `GITHUB` | 公开 API，ToS 允许读取公开数据；限流 60/h（未认证）/5000/h（Token） | 仓库元数据（名称、描述、topics、star、语言、更新时间）+ README 纯文本摘要（受仓库自身 LICENSE 约束 → 无 LICENSE 时**只存元数据**） | 元数据可 | 低 |
| **Hacker News API** | `API` | 公开 Firebase API，条目公开 | 标题、URL、得分、评论数、时间 | 可 | 低 |
| **arXiv API (Atom)** | `API` | 元数据开放；摘要版权归作者 | 标题、作者、分类、链接、发布日期（**摘要截断，仅用于检索与短展示 + 原链**） | 元数据可 | 低-中 |
| **OpenAlex** | `API` | **CC0** | 论文元数据、作者、机构、引用 | 完全可 | 极低 |
| **Crossref** | `API` | 元数据开放 | DOI 元数据 | 可 | 极低 |
| **官方技术博客 RSS/Atom**（Spring Blog / JetBrains / GitHub Blog / Cloudflare / Google Dev / Microsoft DevBlogs / HuggingFace） | `RSS` | Feed 的既定用途即聚合分发 | title、link、summary（Feed 自带）、pubDate、author | 摘要可（保留原链与署名） | 低 |
| **Codeforces API** | `API` | 公开 API，官方提供 | 比赛信息、题目元数据 | 可 | 低 |
| **Kaggle API / 竞赛公开页** | `API` | 公开 API | 竞赛元数据 | 元数据可 | 低-中 |
| **Wikipedia / Wikidata API** | `API` | **CC BY-SA / CC0** | 摘要（**必须署名 + 相同方式共享**） | 需署名 | 低 |
| **高校教务/通知公开页** | `WEB_PAGE`（默认关闭） | 版权与爬取政策通常不明确 | **仅 Metadata：标题 + 日期 + 链接** | 不 | **高 → 需人工逐个确认 robots.txt 与公告政策** |
| 大厂校园招聘公告 | `WEB_PAGE`（默认关闭） | 各站 ToS 限制 | **仅 Metadata** | 不 | **高** |

### 13.2 明确不接入

| 来源 | 原因 |
|---|---|
| 掘金 / CSDN / 知乎 / 微信公众号 | 版权与平台政策不明确，反爬策略明确，抓取有法律与道德风险 |
| 付费墙内容 | 无权获取 |
| 未授权转载的技术博客 | 源头不合法 |
| 任何需要登录/绕过限制才能访问的内容 | 直接违规 |

### 13.3 Provenance 强制字段（每一条外部内容都必须具备）

```
source_id · source_type · source_name · source_url · author
license · license_url · published_at · retrieved_at
content_hash · attribution_required · redistribution_allowed · status
```

**校验规则（入库前断言，不通过则拒绝入库）：**

- `license` 为空且 `source_type ∈ {PUBLIC_API, OPEN_DATA, LICENSED_CONTENT}` → 拒绝
- `redistribution_allowed = false` 且内容长度超过 metadata 阈值 → 拒绝（只能存元数据）
- `attribution_required = true` 且 `author` 为空 → 拒绝
- 所有对外展示的外部条目**必须可点击跳转原始链接**

> 这三条规则在 `ProvenanceGuard` 中实现，是"合规"从文档变成代码的地方。

---

## 14. Data Ingestion Pipeline

### 14.1 统一适配器抽象（避免为每个站点写硬编码流程）

```java
public interface DataSourceAdapter {
    String type();                                     // RSS | API | GITHUB | OPEN_DATA | WEB_PAGE
    FetchPlan plan(DataSource ds, Cursor cursor);      // 产出「要抓什么」：URL 列表 + 条件头 + 分页游标
    List<RawItem> fetch(FetchPlan plan, FetchContext ctx);  // 只负责拿到原始字节/JSON
    NormalizedItem parse(RawItem raw);                 // 归一为统一结构（各适配器唯一必须自己实现的部分）
    default List<NormalizedItem> parseBatch(List<RawItem> raws) { ... }
}

public record NormalizedItem(
    String externalId, String title, String summary, String url,
    String author, Instant publishedAt, List<String> rawTags,
    String contentRef,          // 需要正文时指向已下载的原始文件
    String licenseHint,         // 由适配器根据源配置给出
    String cursor               // 该条目的增量游标
) {}
```

**适配器只做"解析"，其余全部复用共享管道。** 新增一个 RSS 源 = 配置 `DataSource` 一行数据；新增一个 API 源 = 实现 `parse()` 一个方法。

### 14.2 管道阶段

```
DataSource ──► Scheduler ──► FetchJob(游标/ETag) ──► FetchRun
                                                      │
      ┌───────────────────────────────────────────────┘
      ▼
  [1] Fetcher        HTTP 出站（URL 校验 + 限速 + 超时 + 体积限制 + 条件请求）
      ▼
  [2] Parser         MIME 判定 → XML/JSON/HTML 解析 → NormalizedItem
      ▼
  [3] Cleaner        HTML 白名单清洗 → 文本规范化 → 去广告/导航噪声 → 截断
      ▼
  [4] Deduplicator   三级去重（见 14.3）
      ▼
  [5] Classifier     规则分类 + 标签映射（LLM 兜底，限流且可关）
      ▼
  [6] Enricher       质量分、权威度、语言检测、去重指纹
      ▼
  [7] Persister      content_item + data_provenance + fetch_run 统计（同一事务）
      ▼
  [8] Indexer        ES 索引（异步，可重放）
      ▼
  [9] Vectorizer     摘要向量化（批量，异步，可重放）
      ▼
  [10] Done          更新游标 + 计算下次运行时间
```

**每一步都是独立可重试的**：`fetch_run` 记录各阶段处理数，失败在阶段内重试，超过阈值进 DLQ。

### 14.3 三级去重（分别解决不同问题）

| 级别 | 指纹 | 解决什么 | 动作 |
|---|---|---|---|
| L1 URL | `sha256(canonical_url)` | 同一 URL 重复抓取 | 命中则跳过抓取（结合 ETag 判定是否更新） |
| L2 内容 | `sha256(normalized_text)` | 同一内容被多源转载（同文同源） | 命中则只新增 provenance 关联，**不重复建 content_item** |
| L3 近似 | SimHash(64) + 汉明距离 ≤ 3 | 标题改动的近似重复 | 命中则合并到已有条目，标记 `duplicate_of` |

**L2/L3 的价值**：避免"同一篇 Spring 官方博客被 5 个源转发 → 首页出现 5 条一样的内容"——这是内容聚合产品最常见的体验灾难。

### 14.4 增量与更新

| 机制 | 实现 |
|---|---|
| RSS/Atom | 条件请求 `If-None-Match` / `If-Modified-Since`；304 则整轮跳过 |
| 游标型 API | `cursor` 存 `fetch_job.cursor_json`（如 GitHub 用 `since`，HN 用 `maxitem`） |
| 分页 | `page token` 逐页推进，中断可从最后成功页续跑 |
| 内容更新 | L2 命中但 `content_hash` 变化 → 记录 `content_revision`，保留历史版本 |
| 内容失效 | HTTP 404/410 → `status=GONE`（保留元数据，展示为"原链接已失效"）；长期不可达 → `status=STALE` |

### 14.5 可靠性工程

| 能力 | 实现 |
|---|---|
| 重试 | 3 次指数退避（1m / 5m / 25m），仅重试可恢复错误（5xx、超时、连接失败） |
| 幂等 | `fetch_run` 唯一键 = `job_id + scheduled_at`（时间片取整），重复触发不会双跑 |
| 死信 | 超阈值 → `fetch_error.dead_letter = true` + 告警；后台可手动重放 |
| 任务状态 | `PENDING → RUNNING → SUCCESS / PARTIAL / FAILED / DEAD`；`PARTIAL` 表示部分条目失败 |
| 熔断 | 单源连续 3 轮失败率 > 50% → 自动暂停该源并通知管理员 |
| 配额 | per-host QPS + per-source 每日抓取上限 |
| 可观测 | 每轮 run 记录：fetched / inserted / deduped / failed / 耗时 / 字节数 |
| 数据质量 | 质量分 = `源权威度(0-40) + 内容长度(0-20) + 有作者(10) + 有发布时间(10) + 有标签(10) + 原创(10)`；低于阈值不进首页推荐但保留可搜索 |

---

## 15. Crawler 架构

### 15.1 组件视图

```
┌──────────────────────────────────────────────────────────┐
│                      Scheduler 进程                        │
│  定时扫描 fetch_job(enabled AND next_run_at<=now)          │
│  → 创建 FetchRun(PENDING) → 投递任务队列                     │
└───────────────────────┬──────────────────────────────────┘
                        ▼
┌──────────────────────────────────────────────────────────┐
│                     Dispatcher                            │
│  · 全局并发闸门（Semaphore）                                │
│  · Per-host 并发闸门（Redis 计数）                          │
│  · 按源优先级排序（API > RSS > 网页）                        │
└───────────────────────┬──────────────────────────────────┘
                        ▼
┌──────────────────────────────────────────────────────────┐
│                  FetchWorker（虚拟线程池）                   │
│  ① UrlValidator（SSRF 链条，见 04-security.md 24.1）        │
│  ② HostRateLimiter（Redis 令牌桶，per-host）                │
│  ③ OutboundHttpClient（独立连接池 / 固定 UA / 条件请求）      │
│  ④ ResponseGuard（体积 / 超时 / MIME / 压缩炸弹）            │
│  ⑤ Parser → Cleaner → Dedup → Classify → Persist           │
│  ⑥ 失败 → fetch_error → 重试队列 / DLQ                      │
└──────────────────────────────────────────────────────────┘
```

### 15.2 技术实现

| 项 | 选择 | 说明 |
|---|---|---|
| HTTP 客户端 | Java 21 `HttpClient` + **虚拟线程** | 无需 WebClient 的响应式复杂度；IO 密集场景虚拟线程足够 |
| 出站隔离 | 独立连接池 + 独立线程池 | 采集慢不能拖垮业务接口（**这是真实生产事故的常见来源**） |
| Feed 解析 | Rome (`rome-modules`) | 兼容 RSS 0.9x/1.0/2.0/Atom |
| HTML 解析/清洗 | Jsoup + 白名单 | 同时做选择器提取与消毒 |
| 类型识别 | Apache Tika | magic bytes 判定，不信任 Content-Type |
| 限速 | Redis + Lua 令牌桶 | 跨实例一致（worker 可能多副本） |
| 去重存储 | MySQL `dedup_record`（唯一索引） | 可靠性优先；量大后再考虑 Bloom Filter 前置 |
| 任务存储 | V2：DB 任务表轮询；V3：RabbitMQ | 迁移时保持 `fetch_run` 语义不变 |

### 15.3 部署与隔离

- Crawler 运行在 **独立容器**，出站流量可经代理并做域名白名单
- **不挂载云凭证**（避免 Metadata 攻击的收益为零）
- DB 用户限制为"只能写 ingestion / content 相关表"
- 崩溃不影响 API（进程分离 + 熔断降级）

### 15.4 采集可观测指标

`crawler_run_total{source,status}`、`crawler_fetch_duration_seconds`、`crawler_items_inserted_total`、`crawler_dedup_ratio`、`crawler_http_error_total{code}`、`crawler_dlq_size`、`crawler_host_qps`、`outbound_pool_waiting`

---

## 16. Discover 架构

### 16.1 内容组织模型

```
Topic（话题，用户视角的入口）
  ├─ 由 tag 聚合而成（如「Java」「AI 工具」「竞赛」）
  ├─ 含 topic_meta：简介、封面、关注数、是否精选
  └─ 内容 = 该 tag 下的全部 ContentItem（跨来源）

Source（来源，透明度视角）
  └─ 该数据源的全部条目 + license 信息 + 最近更新状态 + 抓取成功率

Timeline / Ranking（排序视角）
  ├─ 最新（published_at DESC，按质量分过滤）
  ├─ 热门（时间窗内互动 + 质量分）
  └─ 精选（运营/算法挑选，人工可干预）

Digest（摘要视角）
  └─ 每日/每周按话题聚合的 Top-N + AI 摘要（带引用）
```

### 16.2 排序公式（可解释、可调参）

```
score = w1 · normalized(quality_score)          # 来源权威度与内容完整性
      + w2 · normalized(log(1 + interactions))  # 互动热度（对数量取对数，防头部碾压）
      + w3 · exp(-age_hours / τ)                # 时间衰减，τ 按话题类型不同（快讯 12h，教程 168h）
      + w4 · topic_match(user_interest)         # 兴趣匹配（未登录时为 0）
      + w5 · source_freshness                    # 该来源近 7 天更新活跃度
      - w6 · repetition_penalty                  # 近期已展示惩罚（多样性）
```

**每个权重与每项得分都可在 Admin 调整并持久化**，这是"可解释"的工程化落地方式。

### 16.3 核心 API

| 接口 | 说明 |
|---|---|
| `GET /api/discover/feed?topic=&type=&cursor=` | 游标分页（不用 offset，防深翻页性能问题） |
| `GET /api/discover/topics` | 话题列表（含关注状态） |
| `GET /api/discover/sources` | 数据源列表 + 许可信息 + 统计 |
| `GET /api/discover/sources/{id}/items` | 某来源的条目 |
| `GET /api/discover/digests?date=` | Digest 列表 |
| `POST /api/topics/{id}/follow` | 关注话题（替代关注用户，冷启动更有效） |
| `POST /api/discover/submissions` | 用户提交资源（进审核队列后才公开） |

### 16.4 Topic Digest 生成（必须由真实数据驱动）

```
每日 07:00（可配）
 1. 取过去 24h 新增 ContentItem（按话题分组）
 2. 过滤：quality_score < 阈值 或 status != ACTIVE → 剔除
 3. 去重：SimHash 近似合并（跨源同内容）
 4. 排序：话题内 score 排序取 Top 8
 5. 生成：LLM 输入「标题 + 摘要 + 来源」列表 → 输出结构化摘要 + 每条来源链接
 6. 校验：引用必须指向本次输入集合；不通过则降级为"纯标题列表 + 统计数字"
 7. 落库：digest + digest_item（保留 item 引用，可回溯）
 8. 兜底：若当日新增 < 3 条 → 跳过并沿用昨日内容（不生成空洞文章）
```

> **这就是"真正由平台数据驱动"的具体含义**：每个 Digest 都能列出来源条目清单，点开可看到原文链接；如果当天没有新内容，就不生成。

### 16.5 首页"生态感"保障（与 Bootstrap 联动）

首页 7 个模块各自有**独立的数据可用性断言**，Bootstrap 第 10 阶段逐条检查；任一条不达标则报错。这是"部署完成后 10 分钟内可完整 Demo"从愿望变成机制的方式。

---

## 19. Document Pipeline

### 19.1 设计原则

**HTTP 请求线程绝不做解析、切片或向量化。** 上传接口只做三件事：鉴权 → 存对象存储 → 创建任务并返回 `document_id + parse_status`。

### 19.2 状态机

```
PENDING ──► UPLOADED ──► PARSING ──► CHUNKING ──► EMBEDDING ──► INDEXING ──► READY
              │              │            │             │            │
              └──────────────┴────────────┴─────────────┴────────────┴──► FAILED
                                                                          │
                                                        （可重试）RETRYING ┘
项目删除 → DELETING → （清理 chunk / 向量 / 对象）→ DELETED
```

每个状态可查询，前端通过 `GET /api/workspaces/{id}/documents/{docId}/status` 轮询或 SSE 订阅进度（`parse_progress` 0-100）。

### 19.3 幂等与任务可靠性

| 机制 | 实现 |
|---|---|
| 幂等键 | `document_id + version`（重新上传同文件不重复处理；同 sha256 直接复用已有解析结果） |
| 任务领取 | 状态条件更新（`UPDATE ... SET status='PARSING' WHERE id=? AND status='UPLOADED'`），影响行数为 0 则说明已被领取 → 避免重复处理 |
| 重试 | 3 次指数退避；区分"可恢复"（LLM 限流、网络）与"不可恢复"（加密 PDF、格式损坏） |
| 死信 | 超过阈值 → `FAILED` + `error_type` + 人工重试入口 |
| 崩溃恢复 | 定时扫描 `status IN (PARSING,CHUNKING,EMBEDDING,INDEXING) AND updated_at < now-10min` → 判定为超时任务 → 重置为 PENDING 或 FAILED（**必须有这一条，否则 Worker 崩了任务永久卡死**） |
| 可重放 | 提供 `repair --document-id` 命令重新走全链路（重建索引场景必需） |

### 19.4 切片（Chunking）策略

| 策略 | 规则 |
|---|---|
| 结构化优先 | 解析出标题层级（PDF/PPTX/DOCX 的书签与样式），**按语义层级切分**而不是定长截断 |
| 长度控制 | 目标 400-600 token，硬上限 800；超长段落按句子边界二次切分 |
| 重叠 | 相邻 chunk 重叠 15%（约 80 token），保证跨段语义连续 |
| 元数据附挂 | 每个 chunk 带 `document_id / workspace_id / chunk_index / section_path / page_no` |
| 表格处理 | 表格转 Markdown 保留结构；过大表格单独成 chunk 并标注 `type=table` |
| 代码块 | 识别后整块保留，不跨块切断（对学生笔记类内容很关键） |
| 脏数据过滤 | 页眉页脚重复内容检测与剔除（基于出现频率） |

> **存储位置**：chunk 正文存 ES（`chunk` 索引的 `content` 字段），MySQL 只存 `chunk_count` 与策略版本。理由：chunk 是检索专用数据，放 MySQL 会显著放大库体积且无查询价值。

### 19.5 Embedding

| 项 | 设计 |
|---|---|
| 批量 | 单次最多 32 条，超时 30s，失败整批重试 |
| 缓存 | 以 `sha256(chunk_text + model)` 为键缓存向量 → 同一课件被 100 人上传只需算一次 |
| 模型版本 | chunk 记录 `embedding_model` + `dim`；换模型时全量重建，**旧索引保留至新索引就绪**（双写切换，避免服务中断） |
| 限速 | 按 Provider QPS 限制 + 全局并发闸门 + Token 预算 |
| 成本控制 | 单用户日 Token 上限；文档大小上限；超限排队而非失败 |

### 19.6 删除与索引清理（常被忽略但必须做）

```
删除文档
 1. document.deleted_at = now()（软删，立即从列表与检索排除）
 2. 投递清理任务（异步）
 3. 删除 ES chunk（按 document_id 条件删除）
 4. 删除对象存储文件（含历史版本）
 5. 清理已引用该文档的 AI 引用 → 标记 ai_citation.status = SOURCE_DELETED
    · 历史回答保留，但展示"来源已删除"提示（不静默篡改历史）
 6. 清理 embedding 缓存（可选，按引用计数）
 7. 空间被删除 → 批量触发以上流程（限速删除，避免冲击 ES）
```

### 19.7 与检索、权限的衔接

**每个 chunk 在 ES 中携带三个权限字段：`visibility`、`workspace_id`、`owner_user_id`。** 检索时在 query filter 中强制生效：

```
filter: [
  { term: { status: "ACTIVE" } },
  { bool: { should: [
      { term: { visibility: "PUBLIC" } },
      { terms: { workspace_id: [authorizedWorkspaceIds] } }
  ]}}
]
```

**这样"文档删除"和"权限变更"能立刻反映到检索结果**（因为过滤字段在索引里，不需要额外同步），而权限集合来自 `AuthorizationService.authorizedWorkspaceIds()`——单一实现，全局一致。
