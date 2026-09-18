# Phase 05 · Object Storage & Document Workflow —— 交付报告

| 字段 | 内容 |
|---|---|
| **阶段** | Phase 05 — Object Storage & Document Workflow |
| **版本** | `v0.5.0` |
| **日期** | 2026-09-18 |
| **上一版本** | `v0.4.0` — Phase 04 · Workspace & Resource Authorization |
| **下一阶段** | Phase 06 — Discover & Data Ingestion |
| **模板依据** | `docs/00-工程规约.md` §17（V2.1 十一节）；准出条件 §18.2 |
| **设计文档** | [`docs/document-pipeline.md`](../document-pipeline.md) · [`docs/resource-authorization.md`](../resource-authorization.md) |
| **验证提交** | `b082347`（`build(release)`）。全部测试与浏览器验收跑在这个提交上 |

> 本阶段的目标不是"接一个对象存储"，而是让文档从**一堆能存能下的字节**变成
> **可被处理的最小单元**：`Upload → 任务队列 → 解析 → 分块 → READY / FAILED`。
> 之所以值得单独立一个阶段，是因为这条链路上每一个环节的失败都是**静默**的 ——
> 字节存了但解析没跑、解析跑了但分块落在句子中间、分块写了但被某层防线改写成 0 行 ——
> 这些在"接口返回 200"的视角下全都看不出来。
> 因此本阶段的交付重心是**任务队列的语义**（幂等 · 租约 · 失败分类 · 人工重试）
> 与**可复现的验证**，存储与解析器是实现这些语义的载体。

---

## Implemented

### 字节存储：一个端口，两种实现

- **`ObjectStorage`（端口）** 在 Phase 04 建立，本阶段补上第二个实现 ——
  端口的价值只有在出现第二个实现时才被检验。
- **`LocalFileObjectStorage`**（Phase 04）：落在本地磁盘，是**默认后端**。
  保持默认的理由是"克隆下来就能跑"不必先起对象存储。
- **`S3ObjectStorage`**（Phase 05，AWS SDK v2）：MinIO 与 AWS S3 的差异被
  `endpoint` + `path-style` 两个配置彻底吃掉，因此适配器里**不存在任何
  "如果是 MinIO 就怎样"的分支**。部署到 AWS 时把 `endpoint` 留空即可，不需要改代码或加开关。
- **不自动建桶**：桶的区域与策略属于部署决策，让应用启动时"顺手建一个"会把这两件事隐掉。

### 解析：解析器端口 + 三个实现

| 解析器 | 格式 | 标题路径的来源 |
|---|---|---|
| `PlainTextDocumentParser` | `text/plain` | 无层级，整篇一段（并处理 GBK 等非 UTF-8 编码） |
| `MarkdownDocumentParser` | `text/markdown` | ATX 标题（`#`），**代码围栏内的 `#` 不算标题** |
| `PdfDocumentParser` | `application/pdf` | 每页产出一个章节，标题为页码 |

- `DocumentParsingRegistry` 按 MIME 与扩展名挑选解析器；**挑不到就是不可重试失败**
  （重试一个没有解析器的文件，只是把同一份字节再读一遍）。
- `ParsedDocument` / `ParsedSection` 是与格式无关的中间表示 ——
  分块器因此完全不知道 PDF 与 Markdown 的存在。

### 分块：两级切分

- 先按**标题路径**分段（`部署 > 环境要求`），再在段内按字符数上界切。
- 段内切分**优先落在句末标点之后**：在 `[起点, 起点+上界)` 窗口内往回找最后一个句末标点；
  一个都没有才硬切（代码块、长 URL 列表、被抽平的表格都是这种情况）。
- 分块带 `heading`（所属标题路径）与 `char_count`。**不用 token 数** ——
  本阶段没有 tokenizer，估出来的值是假的。

### 任务队列：一张表 + 轮询 worker（不是 MQ）

| 组件 | 职责 |
|---|---|
| `document_task` 表 | 持久化队列。`UNIQUE KEY (document_id, task_type)` 是幂等的落脚点 |
| `DocumentTaskRunner.claim` | 领取：`SELECT ... FOR UPDATE SKIP LOCKED` + 写租约，单次批量 5 |
| `DocumentTaskProcessor.process` | 处理一份任务（与生产同 Bean，测试直接调用它而不是另写一套） |
| `DocumentTaskWorker` | `@Scheduled(fixedDelayString = "${app.workspace.worker.poll-interval-ms}")`，3 秒一轮 |

