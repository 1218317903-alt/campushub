# document-pipeline.md · 文档流水线（Phase 05）

> **本文定位**：回答「一份上传的文件，从字节到可检索的分块，中间发生了什么」，
> 以及每一个环节为什么这么选。与 `docs/resource-authorization.md` 互补 ——
> 那篇讲「谁能做」，这篇讲「做了什么」。
>
> **权威程度**：实现级说明。与 `docs/00-工程规约.md` 冲突时以规约为准，与代码冲突时以代码为准。
>
> **最后更新**：2026-09-18 · Phase 05（`v0.5.0`）

---

## 1. 范围与不做什么

**做**：上传 → 落存储 → 入队 → 解析 → 分块 → 可读回（`READY` / `FAILED`）；两条下载路径；
删除时清理分块与字节。支持解析的类型：`text/plain` · `text/markdown` · `application/pdf`。

**不做**（每一条都写明了归属阶段，避免"看起来只是没做完"）：

| 不做 | 为什么现在不做 | 什么时候做 |
|---|---|---|
| 向量嵌入 / 语义切分 | 没有消费方。切分结果当前只被人看 | Phase 07 接检索时再按召回效果调参 |
| 块间重叠（overlap） | 重叠是为了向量召回，现在没有向量检索；预付出存储与去重成本属于给不存在的需求买单 | Phase 07 |
| OCR（扫描件 PDF） | 需要额外的 OCR 运行时与算力预算 | 出现真实需求时单独立项 |
| 文档版本树 | 当前的模型是"一份文档一份字节"，版本树是另一套读模型 | 出现"同一份文档要留历史版本"的真实需求时 |
| 秒传 / 内容去重 | `document.sha256` 已经落库，但去重需要一个"谁在引用这份字节"的引用计数模型 | 存储成本或上传量成为可观测问题之后 |
| 病毒扫描 | 需要外部引擎；当前的白名单 + 不解析未知类型 + 不内联已经覆盖了主要风险面 | 有真实的多方上传场景时 |

---

## 2. 流水线全貌

```
POST /documents (multipart)
        │
        ├─ 校验：大小 · MIME 白名单 · 空间权限（三层防线的第 ①② 层）
        ├─ 字节落存储（ObjectStorage.store）── 先算 SHA-256，再登记元数据
        └─ 一个事务里：INSERT document(parse_status=PENDING) + INSERT document_task(PENDING)
        │
        ▼
   ┌──────────────────────────────────────────────────────────┐
   │  worker 轮询（@Scheduled，默认每 3 秒）                     │
   │  ① 领取： SELECT ... WHERE status='PENDING'                │
   │           AND next_attempt_at <= now() ORDER BY ... LIMIT 5│
   │           FOR UPDATE SKIP LOCKED                           │
   │  ② 打租约： status='RUNNING', lease_owner=<实例>,           │
   │           lease_expires_at=now()+300s, progress=5          │
   │  ③ 读字节： ObjectStorage.open                             │
   │  ④ 解析：   parserRegistry 按 MIME 选解析器 → ParsedSection │
   │  ⑤ 切分：   DocumentChunker（不跨标题合并、按段落装箱）      │
   │  ⑥ 落库：   同一事务内 deleteByDocument + 批量 INSERT 分块   │
   │           + document.parse_status='READY', progress=100    │
   └──────────────────────────────────────────────────────────┘
        │                                    │
        │ 成功                                │ 失败
        ▼                                    ▼
   READY · 分块可读                  可重试 → PENDING + 退避
   （分块数 / 文本长度已更新）        · 不可重试 → FAILED（终态）
```

状态只有四个：`PENDING`（已入队）· `PROCESSING`（worker 已领走）· `READY` · `FAILED`。
`PROCESSING` 是**用户可见的"正在被处理"的唯一证据** —— 没有它，"还没轮到"与"已经坏了"
在界面上无法区分。

---

## 3. 为什么是数据库任务表，而不是 MQ

**选它，是因为本阶段真正需要的两件事它都能给：**