- **任务类型**：`PARSE`（解析）与 `CLEANUP`（删除文档后把字节真正从存储清掉）。
- **租约**：`lease_owner` + `lease_expires_at`（300 秒）。过期的 `RUNNING` 任务被回收重排 ——
  这是崩溃恢复的唯一依据，进程被 kill 时不需要任何代码去"处理"它。
- **失败分两类**：可重试（字节读不到、DB 瞬时错误）按 `30 · 2^(n-1)` 退避，封顶 1 小时，
  上限 3 次；不可重试（无解析器、文件损坏、超限、超时）**直接终态**。
- **人工重试切退避**：`POST /parse` 把任务重置回队列，等待退避中的任务也不例外。

### 下载：两条路径并存

| 路径 | 鉴权方式 | 适用场景 |
|---|---|---|
| `GET .../documents/{id}/content` | 每次请求带 `Authorization` 头，走完整三层防线 | 站内、需要即时撤销 |
| `POST .../documents/{id}/download-link` → `GET /api/v1/document-downloads/{token}` | 签发时走完整三层防线，之后凭 HMAC 签名 + 过期时刻 | 交给浏览器下载器或另一个无身份客户端 |

两条路径的响应都强制 `Content-Disposition: attachment` —— **下载内容永不内联**。

### 前端：空间与文档界面（Phase 04 遗留的空白）

- `views/workspace/WorkspaceListView.vue`：我的空间、新建空间、用邀请码加入。
- `views/workspace/WorkspaceDetailView.vue`：四个 Tab（文档 / 协作笔记 / 成员 / 邀请），**全部懒加载**。
- `components/WorkspaceDocumentPanel.vue`：上传、解析状态与进度、分块数/字数、失败原因、
  重新解析、删除；`PENDING/PROCESSING` 期间**有界轮询**（3s × 最多 40 轮）。
- `views/workspace/DocumentDetailView.vue`：解析结果、按 `ordinal` 分页读分块、签发下载链接、重试、删除。
- `components/WorkspaceNotePanel.vue`：协作笔记的列表/详情/编辑/新建。
- `api/workspace.ts`：20 个接口的 TS 契约；`api/http.ts` 新增 multipart 上传路径。

### 配置（三块，各自独立）

```yaml
app.workspace:
  storage:  backend: local|s3 · local-dir · s3.{bucket,endpoint,region,path-style,access-key,secret-key}
  parsing:  chunk-max-chars: 1200 · max-pages: 300 · max-text-chars: 2000000 · memory-bytes: 16777216
  worker:   enabled · batch-size: 5 · poll-interval-ms: 3000 · lease-seconds: 300
            max-attempts: 3 · backoff-seconds: 30 · parse-timeout-seconds: 120
```

`parsing.*` 全部是**资源保护参数**：它们决定"一份用户上传的文件能让服务端做多少工作"。
刻意保守，等 Phase 09 压测拿到真实数据再调。

---

## Architecture Decisions

### 1. 任务队列用数据库表，而不是消息队列

**决策**：`document_task` 一张表 + `@Scheduled` 轮询，不引入 RabbitMQ / Kafka / Redis 队列。

**理由**：本阶段的真实需求是"任务不丢、不重复、崩溃能恢复、失败可分人工与自动"，
这四条一条都不需要 MQ。而 MQ 会引入一个**必须部署、必须监控、必须处理背压**的新组件 ——
规约要求新增中间件先有压测数据支撑，现在没有。

**代价（明确说说）**：**轮询间隔就是"任务从入队到开始处理"的延迟下限。**
一份文档上传后至少要等一个 3 秒周期才会开始解析，这不是可以调参消掉的实现细节。
`poll-interval-ms` 的注释里写了这句话，`architecture.md` 的取舍表里也写了。
判据（什么情况下该换 MQ）写在 `docs/document-pipeline.md` §3。

### 2. 幂等落在唯一键上，而不是落在应用层的判断里

**决策**：`UNIQUE KEY (document_id, task_type)`。重试走"重置同一行"，不插新行。

**理由**：没有这个键，"幂等"就只是应用层一句无法被验证的承诺 ——
用户网络重试造成的重复上传、应用层重入造成的重复入队，都会真的产生第二个解析任务。
有了它，第二次入队撞唯一键、按"已存在则不动"处理，**这件事是数据库保证的**。