1. **与文档行同事务入队。** 上传是"写一行文档 + 排一个任务"，两者必须同生共死。
   用 MQ 会引入一个**双写**问题：事务提交了而消息没发出去（或反之），
   修复它需要 outbox 表 —— 而 outbox 表本身就是"数据库里的队列"，
   等于绕一圈回到起点，还多一个投递组件。
2. **`FOR UPDATE SKIP LOCKED`（MySQL 8.0+）已经提供了多实例安全的领取语义。**
   两个实例同时轮询时不会领到同一行，也不需要额外的协调服务。

**代价必须说清楚（这是它相对 MQ 的主要缺点）：**

- **`poll-interval-ms = 3000` 就是"入队到开始处理"的延迟下限。** 平均延迟约 1.5 秒，
  最坏 3 秒。对一个"上传完等解析"的场景是可接受的；对"毫秒级响应"不是一个选择。
- **吞吐有上界**：`batch-size / poll-interval-ms` = 5 / 3s ≈ 1.7 任务/秒（单实例，
  按"每次只领一批"计）。解析本身是 CPU 密集的，因此真正的上界通常先落在解析上。
- **空转查询**：即使没有任务，也每 3 秒一次 `SELECT`。这是确定的成本，不是估计。

**换掉的触发条件**（写下来是为了不在"感觉慢了"的时候凭直觉换）：
Phase 09 压测显示"入队到完成"的 P95 因为队列延迟而不达标，或出现**优先级 / 定时 / 延迟**
这类队列语义需求时，再引入 MQ。届时 outbox 已经存在，迁移路径是清楚的。

---

## 4. 任务队列的语义

### 4.1 幂等的落脚点是唯一键

`document_task` 上有 `UNIQUE KEY uk_document_task_document_type (document_id, task_type)`，
入队走 `INSERT ... ON DUPLICATE KEY UPDATE id = id`（自赋值 = 什么都不改，但拿到 0 影响行）。

**为什么不是"先查再插"**：两次上传、人工重试、worker 恢复都可能并发触发入队。
"先查再插"在并发下会得到两行任务，而两行任务意味着同一份文档被并行解析两次 ——
它们的 `deleteByDocument + INSERT` 会互相踩，最终分块表里少掉一批
（唯一键 `uk_document_chunk_ordinal` 让第二份插入失败，而不是产生重复块）。
唯一键把"只能有一个任务"变成一条**由数据库保证的事实**，而不是一段需要评审的代码。

### 4.2 租约：worker 崩溃后任务能回来

领取时写 `lease_expires_at = now() + 300s`。每次轮询的第二步是：

```sql
UPDATE document_task SET status='PENDING', lease_owner=NULL, lease_expires_at=NULL
 WHERE status='RUNNING' AND lease_expires_at <= now()
```

**因此处理语义是「至少一次」，不是「恰好一次」。** 这是刻意的取舍：
对一个"进程被杀"的场景，要么用租约 + 幂等，要么引入分布式事务。
本项目选择前者，并**让解析本身幂等**：分块是"先删后插"（同一事务），
所以重复执行一份文档的结果与执行一次相同。没有这一点，租约就只是个 bug 制造机。

`lease-seconds` 必须明显大于"正常解析一份文件"的耗时（默认 300 秒 vs 解析超时 120 秒），
否则一个慢任务会被自己的租约超时判成崩溃并被重复执行 —— 而它其实正常。

### 4.3 失败分两类，这是本阶段最容易被做错的一处

| 类别 | 例子 | 处理 |
|---|---|---|
| **可重试** | 字节读不到（存储临时不可用 / 对象缺失）、DB 瞬时失败 | `status=PENDING`、`progress=0`、`next_attempt_at = now() + backoff·2^(n-1)`（封顶 1 小时） |
| **不可重试** | 类型没有解析器、文件已损坏、页数/文本长度超限、解析超时 | 直接 `FAILED`，`parse_message` 面向用户，**不再派发** |