### 3. 崩溃恢复用租约，不用心跳计数

**决策**：worker 领任务时写 `lease_expires_at = now + 300s`；回收靠"`RUNNING` 且租约已过期"。

**理由**：心跳方案需要某个组件记住"上一位 owner 的状态"，而租约只需一次时间比较。
代价是租约过短会误判慢任务、过长会拖慢恢复 —— 300 秒的选择标准是"明显大于正常解析耗时"。

### 4. 失败分两类，而不是统一重试 N 次

**决策**：`DocumentParseException` 携带 `retryable` 标志；不可重试的失败直接进终态。

**理由**：一份"没有解析器"的文件重试三次的结果与重试一次完全相同，
只是多占用两次 worker 时间并把终态时间往后推 90 秒。
把"能不能靠重试解决"这件事在抛出点分类，比在重试策略里猜要准确。

### 5. 下载令牌端点没有身份能力点

**决策**：`GET /api/v1/document-downloads/{token}` 不标 `@PreAuthorize`，也不走资源级判定与空间范围。

**理由**：这条请求里根本没有身份 —— 它的场景是"把地址交给浏览器的下载器"，
那里没有 `Authorization` 头可带。授权信息全部编码在令牌里，而**签发那一刻**走的是完整三层防线。
给它加一条 `@PreAuthorize` 会诱导出一个"在无身份请求上永远不成立"的判据。
完整论证见 `docs/resource-authorization.md` §8.1。

### 6. 分块表带冗余的 `workspace_id`

**决策**：`document_chunk.workspace_id` 是冗余列（文档不跨空间移动），且带外键。

**理由**：它让第三层防线**可以直接作用于分块表**。没有它，分块的范围判定只能靠
`JOIN document` —— 而拦截器改写的是单表 SQL，一旦需要 JOIN 就等于这一层防线对这张表失效。

### 7. 解析与分块参数是资源保护参数，不是性能参数

**决策**：`max-pages: 300`、`max-text-chars: 2000000`、`memory-bytes: 16 MiB`，
超限一律**失败**而不是"抽一部分"。

**理由**：静默截断会让"全文可检索"这个承诺变得不真实，而用户无从知道少了哪一段 ——
这比失败更糟。`memory-bytes` 是对"压缩炸弹"的直接防护：几百 KB 的 PDF 可以展开成几个 GB。

### 8. 重新解析单列一个权限点

**决策**：`document:retry`（第 17 个权限点），不复用 `document:upload`。

**理由**：两者不是有意为之的细分，而是真实的差异 —— 上传的代价局限于自己，
重新解析会**替换这份文档现有的可检索内容**并占用解析资源。
混用一个码，将来任何"允许上传但不允许重跑解析"的角色划分会立刻失效，
而失效的形式是"权限比预期更宽"。

---

## Git History

本阶段 17 个提交（`feature/phase-05-object-storage`，按时间顺序）：

| Commit | 内容 |
|---|---|
| `45e46b6` | `build(deps)`：引入 PDFBox 与 AWS SDK S3（BOM 不托管，版本钉死） |
| `d21129d` | `feat(workspace)`：任务队列驱动的解析与分块、迁移 V6、S3 后端、下载令牌 |
| `775de1d` | `test(workspace)`：解析器注册表 · 新表的作用范围覆盖 · 重试矩阵 |
| `b8d6bcb` | `fix(workspace)`：令牌校验永远失败 · 超长段落切点从未落在句子之间 |
| `9d219d5` | `fix(workspace)`：worker 删分块被第三层防线静默改写为 0 行 |
| `2b1a7e1` | `test(workspace)`：三个解析器的行为与失败分类 |
| `c10c7b0` | `fix(workspace)`：人工重试没能切开正在等待的退避窗口 |
| `ca51e00` | `test(workspace)`：文档流水线 · 下载令牌 · 重新解析的授权 |
| `4db8386` | `test(workspace)`：S3 后端对真实 MinIO 容器的验证 |
| `d70fcf2` | `feat(web)`：multipart 上传走独立路径 |
| `c0cde29` | `refactor(web)`：markdown 排版上移到全局样式 |
| `ae19e10` | `feat(web)`：空间 / 笔记 / 文档三个页面 |
| `6e5fcb5` | `test(web)`：API 契约断言 + 零依赖浏览器验收脚本 |
| `8d40a3c` | `build(make)`：`make e2e` |
| `a69f645` | `docs(workspace)`：`document-pipeline.md` + 架构 / 授权 / 环境 / README / CHANGELOG |
| `b082347` | `build(release)`：版本号提升至 `0.5.0` |
| `eaf8997` | `fix(e2e)`：验收脚本默认地址指向 Vite 真正监听的 host |

规模：功能提交合计 `87 files changed, 12862 insertions(+), 346 deletions(-)`。

---

## Database Changes

**唯一迁移：`V6__document_pipeline.sql`**（V1–V5 未改动；`validate-on-migrate: true` 会拦住对历史脚本的修改）。

### 新表（2 张）

| 表 | 说明 |
|---|---|
| `document_task` | 持久化任务队列。`uk_document_task_document_type` 是幂等的落脚点；`idx_document_task_dispatch (status, next_attempt_at)` 与领取语句的 `WHERE` 列顺序一致，因此 `ORDER BY next_attempt_at` 也走同一棵索引；外键 `ON DELETE CASCADE` |
| `document_chunk` | 解析产出的分块。`uk_document_chunk_ordinal (document_id, ordinal)` 既是唯一约束也是**重试幂等的工具**（重跑时先按文档删旧块，删漏了会被立刻发现）；`workspace_id` 是让第三层防线能直接作用于本表的冗余列 |

### 变更既有表（`document`，只加列与改注释）

- 新增 5 列，全部 `NOT NULL DEFAULT` 或允许 `NULL`，因此对已有行是**安全的加列**（不需要回填脚本）：
  `parse_progress` · `chunk_count` · `text_length` · `parser_version` · `parsed_at`。
  V5 期间上传的文档在本迁移之后仍是 `PENDING`，会被 worker 自动捡起来 —— 不会变成孤儿。
- `MODIFY COLUMN` 更新 3 处**已经过期的注释**：V5 写下 `parse_status` 注释时 Phase 05 还没实现，
  当时写的是"本阶段只会出现 PENDING"这类临时表述。注释留在库里，下次有人
  `SHOW FULL COLUMNS` 读到的必须是当前事实。

### 不建的东西（同样是决策）

- **不建 `FULLTEXT` 索引**：Phase 07 的检索要走独立的搜索栈（MySQL 基线 + ES 双跑）。
  现在建一个 ngram 全文索引，到 Phase 07 会被推开，属于提前实现。
- **不设 `priority` 列**：当前没有"哪类任务更紧急"的证据，加一个恒为默认值的排序列
  只会让领取语句多一个不起作用的排序项。

### 权限点与基线

- 新增 **1 个权限点** `document:retry`（id 显式指定 17，接 V5 的 16），授予基础角色 `USER`。
- `app_metadata.schema.baseline` 更新为 `'V6'` —— 不更新它，`/api/v1/system/info`
  会继续报 V5，一个看起来正常、实际错误的结论。

---

## API

新增 4 个端点（全部需要认证；`SecurityConfig` 仍是"默认拒绝"）：

| 方法 | 路径 | 身份能力 | 资源级判定 |
|---|---|---|---|
| `POST` | `/api/v1/workspaces/{wsId}/documents/{docId}/download-link` | `document:download` | 成员即可 |
| `GET` | `/api/v1/workspaces/{wsId}/documents/{docId}/chunks` | `document:read` | 成员即可 |
| `POST` | `/api/v1/workspaces/{wsId}/documents/{docId}/parse` | `document:retry` | **区分归属**：成员仅限自己上传的 |
| `GET` | `/api/v1/document-downloads/{token}` | **无** | **无**（令牌自带授权，见 §8.1） |

文档列表接口在 Phase 04 只返回元数据；本阶段的列表与详情响应同时给出
`parseStatus` / `parseProgress` / `chunkCount` / `textLength` / `parseMessage` /
`parserVersion` / `parsedAt`，以及服务端算好的 `deletableByMe` / `retryableByMe`。
前端只用后者决定显示哪个按钮，不自己推导角色。

前端新增 3 条路由：`/spaces` · `/spaces/:publicId` · `/spaces/:publicId/documents/:docPublicId`
（路径带空间标识，与第 ② 层防线的定位方式一致）。

---

## Tests

### 后端全量（验证提交 `b082347`）

```text
$ ./mvnw -B clean verify

[INFO] Tests run: 190, Failures: 0, Errors: 0, Skipped: 0     ← surefire：单元 + 架构
[INFO] Tests run: 145, Failures: 0, Errors: 0, Skipped: 0     ← failsafe：集成（真实 MySQL 8.4 / MinIO 容器）
[INFO] BUILD SUCCESS
[INFO] Total time:  01:48 min
```