判定依据是**"再试一次会不会有不同结果"**：一份内容损坏的 PDF，
重试一百次只是让同样的字节被读一百次、失败一百次，并把队列占满。
因此不可重试的失败会**跳过剩余的 `max-attempts`**：重试次数是给"可能自愈的失败"用的。

`attempt_count` 仍然会自增（留痕），但 `next_attempt_at` 不再被推进 ——
它停在原地，于是"为什么它不再被领取"在数据里是可读的。

### 4.4 人工重试：用户点一下不该被退避挡住

`POST /documents/{id}/parse` 会重置任务行，让它立刻可被领取。
条件是：

```sql
status IN ('SUCCEEDED','FAILED') OR (status='PENDING' AND attempt_count > 0)
```

**排除 `RUNNING`**：正在解析的任务不该被"再排一次"，那只会让它被判成重复执行。
**但必须包含"PENDING 且尝试过"** —— 这正是本阶段修掉的一个真实缺陷：
初版只放行 `SUCCEEDED`/`FAILED`，于是一份刚失败、正在退避窗口里等待的任务
（`PENDING` + `attempt_count = 1`）在用户点「重新解析」后**什么都不发生**：
接口返回 200、状态是 `PENDING`、界面最长 2 分钟没有任何变化。
从用户的角度看，这是"按钮坏了"；从实现的角度看，它甚至没有任何报错。
判据是**"用户主动重试"与"机器自动重试"不是同一件事**：
前者的意图是"我现在就要再试一次"，后者要遵守退避。

---

## 5. 解析与分块

### 5.1 三个解析器，各自的标题约定

| 解析器 | 类型 | `heading`（分块的标题路径） |
|---|---|---|
| `MarkdownDocumentParser` | `text/markdown` | 由标题层级拼成，分隔符 `' > '`，如 `空间文档规范 > 支持的格式` |
| `PlainTextDocumentParser` | `text/plain` | 恒为 `null` —— 纯文本没有层级可提取，编造一个标题等于给出错误信息 |
| `PdfDocumentParser` | `application/pdf` | `第 N 页` |

解析器注册表在装配期**自检冲突**：两个解析器声明同一个 MIME 时直接启动失败。
若不做这件事，"实际生效的是哪一个"将取决于注入顺序 —— 一个运行时才知道、
且换个环境就变的事实。

PDF 解析按页遍历，页与页之间不合并；某页抽取失败即整份失败（而不是跳过该页）：
**静默少几页比明确失败更糟** —— 一份"看起来完整"的文档比一份"明确坏掉"的文档危险得多。

### 5.2 切分：只做一条语义判断

`DocumentChunker` 的规则：

1. **不跨越标题合并。** 一个块的标题路径是它所属段落的标题路径。
2. **按段落装箱到接近上界（默认 1200 字符）再切**，切点永远落在段落之间。
   定长切开会把列表从中间截断，后半段失去它的标题上下文。
3. **超长段落按句子结束标点（`。！？；!?;.\n`）二级切分** ——
   一个 3000 字的段落若不切开，会单独占用一个远超上界的块，
   而"块大小"正是检索时判断位置精度的依据。
4. **不做语义切分、不做块间重叠。** 理由见 `DocumentChunker` 的类注释：
   没有嵌入模型时，"按语义相似度切分"只会给出一个无法解释、也无法回归的结果 ——
   换个参数，同一份文档切出完全不同的块，而没人能说清哪个是对的。

`ordinal` 由切分器连续生成（0 起），不由调用方传入；配套的唯一键
`uk_document_chunk_ordinal (document_id, ordinal)` 保证"同一份文档的序号不重复"。

### 5.3 资源保护参数（都在 `app.workspace.parsing` 下）