**用例数口径（必须写明，否则同一份结果能报出两个数）**：
`target/surefire-reports/*.xml` + `target/failsafe-reports/*.xml` 里 `<testcase>` 元素逐条计数 ——
**surefire 190 + failsafe 145 = 335 个用例，0 失败 0 错误 0 跳过**。

若改按套件 `<testsuite tests="...">` 属性求和，得到的是 188 + 145 = 333，
**比实际少 2 个**。原因已定位：`DocumentParserTest` 有三个 `@Nested` 分组
（`纯文本` / `PDF` / `Markdown`），surefire 写出的 `tests` 属性对嵌套类少计 2 个，
而该 XML 里的 `<testcase>` 元素是 22 个（属性写 20）。
本项目此前（Phase 03 / 04）两套口径恰好一致，本阶段首次出现分歧 ——
因此后续引用一律以 `<testcase>` 计数为准，控制台那行 `Tests run: 190` 与它一致。

### 前端

```text
$ cd frontend && npm run typecheck && npm test && npm run build

> camphub-frontend@0.5.0 typecheck
> vue-tsc --noEmit -p tsconfig.app.json
                                          ← 无输出 = 类型检查通过
> camphub-frontend@0.5.0 test
> node tests/run.mjs
# tests 13
# pass 13
# fail 0

✓ built in 1.66s
```

### 浏览器端到端（真实 Chrome，CDP 驱动）

```text
$ node scripts/e2e/browser-check.mjs

ok   前端可访问且社区页渲染完成
ok   演示账号 demo01 登录
ok   「我的空间」页面可用
ok   创建一个空间并自动进入
ok   上传一份 Markdown，列表里出现解析状态
ok   解析在浏览器里自行推进到「已就绪」并显示分块数
ok   文档详情页展示分块、标题路径与正文
ok   下载链接可直接取到字节，且内容与上传的一致
ok   协作笔记可新建并在浏览器里渲染成 HTML
ok   发出定向邀请并取到邀请码
ok   成员列表把拥有者标为「拥有者」
ok   非成员（demo02）直接访问空间地址得到 404 文案
ok   非成员的「我的空间」里没有这个空间
ok   用邀请码加入后可以进入空间，并能在成员列表里看到自己
ok   成员可以读到自己没有上传的文档及其分块
ok   伪造的空间与文档标识一律不可见

全部通过：16 项。截图见 target/e2e/
```

### 本阶段新增用例（77 个）

| 类型 | 类 | 用例 |
|---|---|---|
| 单元 | `DocumentParserTest` | 22（三解析器的正确路径与失败分类，含 3 个 `@Nested` 分组） |
| 单元 | `DocumentChunkerTest` | 11（ordinal 连续 · 装箱 · 不跨标题合并 · 两级切分 · 空内容） |
| 单元 | `DownloadTokenServiceTest` | 10（签发/校验 · 篡改载荷与签名 · 换密钥 · 过期边界 · 非法输入） |
| 单元 | `DocumentParsingRegistryTest` | 2 |
| 集成 | `DocumentDownloadTokenIT` | 11 |
| 集成 | `DocumentParseAuthorizationIT` | 8 |
| 集成 | `DocumentPipelineIT` | 7 |
| 集成 | `S3ObjectStorageIT` | 6（真实 MinIO 容器） |

此外 `WorkspaceActionTest` 与 `WorkspaceScopeCoverageTest` 的**既有用例被扩充**
（新增动作的每一格断言、新表的标注覆盖），未新增用例数。

按口径换算：surefire 145 → 190（+45）、failsafe 113 → 145（+32），合计 258 → 335（+77）。

### 测试写法的三个刻意选择

1. **测试直接调 `DocumentTaskRunner.claim` + `DocumentTaskProcessor.process`，而不是另写一套。**
   测试 profile 关掉定时器（`app.workspace.worker.enabled=false`），用例自己驱动一轮。
   依赖定时器会让测试不稳定（有时跑一次，有时跑两次）。
   但被调用的**是生产的那两个 Bean**，因此测到的行为与线上一致。
2. **S3 后端用真实的 MinIO 容器测，不用 mock。**
   S3 兼容性的问题（path-style 寻址、桶名规则、`endpoint` 覆盖）恰恰在 mock 里全部消失。
   退化为 mock 等于把"我们这个后端能不能连上真的对象存储"这个问题从测试里删掉。
3. **下载令牌的用例断言"各种非法输入返回空而不是抛异常"。**
   令牌端点的输入完全来自外部（URL 里的一段字符串），
   一个抛异常的实现在生产上表现为 500 —— 而 5xx 会让客户端反复重试一个永远不会成功的请求。

### 由"真正运行"暴露、而不是由编写暴露的缺陷（4 个）

四个都写完了、看起来是对的，跑起来才发现。共同点是**失败模式为静默或指向错误的原因**：

| # | 缺陷 | 表现 | 为什么难发现 |
|---|---|---|---|
| 1 | 令牌签名经 `base64 → String → US_ASCII` 往返比较 | **任何令牌都验不过**，每条下载链接都返回 `40024` | 签名是 32 字节二进制，必然包含非法 UTF-8 序列，被替换成 U+FFFD 后与原值不等。单测写出来立刻暴露；不写单测的话，这条路径只在"用户点下载"时才走到 |
| 2 | 分块器的句末标点判定发生在**累积长度已超上界之后** | "按句子切"这一级**从未起过作用**，长段落切点落在任意位置，且不报错 | 切出来的块仍然非空、仍然连续，肉眼看起来完全正常。只有断言"切点紧跟句末标点"才能发现 |
| 3 | `DocumentChunkMapper#deleteByDocument` 标了 `@ScopedTable`，但唯一调用者是**无身份的后台 worker** | 第三层防线把它改写成 `1 = 0`，删 0 行且不报错；随后插新块撞唯一键 → **每次重新解析都失败**，错误信息指向唯一键冲突 | 真正的原因（这一层防线在 worker 里没有身份可用）与报出来的现象（唯一键冲突）相距很远。这是 Phase 04 写下的"后台 worker 触及私有表必须 `@Unscoped`"那条约定的第一次真实违反 |
| 4 | `resetForRetry` 的条件只有 `status IN ('SUCCEEDED','FAILED')` | 一次失败后**正在等退避窗口**的任务不会被重置：响应 200、状态 `PENDING`、界面最长两分钟毫无变化；用户会去点第二次、第三次 | 这与代码注释里写的理由并不一致 —— 注释针对的是"正在排队"与"正在跑"，而等待退避的任务恰恰是人工重试最该生效的那一类 |

第 3 条还顺带暴露了一个遗漏：`completeCleanup` 没有清分块。文档是软删除、行还在，
因此外键的 `ON DELETE CASCADE` 不会触发 —— 一份"已删除"的文档，内容会完整躺在分块表里。

另有一个不算缺陷但值得记的坑：`scripts/e2e` 的默认 `base-url` 写的是 `127.0.0.1:5173`，
而 Vite 默认监听 `localhost`（本机解析到 `::1`），两者连不上。表现是脚本第一步就失败，
错误看起来像页面渲染出了问题，真正的原因在另一层（监听地址），中间没有任何提示。

---

## Security

本阶段新增的安全保障（全部有对应测试）：

- **下载令牌不是绕过授权的后门**：签发的唯一入口是 `POST .../download-link`，
  而它走完整三层防线；令牌由 HMAC-SHA256 签名并带过期时刻。
  改动一个字节、换一份文档、过期之后再用，一律拒绝（`DownloadTokenServiceTest` + `DocumentDownloadTokenIT`）。
- **下载内容永不内联**：两条下载路径都强制 `Content-Disposition: attachment`。
  一份被解析的文档是用户上传的任意字节，内联渲染等于让上传者决定别人的浏览器执行什么。
- **重新解析区分归属**：普通成员只能重试自己上传的文档，拥有者与管理员可以处理任何一条
  （`DocumentParseAuthorizationIT`）。不这样做的直接后果是"反复重解析别人的文档，
  让那份内容在一段时间里不可检索"。
- **解析的资源保护参数**：页数上限、字符数上限、堆内驻留上限、单次解析超时。
  它们防的是畸形输入（深层嵌套对象图、压缩炸弹）让一个 worker 线程陷入近乎无限的计算。
- **后台 worker 的私有表查询全部 `@Unscoped` 并写明理由**，
  由 `WorkspaceScopeCoverageTest` 在构建期强制（这条约定的价值在本阶段被缺陷 #3 验证过）。