| 参数 | 默认 | 它防的是什么 |
|---|---|---|
| `chunk-max-chars` | 1200 | 块过大 → 检索命中失去位置精度 |
| `max-pages` | 300 | 页数爆炸（超限失败，**不退化成"抽全文丢页码"**） |
| `max-text-chars` | 2000000 | 抽取文本爆炸（超限失败，**不做静默截断**） |
| `memory-bytes` | 16 MiB | **压缩炸弹**：几百 KB 的 PDF 可以展开成几个 GB，超出部分由 PDFBox 落临时文件 |
| `worker.parse-timeout-seconds` | 120 | 畸形对象图让解析进入近乎无限的计算 |

这些是**资源保护参数**，不是体验参数；取值刻意保守，Phase 09 拿到真实数据后再调。

---

## 6. 两条下载路径

| | 带令牌的 `/content` | 短期链接 `/download-link` |
|---|---|---|
| 鉴权时机 | **每次请求**（走三层防线第 ①② 层） | **签发那一刻**（之后凭地址即可访问） |
| 凭据 | 访问令牌（`Authorization` 头） | 一次性令牌无法复用；链接自身携带授权 |
| 适用 | 前端需要自己处理响应（如另存对话框） | 直接把地址交给浏览器 |
| 两种后端 | 恒为"字节经过应用" | 本地磁盘 → 本应用签名地址；S3 → **预签名直链**（字节不经过应用） |

`download-token-ttl-seconds` 默认 300（5 分钟）。
签发的动作会有审计记录（`DOCUMENT_DOWNLOAD_LINK`），因此"谁给了谁一个下载地址"是可追溯的。

**为什么前端只走 `/download-link`**：它让"当前是哪种存储后端"对前端不可见 ——
本地与 S3 返回的是同一个形状（URL + `direct` + `expiresAt`）。
前端判断"要不要用 blob 再自己触发保存"这件事一旦写出来，就等于把存储实现泄漏到了 UI 层。

### 6.1 令牌安全

- 令牌是 HMAC 签名的载荷（文档标识 + 过期时刻），**不是**可枚举的自增标识。
- 篡改、截断、垃圾输入**一律返回同一个错误码 `40024`，不按原因分叉** ——
  分叉会让攻击者能用错误码区分"签名错"与"载荷格式对但签名不对"。
- **令牌不能当身份凭据**：把它放进 `Authorization` 头会被当成无效访问令牌（401）。
  它只证明"有人被授权下载这一份文档"，不证明"你是谁"。
- 密钥来自 `app.workspace.documents.download-token-secret`。留空时**启动即生成随机密钥并告警**
  （重启后所有在途链接立即失效）。生产必须显式配置 —— 由启动校验强制。

### 6.2 内容永远不会被内联打开

`download-inline: false` 且响应头恒为 `Content-Disposition: attachment`、
`Content-Type: application/octet-stream`。内联意味着**用户上传的内容会在本站域的源下
被浏览器解析渲染** —— 这正是"上传一个 HTML 就得到一个 XSS"的成因。
本条与 MIME 白名单是两道**独立**的防线：白名单管"能不能存下来"，
`inline` 管"存下来的东西会不会被当成代码执行"。

浏览器验收脚本对这一点有断言（见 §8），因为它是一个**只有真实浏览器才能暴露**的
问题：接口层的测试只能断言响应头，不能断言"浏览器没有把它渲染成页面"。

---

## 7. 删除与清理

删除文档时：`document.deleted_at` 打标（软删除，元数据保留）+ 清理**分块**与**字节**。

**为什么分块必须真的删掉**：分块是内容本身，留着它等于"内容还在库里，
只是列表里看不见"。元数据保留是为了让审计与"谁删了什么"可查，
而分块没有这层价值。

**为什么清理要绕过第三层防线**：worker 与清理路径**没有身份**（不是某个用户发起的），
而第三层防线按"当前用户的空间范围"改写 SQL。空集会被改写成 `1 = 0`，
于是 `DELETE FROM document_chunk WHERE document_id = ?` **影响 0 行且不报错** ——
字节被删了、分块还在，接口上一切正常。这正是 `@Unscoped("理由")` 存在的场景：
`DocumentChunkMapper.deleteByDocument` 显式声明绕过，理由写在注解里，
并由 `WorkspaceScopeCoverageTest` 在构建期核对。