- **越权在真实浏览器里被验证**：非成员直接访问空间地址得到 404 文案、页面里不出现空间名、
  列表里没有这个空间、伪造标识一律不可见 —— 这四项此前只是接口层的断言。

---

## Performance

**Not benchmarked yet.**

本阶段没有做压测。已有的只是**可复现的功能性测量**，不足以称为性能数据：

- 文档流水线的吞吐上限由 `worker.batch-size` / `worker.poll-interval-ms` 决定，
  当前配置（5 / 3 秒）对应约 1.7 任务/秒。这是**算出来的**，不是测出来的。
- **轮询间隔就是"任务从入队到开始处理"的延迟下限**（当前 3 秒）——
  这是数据库队列相对 MQ 的主要代价，必须能被明确说出。
- Phase 03 记录的社区基线查询测量（EX-000）与 Phase 04 的深分页实测仍然有效，
  但它们与本阶段新增的链路无关。

调参与换 MQ 的判据都写在 `docs/document-pipeline.md` §3，触发条件是 Phase 09 的压测数据。

---

## Known Limitations

- **不支持 OCR**：扫描件 PDF 抽不出文本层，会以 `FAILED` 收尾（`text_length = 0` 且有内容）。
- **分块无重叠、无向量嵌入**：只按标题与字符数切分。重叠是 Phase 07 接检索时按召回效果调的参数。
- **无上传配额，也无孤儿对象回收**：删除文档会清分块与字节，但没有上传量上限；
  被外部删掉的对象不会被回收。
- **S3 后端只在集成测试里跑过**：生产形态（真实凭据来源、桶策略、跨域）未验证。
- **本地后端的"直链"仍经过应用**：是应用签名的地址，不是 CDN 直出。
- **解析吞吐靠单实例轮询**：多实例部署时需要确认租约机制在并发下仍然正确（逻辑上支持，未实测）。
- **`TEAM` 与 `PRIVATE` 在授权上仍然无区别**（Phase 04 遗留，未变）。
- **空间无法转让**：拥有者不能退出，只能删除空间或保持原样。

---

## Technical Debt

本阶段新增的技术债（连同「什么时候还」）：

| 项 | 现状 | 触发条件 / 计划 |
|---|---|---|
| 解析吞吐参数未标定 | `batch-size` / `poll-interval-ms` 是估的 | Phase 09 压测后调，或按 `document-pipeline.md` §3 的判据换 MQ |
| 分块切分粒度未按检索效果调 | `chunk-max-chars: 1200` 是估的 | Phase 07 拿到召回数据后调（含是否加重叠） |
| S3 生产形态未验证 | 只在 MinIO 容器里跑过 | 首次真实部署时验证凭据来源与桶策略 |
| 无配额与对账任务 | 无上传量上限，无孤儿回收 | 出现真实容量压力时用对象存储生命周期规则 + 对账任务 |
| 下载令牌撤销不即时 | 已签发令牌在有效期内仍然可用 | 需要即时撤销时给令牌加版本号或存储侧黑名单 |
| `parser_version` 只写不读 | 列已存在并写入，但没有"按版本筛出需重跑的文档"的入口 | 解析器升级导致结果变化时补一个运维入口 |
| 前端无组件测试 | 仍是类型检查 + 构建 + 浏览器验收三层 | 业务组件继续增多时引入 Vitest（沿用 Phase 04 的判据） |

---

## Next Phase Dependencies

Phase 06（Discover & Data Ingestion）依赖本阶段的东西，以及本阶段为它留的接口：

1. **`ObjectStorage` 端口与 S3 后端可直接复用**：外部内容要落地原文件（快照）时，
   不需要再写一个存储适配器 —— 端口已经有两个实现，第三个消费者不会改变它的形状。
2. **`document_chunk` 是 Phase 07 检索的料**：分块被设计成"可检索的最小单元"，
   带 `workspace_id`（权限前置到检索阶段时可直接作用于本表）与 `heading`（命中高亮的粒度）。
   本阶段刻意**不建全文索引**，把索引决策留给 Phase 07 的搜索栈。
3. **任务队列的形态是 Phase 06 采集任务要回答的第一个问题**：
   采集有**外部限流**（RSS / GitHub API），这与解析任务的性质不同 ——
   解析是"越快越好"，采集是"必须慢下来"。`document-pipeline.md` §10 记了这件事，
   Phase 06 必须决定是复用这张表加分片租约，还是引入独立的调度器。
4. **外部抓取内容永远是数据（UNTRUSTED）**：本阶段的解析器已经按这个原则写
   （解析器不认识任何指令，只产出文本与层级），Phase 06 的采集适配器必须继承它。
5. **权限过滤仍在检索阶段之前**：Phase 06 的 Discover 涉及"我能不能看到这份外部内容"，
   第 ②③ 层防线已就位，不需要新的机制，但**必须显式标注**每张新表的 `@ScopedTable` / `@Unscoped`
   （`WorkspaceScopeCoverageTest` 会在构建期拦住漏标）。

---

## 附录 · 推送之后才暴露的缺陷（`v0.5.1` / `v0.5.2`）

**上面所有"全绿"的结论都成立于本机的环境**，而它们在 CI 上不成立。
推送 `v0.5.0` 之后 GitHub Actions 的「后端 · 构建与全量测试」连续三次红灯，
其中含一个正文完全没有覆盖的真实缺陷。补记在此，而不改写上面的数字 ——
那些测试结果本身是真的，缺的是它们**成立的条件**。

### 两个补丁

| 版本 | 修了什么 | 是不是红灯的根因 |
|---|---|---|
| `v0.5.1` | `minio/minio` 在 Docker Hub 上已 404（MinIO 迁到 quay.io），改 `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` | **不是**（修完仍红），但它本身是一处真实缺陷 |
| `v0.5.2` | 集成测试的 JDBC 连接时区跟随"跑测试那台机器的时区"，而容器固定在 +08:00 | **是** |

### 根因：测试依赖了"跑测试的机器恰好与容器同时区"

`@ServiceConnection` 生成的测试 JDBC URL **不带** `application.yml` 里的
`connectionTimeZone` / `forceConnectionTimeZoneToSession`，驱动因此退回默认的 `LOCAL`。
于是 `document_task` 里出现两套时钟：

- `insertIfAbsent` 不给 `next_attempt_at` 赋值 → 列默认值 `CURRENT_TIMESTAMP(3)`，取**会话时区**（+08:00）；
- `reschedule` / `resetForRetry` / `markRunning` 写入应用传入的 `Instant` → 按 **JVM 时区**换算。

本机 JVM 恰好是 +08:00 → 一直绿。CI runner 是 **UTC** → 应用算出的"现在"比库里早 8 小时，
领取语句 `next_attempt_at <= ?` 恒不成立 → **一份文档都不会被解析**，
10 个用例一起失败，而失败信息写的是"分块数为 0"。

**请注意这个缺陷的性质**：它不是"测试写得不好"，而是**测试跑在一份生产不存在的配置上**。
生产的 `application.yml` 本来就带这两个参数（`forceConnectionTimeZoneToSession=true`
把会话时区也钉在 +08:00），因此生产是自洽的。补上参数之后，测试才第一次跑在生产约定上。

### 验证（复现命令）

```text
TZ=UTC ./mvnw -B clean verify              # 修复前：10 个失败；修复后：BUILD SUCCESS
TZ=America/New_York ./mvnw -B clean verify # 另一个方向的时区，同样 BUILD SUCCESS
./mvnw -B clean verify                     # 本机默认 +08:00，不回归
```

三次均为 **335 用例 0 失败**（按 XML `<testcase>` 计数：surefire 190 + failsafe 145）。
CI 最终在 `main` = `5325283` / `develop` = `760080a` 上 **success**。

### 三条教训（都写成了可执行的约定）

1. **阶段报告里的"全绿"必须同时给出验证环境。** 只写"BUILD SUCCESS"传递的信心是虚的 ——
   本阶段正文写下 335 用例全绿时，没有人问过"这些用例在 UTC 的机器上还成立吗"。
   凡涉及时间的断言，能一键换时区验证的就应该真的换一次（`TZ=UTC` 是最省事的那个）。
2. **"本机已有"不等于"别人能拿到"。** `minio/minio:latest` 在本机"能用"，
   只是因为本机早先把 quay 的镜像人工打过同名 tag（两者 `docker images` 里 ID 相同）。
   判断容器依赖是否可复现，要看引用的镜像在公开仓库里能不能拉到。
3. **没修好就说明还有第二个原因，不能收工。** `v0.5.1` 在 CHANGELOG 与 tag 里把
   MinIO 镜像写成了"根因"，修完仍然红 —— 这条错误判断保留在 CHANGELOG 里没有删，
   因为"当时为什么这么判断"本身就是后续排查同类问题的线索。