> 这一条是本阶段**第二个真实缺陷**：它的表现是"删除文档后分块表残留"，
> 而唯一能发现它的方式是直接查表 —— 接口层看不出任何异常。

---

## 8. 怎么验证它真的在工作

| 层次 | 位置 | 覆盖 |
|---|---|---|
| 单元 | `DocumentChunkerTest` · `DocumentParserTest` · `DocumentParsingRegistryTest` · `DownloadTokenServiceTest` | 装箱与二级切分、三种解析器与失败分类、解析器注册表的冲突自检、令牌的签发与验签 |
| 集成 | `DocumentPipelineIT` · `DocumentParseAuthorizationIT` · `DocumentDownloadTokenIT` | 端到端一致性（接口/库/分块三方）、重试替换而非累加、越权矩阵、令牌的全部拒绝路径 |
| 真实后端 | `S3ObjectStorageIT` | **真实 MinIO 容器**：字节真落桶、直链真签名、篡改被拒、删除后对象不在 |
| 真实浏览器 | `scripts/e2e/browser-check.mjs` | 上传 → 界面进度 → 已就绪 → 分块可读 → 链接可取字节 → 非成员不可见 → 邀请加入 |

```bash
make verify                      # 单元 + 架构 + 集成（含 MinIO 容器）
node scripts/e2e/browser-check.mjs   # 需先 make up && make run && make fe-dev
```

**集成测试怎么确定性地驱动队列**：测试 profile 关闭定时器
（`app.workspace.worker.enabled=false`），由用例自己调
`DocumentTaskRunner.claim` + `DocumentTaskProcessor.process` —— **与生产用的是同一个 Bean**，
只是不依赖定时器触发。依赖定时器会让测试变成"有时跑一次、有时跑两次"。

---

## 9. 配置清单（`app.workspace.*`）

```yaml
app.workspace:
  documents:
    max-size-bytes: 10485760            # 10 MiB，小于容器上限，让错误形状统一
    allowed-types: [...]                # 白名单，比"可解析类型"更宽
    download-inline: false              # 恒为 false
    public-base-url: ""                 # 留空 → 相对路径
    download-token-ttl-seconds: 300
    download-token-secret: ""           # 留空 → 启动时随机并告警；生产必须配置
  parsing:
    chunk-max-chars: 1200
    max-pages: 300
    max-text-chars: 2000000
    memory-bytes: 16777216
  worker:
    enabled: true                       # 测试 profile 设 false
    batch-size: 5
    poll-interval-ms: 3000              # 同时是队列延迟下限
    lease-seconds: 300
    max-attempts: 3
    backoff-seconds: 30                 # 第 n 次失败等待 30·2^(n-1)，封顶 1h
    parse-timeout-seconds: 120
  storage:
    backend: local                      # local | s3
    local-dir: ./data/workspace-documents
    s3:
      bucket: camphub-documents
      endpoint: http://127.0.0.1:9000   # 留空 → AWS 默认端点
      region: us-east-1
      access-key: ""                    # 留空 → SDK 默认凭据链（实例角色）
      secret-key: ""
      path-style: true                  # MinIO 必须 true；AWS 用 false
```

`ApplicationConfigStructureTest` 逐个键断言它们位于**正确的缩进层级** ——
写成顶层 `workspace:` 时 Spring 不会报错，应用照常启动，
直到第一次上传文档才以一个 `NullPointerException` 暴露，而报错位置与真正的原因隔了好几层。

---

## 10. 与后续阶段的接口

- **Phase 07（检索）**：`document_chunk` 就是检索的输入。需要补的是索引（全文）与
  **权限过滤前置到检索阶段**（规约 §4）—— 分块表已带 `workspace_id`，这一列就是为它准备的。
- **Phase 06（采集）**：外部内容抓取后同样要落成文档并复用这条流水线，
  但**外部内容永远是数据（UNTRUSTED）**（规约 §3）：它不得成为系统指令，
  且解析器的输入输出边界不变。
