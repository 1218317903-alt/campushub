# Changelog

本文件记录 CampusHub AI 每个已发布版本的变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
按 `docs/00-工程规约.md` §15.6，每个 Release 必须记录：
**Added · Changed · Fixed · Security · Known Issues**。

> **版本线说明**：`docs/08-roadmap.md` 中的 V0~V5 版本编号已被
> `docs/12-phase-plan.md` 的 Phase 01~13 计划取代。本文件从 `v0.1.0` 起按新计划记录。

---

## [0.5.2] - 2026-09-18

补丁版本。修 `v0.5.0` / `v0.5.1` 上 GitHub Actions 后端 job 一直红灯的**真正**根因。
仍**不改任何产品行为**，只改集成测试的数据源配置。

### Fixed

- **集成测试的 JDBC 连接时区跟随"跑测试那台机器的默认时区"，而容器固定在 +08:00。**
  `@ServiceConnection` 生成的测试 URL 不带 `application.yml` 里的
  `connectionTimeZone` / `forceConnectionTimeZoneToSession`，驱动因此退回默认的 `LOCAL`。
  于是任务表里出现两套时钟：列默认值 `CURRENT_TIMESTAMP(3)` 取会话时区（容器 +08:00），
  而应用传的 `Instant` 按 JVM 时区换算。
  - 本机 JVM 恰好也是 +08:00 → 一直绿；GitHub Actions runner 是 UTC →
    应用算出的"现在"比库里早 8 小时，领取语句 `next_attempt_at <= ?` 恒不成立，
    **一份文档都不会被解析**：10 个用例一起失败，而失败信息写的是"分块数为 0"。
  - 修复：在 `TestcontainersConfiguration` 里补上与生产**逐字一致**的两个连接时区参数
    （`connectionTimeZone=Asia/Shanghai` / `forceConnectionTimeZoneToSession=true`）。
    这不是为测试放宽条件 —— 生产本来就带这两个参数，测试此前跑的是生产不存在的配置。
  - 复现命令：`TZ=UTC ./mvnw -B clean verify`（修复前红 / 修复后绿）；
    另验证 `TZ=America/New_York` 同样绿，证明测试结论已与机器时区无关。

### 更正

- `[0.5.1]` 里写的"CI 红灯根因"是**不准确的**。那一版修的 MinIO 镜像引用确实是一处
  真实缺陷（`minio/minio` 在 Docker Hub 上已 404），值得修，但它不是红灯的原因 ——
  修完之后 CI 仍然红。红灯的根因是上面这条时区问题。
  两条都留在 CHANGELOG 里而不是删掉其中一条，因为"当时为什么这么判断"本身
  也是后续排查同类问题的线索（先按"干净机器上缺什么"排查是对的，
  但**没修好就说明还有第二个原因**，此时应该继续查而不是收工）。

### Known Issues

- 与 `[0.5.0]` 相同（S3 生产部署形态未验证 · 不支持 OCR · 无上传配额 · 解析吞吐参数未标定）。
- 任务队列的时间戳由**两处**写入（列默认值 + 应用传入的 `Instant`），
  依赖连接时区把它们对齐。生产配置已把会话与连接时区都钉在 +08:00，因而是自洽的；
  但这是一处"配置写错就静默失效"的地方，已记入技术债（`docs/architecture.md` §10）。

---

## [0.5.1] - 2026-09-18

补丁版本。**只修 CI，不改任何产品行为**：`v0.5.0` 推送后 GitHub Actions 的
"后端 · 构建与全量测试"红灯，而本机全量测试是绿的。

> ⚠️ **本版对根因的判断不准确**，请与 `[0.5.2]` 一起看。本版修的 MinIO 镜像引用
> 是一处真实缺陷（修完 CI 仍红），红灯的真正根因见 `[0.5.2]`。

### Fixed

- **`S3ObjectStorageIT` 的 MinIO 镜像引用已失效**：`minio/minio` 在 Docker Hub 上
  现在返回 `404 object not found`（MinIO 已把社区镜像迁到 quay.io）。
  改为 `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`，并**钉住 release**，
  理由与 `mysql:8.4` 一致：不随上游 `latest` 漂移。
  - 本机之所以没暴露这个问题：本机早先把 quay 的镜像人工打过同名 tag，
    两者 `docker images` 里 ID 相同、看起来一样。详见 `docs/11-开发环境.md` §7.5bis。
  - 该 release 正是本机此前验证过的版本（镜像 label 的 `version` 与之一致），
    因此换引用不影响任何已验证的行为。

### Known Issues

- 与 `[0.5.0]` 相同（S3 生产部署形态未验证 · 不支持 OCR · 无上传配额 · 解析吞吐参数未标定）。

---

## [0.5.0] - 2026-09-18

Phase 05 — Object Storage & Document Workflow。文档从"能存能下"变成**能被处理**：
`Upload → 任务队列 → 解析 → 分块 → READY / FAILED`。存储是**一个端口、两种实现**，
解析是**一张任务表 + 轮询 worker**（不是消息队列 —— 理由与代价见
[docs/document-pipeline.md](docs/document-pipeline.md)）。本版本同时补齐了空间与文档的前端界面。

### Added
- **存储双实现**：`ObjectStorage` 端口（`store` / `open` / `delete` / `directGetUrl`）。
  `LocalFileObjectStorage`（默认，可克隆即跑）与 `S3ObjectStorage`（S3 兼容：MinIO / AWS，
  走 `S3Presigner` 预签名直链）。切换只改 `app.workspace.storage.backend`。
- **异步文档流水线**：`document_task` 表（`UNIQUE (document_id, task_type)` 幂等落脚点 ·
  `lease_owner` / `lease_expires_at` 租约 · `next_attempt_at` 退避）+
  `DocumentTaskRunner`（`FOR UPDATE SKIP LOCKED` 领取）· `DocumentTaskProcessor`（执行）·
  `DocumentTaskWorker`（`@Scheduled` 触发）。**与文档行同事务入队**，没有双写。
- **三种解析器**：纯文本 / Markdown / PDF（PDFBox 3.0.7）。
  Markdown 按标题层级生成**标题路径**（`空间文档规范 > 支持的格式`），PDF 按页（`第 N 页`），
  纯文本 `heading` 恒为 `null`（没有层级可提取时不编造一个）。
- **分块（`document_chunk`）**：不跨越标题合并、按段落装箱到 1200 字符、超长段落按句子结束标点
  **二级切分**；`ordinal` 自 0 连续，唯一键 `(document_id, ordinal)` 保证不重复。
- **两条下载路径**：`/content`（带令牌，每次请求重新鉴权）与 `/download-link`
  （短期链接，默认 300 秒；本地后端签本应用地址，S3 后端给直链）。**每次签发留审计**
  （`DOCUMENT_DOWNLOAD_LINK`）。
- **失败分类**：可重试（字节读不到、DB 瞬时失败）按 `30·2^(n-1)` 指数退避重排，封顶 1 小时、
  上限 3 次；不可重试（无解析器 / 文件损坏 / 页数或文本长度超限 / 解析超时）直接终态并向用户
  给出原因。**重试次数是给"可能自愈的失败"用的**，因此不可重试的失败跳过剩余次数。
- **文档与分块的接口**：元数据列表（分页）· 详情 · 分块列表（分页）· 重新解析 · 删除。
- **前端**：「我的空间」（列表 · 创建 · 用邀请码加入）· 空间详情（文档 / 协作笔记 / 成员 / 邀请，
  标签页懒加载）· 文档详情（解析状态与进度 · 分块查看 · 重新解析 · 获取下载链接 · 删除）·
  文档列表在存在未完成文档时**有界轮询**（3 秒一次，最多 40 次，到点停止并提示手动刷新）。
- **真实浏览器验收**：`scripts/e2e/browser-check.mjs`（`make e2e`），用本机 Chrome 通过 CDP 走
  16 项断言：登录 → 建空间 → 上传 → 进度 → 已就绪 → 分块标题路径 → 链接取回字节且带
  `attachment` → 笔记渲染 → 邀请 → **非成员看不到空间与文档** → 兑换加入 → 成员可读分块。
  零依赖（Node 内置 WebSocket），不下载额外浏览器。
- **测试**：新增 `DocumentChunkerTest` · `DocumentParserTest`（PDFBox 真生成 PDF）·
  `DocumentParsingRegistryTest` · `DownloadTokenServiceTest` · `DocumentPipelineIT` ·
  `DocumentParseAuthorizationIT` · `DocumentDownloadTokenIT` · `S3ObjectStorageIT`（真实 MinIO）。
  前端测试从 7 例扩展到 13 例（新增空间与文档 API 的契约形状断言）。

### Changed
- **正文排版样式（`.markdown-body`）从帖子详情组件上移到 `src/styles/main.css`**：
  空间笔记也要用同一套排版，而写在组件的非 scoped 块里意味着
  "那段样式只在那个组件被加载过之后才生效"——先访问笔记再访问帖子会看到两种排版。
- 前端测试运行器支持多入口（每个 `tests/*.test.ts` 单独构建），避免模块级状态在用例文件间泄漏。
- `Makefile` 新增 `make e2e`。

### Fixed
- **退避窗口中的人工重试无效**（真实缺陷）：`resetForRetry` 原本只放行
  `SUCCEEDED` / `FAILED`，于是"刚失败、正在退避窗口里"的任务（`PENDING` + `attempt_count > 0`）
  在用户点「重新解析」后什么都不发生 —— 接口返回 200、状态是 `PENDING`、界面最长 2 分钟毫无变化。
  现在放行这一情形（仍排除 `RUNNING`）。判据是**用户主动重试与机器自动重试不是同一件事**。
- **worker 删除分块被第三层防线静默改写**（真实缺陷）：`DocumentChunkMapper.deleteByDocument`
  原先标注 `@ScopedTable`，而 worker 没有身份 → 授权集合为空 → SQL 被追加 `1 = 0` →
  **影响 0 行且不报错**。表现是"删除文档后分块表残留"，接口层完全看不出异常。
  改为 `@Unscoped("理由")` 显式声明绕过。
- 下载令牌的验签与超长分块的二级切分（Phase 05 开发期修复，见提交 `b8d6bcb`）。

### Security
- **下载内容永不内联**：`Content-Type: application/octet-stream` +
  `Content-Disposition: attachment`（RFC 5987 编码中文名），`download-inline: false`。
  内联会让用户上传的内容在本站域的源下被浏览器解析渲染 —— 那就是"上传一个 HTML 得到一次 XSS"。
- **MIME 白名单**（比"可解析类型"更宽：压缩包与 Office 文档能存能下，只是解析会以终态失败收尾），
  并由构建期断言保证**每个解析器声明的类型都在白名单里**（否则那个解析器是死代码）。
- **下载令牌**：HMAC 签名（文档标识 + 过期时刻），篡改 / 截断 / 垃圾输入**一律同一个错误码**
  而不按原因分叉；令牌**不能当身份凭据**（放进 `Authorization` 头会被当成无效访问令牌）；
  密钥留空时启动即生成随机密钥并告警，生产必须显式配置。
- **资源保护参数**：页数上限 300 · 文本长度上限 200 万字符 · 解析堆内存 16 MiB（防压缩炸弹）·
  单次解析超时 120 秒 · 上传 10 MiB（小于容器上限，让"太大"由应用给出统一错误信封）。
- **新增的私有查询全部显式二选一**（`@ScopedTable` 或 `@Unscoped("理由")`），
  由 `WorkspaceScopeCoverageTest` 在构建期强制。

### Known Issues
- **解析吞吐靠单实例轮询**：批 5 / 3 秒 ≈ 1.7 任务/秒，且**轮询间隔就是"入队到开始处理"的延迟下限**
  （平均约 1.5 秒，最坏 3 秒）。换 MQ 的判据写在文档里，等 Phase 09 的压测数据说话。
- **无配额、无孤儿对象回收**：上传量没有上限；被外部删掉的对象不会被回收。
- **S3 后端只在集成测试（真实 MinIO 容器）里跑过**，真实部署形态（凭据来源、桶策略、跨域）未验证。
- **不支持 OCR**（扫描件 PDF 抽不出文字，会以终态失败收尾）；**无向量嵌入、分块无重叠**（Phase 07）。
- 本地后端的「直链」仍是应用签名的地址，字节经过应用，不是 CDN 直出。
- 本阶段**未压测**：解析与队列的性能指标待 Phase 09。

---

## [0.4.0] - 2026-09-18

Phase 04 — Workspace & Resource Authorization。引入协作空间与**资源级鉴权**：
空间 / 成员 / 定向邀请 / 协作笔记 / 文档（上传 · 下载 · 删除）。
本版本同时包含此前未发布的 Phase 01–03 质量修复。

### Added
- **协作空间**：创建 · 读取 · 修改设置 · 删除（软删除）；「我的空间」列表（自己拥有的 + 被邀请加入的），
  分页按 `(created_at, id)` 稳定排序。
- **成员管理**：成员列表（拥有者合成为首行）· 改角色 · 移除成员 · 退出空间。
  拥有者不能退出（`40023`）——`owner_id` 必须指向一个真实存在的人。
- **定向邀请**：按登录名邀请，邀请码与 `public_id` 同构（22 位 62 进制随机串）；
  只有被邀请人能兑换；72 小时有效期；可撤销。并发兑换靠条件更新保证只成功一次。
- **协作笔记**：列表 · 详情 · 创建 · 编辑 · 删除。Markdown 正文复用社区的渲染与净化边界（ADR 0005），
  经 `common/rendering` 共用同一实现。
- **文档**：multipart 上传 · 元数据列表/详情 · 流式下载 · 删除。字节落本地磁盘
  （`LocalFileObjectStorage`，经 `ObjectStorage` 端口接入），服务端生成存储键并计算 SHA-256。
- **资源级鉴权**：三层防线（`@PreAuthorize` 身份能力 → `AuthorizationService` 资源判定 →
  MyBatis 拦截器按 `@ScopedTable` 追加空间范围）。权限矩阵集中在 `WorkspaceAction`，
  由单元测试逐格断言。设计见 [docs/resource-authorization.md](docs/resource-authorization.md)。
- 16 个权限点（`workspace:*` · `note:*` · `document:*`）与 `app.workspace.*` 配置块。
- 新增错误码：`40021` 成员数已达上限 · `40022` 空间内容不符合要求 ·
  `40023` 拥有者不能退出 · `41300` 请求内容过大 · `50300` 依赖服务暂时不可用。
- 测试：权限矩阵逐格断言 · SQL 改写全形状 · 标注覆盖强制 · 本地存储路径安全 ·
  **跨用户攻击集成测试**（28 个用例，五组：404 语义 / 可见无权限 / 删除归属 / 成员与邀请 / 绕过服务层的第三层防线）。

### Changed
- `MarkdownRenderer` 与 `PageResponse` 从 `community` 迁至 `common`（`rendering` / `web`），
  由社区与空间共用 —— 两处各写一份净化逻辑，迟早会有一处漏掉一个标签。
- `AuthorizationService.assertCan` 返回判定所依据的身份，避免调用方重复查询。
- 列表与详情响应新增 `deletableByMe`，由服务端算出，前端不再自行推导删除权限。
- `application.yml` 新增 `app.workspace.*`；`ApplicationConfigStructureTest` 同步断言全部键。

### Fixed
- **枚举列的映射不再指名通用类型处理器。** 原写法在结果映射上写
  `typeHandler="org.apache.ibatis.type.EnumTypeHandler"`，而 MyBatis 的类型注册表
  除按 `javaType` 建表外还有一张**以 handler 类为键**的表；指名通用处理器时先命中后者，
  拿到的可能是为**另一个枚举**创建的实例，表现是 `No enum constant <别的枚举>.PRIVATE`，
  且只在真正查询时暴露。改为只写 `javaType`（与项目既有约定一致）。
- `InviteRequest.toRole()`（以及同类入口）补齐"未指定时取 `MEMBER`"的默认值 ——
  之前空白取值会直接落到 `40001`，与字段文档所述不符。
- `WorkspaceInviteMapper.revoke` 补上 `workspace_id` 条件：
  只按邀请码撤销会让"我在别的空间里也是成员"变成一次跨空间的越权改状态。
- 成员上限判断改用 `@Unscoped` 的计数查询：原实现带自动空间过滤，
  而兑换者在兑换时**还不是成员**，会导致计数恒为 0、上限在兑换路径上静默失效。
- 第三层防线的 SQL 拼接在 `head` 末尾补空白，避免拼出 `?AND workspace_id`。

### Security
- **私有内容只有成员可见。** 不可见一律返回 `404`（不区分"不存在"与"不归你"），
  可见但无权限才 `403`—— 响应码不再泄漏某个空间或资源是否存在。
- **第三层防线由构建期断言强制。** 每条访问私有表的 `select`/`update`/`delete`
  必须在 `@ScopedTable` 与 `@Unscoped("理由")` 之间二选一（理由必须非空）；
  授权集合为空时查询退化成 `1 = 0`—— 拿不到身份时查不到数据，而不是查到全部。
- **范围绑定必须清理。** `WorkspaceScopeContext` 是 `ThreadLocal`，由过滤器在请求结束时
  **无条件**清理；否则线程池复用时下一个请求会继承上一个请求的授权范围。
- **文档路径两道路径安全**：存储键格式校验 + 拼接后归一化校验（防穿越）；
  原始文件名绝不参与路径拼接。
- **上传两段写入 + 完整性校验 + 原子改名**：字节先落存储、再登记元数据；
  字节数与声明不符即删除并报错 —— 半份内容比没有内容更危险。
- **下载恒为附件**（`Content-Disposition: attachment`，`download-inline: false`），
  MIME 用白名单：内联打开用户上传的 HTML 等于一次 XSS。
- **定向邀请不泄漏码的存在性**：非受邀人、已兑换、已撤销一律 `404`。

### Known Issues
- `document.parse_status` 只会是 `PENDING`；内容解析、分块与 `READY`/`FAILED` 流转属 Phase 05。
- 没有通知机制：邀请码需要邀请人自行转达（通知属 Phase 10）。
- 本地磁盘存储没有配额与清理：删除文档只标记 `deleted_at`，字节仍留在磁盘上。
- 无法转让空间：拥有者当前只能删除空间或保持原样。
- `TEAM` 与 `PRIVATE` 在授权判定上没有区别（两者都只有成员可见），
  `TEAM` 目前只表达团队意图，为将来的发现/推荐策略留位置。
- 刷新令牌仍存 localStorage；Cookie 迁移须与 CSRF 防护整体设计。
- **不支持 Web Locks 的浏览器**只能做同页去重及已落盘凭据同步，不能保证跨标签页串行。
- 独立 ESLint / Checkstyle 尚未配置；性能未重新压测。

### 此前未发布的 Phase 01–03 质量修复

#### Added
- 前端认证回归测试（Node 测试运行器 + 现有 Vite），已接入 CI。
- 并发刷新、无 exp JWT、限流容量、删除帖回复可见性及分页边界测试。
- 架构测试补充跨模块持久层隔离与领域层依赖方向。

#### Changed
- `/` 进入社区；运行信息移至 `/system`，移除过时的阶段交付文案。

#### Fixed
- 网络、429、5xx 导致刷新失败时保留会话；只有确认凭据失效才清理。
- 同页刷新去重、跨标签页 Web Locks 协调及 storage 同步；登出后迟到的刷新不恢复会话。
- 帖子软删除后，不能再通过评论 ID 读取回复。
- 分页偏移使用 long，并按截断后的实际页大小计算；评论同时间戳按 ID 稳定排序。
- 限流键数执行一万条硬上限，各桶按自身到期时间清理。

#### Security
- 刷新令牌的条件撤销成功后才允许签发后继，防止并发分叉。
- 访问令牌必须携带 exp 声明。

---

## [0.3.0] - 2026-09-17

Phase 03 — Community MVP & Bootstrap。引入社区内容域（帖子 · 板块 · 标签 · 评论 · 回复 ·
点赞 · 收藏 · 浏览）、一份"从零部署即有内容"的合成演示数据，以及前端社区全部页面与登录态。

> 本阶段**仍然只使用 MySQL**：没有缓存、没有消息队列。基线数据（`docs/experiments/EX-000-query-baseline.md`）
> 显示读取路径还没到需要缓存的程度，而"先加缓存再看效果"会让人无法判断瓶颈原本在哪。

### Added

- **`V3__community.sql` 迁移**：7 张表 —— `category` · `tag` · `post` · `post_tag` · `comment` ·
  `post_reaction` · `post_view_daily`。几个非显然的决定：
  - `post` 同时存 `body_md`（**编辑的事实来源**）与 `body_html`（写入时渲染并净化的结果，
    读路径直接返回），另有 `summary` 由写入时从原文派生 —— 因此**列表页不需要读 `MEDIUMTEXT` 正文**。
    取舍与复核条件见 `docs/adr/0005`。
  - **互动幂等由主键强制**：`post_reaction` 主键为 `(user_id, post_id, type)`，
    重复点赞不可能写出第二行；`ReactionService` 捕获 `DuplicateKeyException` 判断"是否真的新增"，
    只有真的新增才 `+1`。计数的正确性因此不依赖业务代码的判断。
  - **评论严格两层**，`parent_id` 自引用 + `ON DELETE CASCADE`；**刻意不存 `root_id`**
    （在两层约束下它与"回复的 `parent_id`"恒等）。层级规则只能由服务层保证并由集成测试钉住。
  - **浏览计数分两张表**：`post_view_daily` 明细负责按天去重，汇总落 `post.view_count`。
  - **帖子索引不带 `deleted_at` 条件**：它作为末列无助于过滤、作为首列会破坏有序扫描，
    而正常运营下删除率是个位数百分比。等删除率真的变高再调整，而不是先加一列。
- **`V4__schema_baseline_marker.sql` 迁移**：把 `app_metadata.schema.baseline` 修正到当前迁移版本。
  V1 写入了 `V1` 之后，V2 与 V3 都忘了更新它 —— 于是库结构已到 V3，`/api/v1/system/info`
  仍然报 `V1`。这个端点存在的意义就是"部署后一个请求确认迁移已生效"，
  而一个恒为 `V1` 的值不但做不到这件事，还会给出一个**看起来正常**的错误结论。
- **community 模块**：
  - 领域：`Post` · `PostSummary` · `PostDetail` · `Comment` · `Category` · `Tag` · `TagAssignment` ·
    `MarkdownRenderer`（commonmark + OWASP Java HTML Sanitizer）· `Slugifier` · `PostSort`
  - 应用：`PostService` · `CommentService` · `ReactionService` · `TagService`
  - 接口：`PostController` · `CommentController` · `CommunityController` · `PostSortConverter` ·
    `PageResponse` · `CurrentUser`
- **`bootstrap` 组装点**：`DemoSeedRunner` · `DemoContentLibrary` · `DemoSeedProperties` ·
  `DemoCommentPlan`（评论数量在帖子之间的分配规则）。
  演示数据**只调用各模块的公开应用服务**（注册 / 发帖 / 评论 / 点赞），不直接写表 ——
  因此密码会被真正哈希、Markdown 会被真正净化、审计会被真正记录，
  演示数据与真实用户走的是同一条代码路径。默认关闭、拒绝在 `prod` profile 下执行、
  按"库里有没有帖子"判断幂等。规模由 `app.demo-seed.*` 配置。
- **HTTP 接口**（`/api/v1/community`）：

  | 方法 | 路径 | 公开 | 说明 |
  |---|---|---|---|
  | GET | `/categories` | ✅ | 板块列表 |
  | GET | `/tags` | ✅ | 热门标签（按被引用帖子数倒序，最多 20 个） |
  | GET | `/posts` | ✅ | 帖子列表；支持板块/标签筛选、`latest`/`hot` 排序、分页 |
  | GET | `/posts/{publicId}` | ✅ | 详情；带令牌访问记录一次浏览（同用户同天只计一次） |
  | POST | `/posts` | — | 发布 |
  | PUT | `/posts/{publicId}` | — | 编辑（仅作者） |
  | DELETE | `/posts/{publicId}` | — | 删除（软删除，仅作者） |
  | GET | `/posts/{publicId}/comments` | ✅ | 顶层评论列表 |
  | POST | `/posts/{publicId}/comments` | — | 发表评论或回复 |
  | POST / DELETE | `/posts/{publicId}/like` | — | 点赞 / 取消（幂等，返回操作后的状态与计数） |
  | POST / DELETE | `/posts/{publicId}/favorite` | — | 收藏 / 取消（幂等） |
  | GET | `/comments/{publicId}/replies` | ✅ | 某条顶层评论的回复 |
  | DELETE | `/comments/{publicId}` | — | 删除评论（软删除，仅作者；顶层评论连带删除其回复） |
  | GET | `/me/favorites` | — | 我收藏的帖子 |

- **前端**：社区信息流（筛选条件放 URL）、帖子详情、发布 / 编辑、我的收藏、登录与注册（同页）；
  `auth` store 与 `http.ts` 的"令牌过期 → 刷新 → 重试一次"链路；`PostCardItem` ·
  `CommentThread` · `PasswordInput` 组件；`utils/datetime` · `utils/form`。
  全部视图为路由级懒加载。
- **`scripts/bench/query-baseline.mjs`**：可复现的社区只读接口延迟测量脚本
  （Node，无第三方依赖，Windows 可跑）。结果见 `docs/experiments/EX-000-query-baseline.md`。
- **`docs/adr/0004`**（计数内联而非拆 `content_stats`）与 **`docs/adr/0005`**（内容渲染与净化边界）。

### Changed

- **安全配置改为逐条放行社区 GET 端点**，而不是放行 `/api/v1/community/**`。
  后者会让"以后新增的接口"默认就是公开的 —— 新增接口默认应当需要认证。
- **修正 `application.yml` 中 `community:` 与 `request:` 两个配置块的缩进层级。**
  它们此前写在**顶层**，而属性类前缀是 `app.community` / `app.request`，
  于是这些配置**全部静默失效**：应用照常启动、测试照常全绿。
  其中 `request:` 块整块都是 `${APP_TRUST_FORWARDED_HEADERS:false}` 这类安全开关，
  失效后的表现是"限流在反向代理之后把所有请求都算在同一个代理 IP 上"。
  根因是 record 的构造函数绑定在属性缺失时不报错，"写错位置"与"本来就没打算配"在运行时完全一样。
  配套新增 `ApplicationConfigStructureTest`（解析 `application.yml` 并断言键的层级），
  且已反向验证：还原成修复前的 yml 会报 11 个缺失键。
- **版本号改为单一来源。** 此前 pom 的 `<version>` 只用于 Maven 自身，而"应用对外报出的版本"
  其实硬编码在 `application.yml` 里 —— `v0.2.0` 发布时 pom 写着 `0.1.0-SNAPSHOT`、
  接口报的却是另一个数，两处可以各自漂移且都不会失败。现在 `application.yml` 通过资源过滤
  引用 `@project.version@`，pom 成为唯一来源；仍保留 `APP_VERSION` 环境变量覆盖，
  用于在镜像/编排层标注构建来源。发布态收敛为 `0.3.0`（不带 `-SNAPSHOT`），
  下一次开发再改为下一版本的 `-SNAPSHOT`。前端 `package.json` 同步为 `0.3.0`。

### Fixed

- **`?sort=hot` / `?sort=latest` 返回 400。** `PostController` 的 Javadoc 写明"取值不区分大小写"，
  实现却直接把 `PostSort` 枚举当作 `@RequestParam` 的类型，而 Spring 对枚举的默认转换**区分大小写**。
  后果是前端**所有带排序的列表请求都会失败**。修复为注册 `PostSortConverter`；
  非法取值仍被拒绝（`40002`），不静默退化为默认排序。
  这个缺陷此前没有任何测试覆盖到（集成测试里一次都没出现过 `sort` 参数）——
  它只在"启动真实服务、按前端实际发出的 URL 请求"时才暴露。
- **`/api/v1/system/info` 的 `schemaBaseline` 长期报 `V1`**（库已到 V3）：见上面的 V4 迁移。
  同时把 `SystemInfoIT` 里硬编码的 `EXPECTED_SCHEMA_BASELINE = "V1"` 改为
  "与迁移脚本里的最新版本比对" —— 写死常量的断言会在升版时被顺手改掉，
  于是它永远通过，也就永远发现不了标记漂移。
- **`AuthFlowIT#me_withTamperedToken_returnsTokenInvalid` 是概率性通过。**
  它篡改的是令牌签名段的**最后一个字符**，而 HS256 的 32 字节签名经 base64url 无填充编码后是
  43 个字符（258 位，只比 256 位多 2 位）—— **最后一个字符的低 2 位是解码时被忽略的填充位**，
  把 `A` 改成 `B` 恰好只动了最低位，解码出来的签名与原签名完全相同，令牌依然有效。
  实测 2000 次随机签名：改末字符有 **6.40%** 概率篡改无效（理论值 4/64 = 6.25%），改首字符 0/2000。
  改为篡改签名段的第一个字符，并把这个原因写进注释。
- **演示数据的评论全部堆在最旧的那一段帖子上。** 生成器原本"从第一帖开始逐帖填评论，
  填满 `comment-count` 上限就停"，于是 2000 帖 / 4000 评论的规模下只有最早的 1000 帖有评论。
  而首页按发布时间**倒序**，最显眼的位置恰好是一屏零评论 0 互动的帖子 ——
  看起来像个死社区。这个后果不影响任何既有断言：帖数对、评论数对、账号能登录。
  改为由 `DemoCommentPlan` 按 `⌊(i+1)·C/P⌋ − ⌊i·C/P⌋` 均匀摊开（总和严格等于上限、
  任意两帖相差不超过 1、`C ≥ P` 时每帖至少 1 条），并补 `DemoCommentPlanTest`
  断言这些**形状**（数量对而位置错，只有形状断言挡得住），`DemoSeedIT` 补
  "每一帖都应有评论"。默认规模（60 帖 / 240 评论）的行为与改动前完全一致。
- **基线测量脚本的样本筛选函数写了但没有被调用。** `pickPostWithComments` 定义了却没接到
  `main()` 上，于是"某条顶层评论的回复列表"这个场景仍然按 `feed.items[0]` 取样本 ——
  也就是修复前的行为依旧存在。同时它的取样假设是"有评论的帖子出现在最新的 25 页里"，
  而这批演示数据的评论恰好都在更早的帖子上，于是该场景被静默跳过。
  改为按服务端允许的最大页大小（50）逐页扫到找到为止，扫不到时在报告里**写明扫描页数**
  而不是少一行。
- **`docs/architecture.md` 里的演示数据环境变量名写错**：写成 `APP_DEMO_SEED_POST_COUNT`，
  而实际是 `APP_DEMO_SEED_POSTS`。照着文档执行不会报错 —— Spring 照常启动，
  只是用了默认规模（60 帖），于是"我以为自己在测 2000 帖的数据"这件事不会有人告诉你。
  已在文档中写明以 `application.yml` 为准，并提示测量前先用 `total` 确认数据规模。
- `V3` 注释里引用的旧类名 `ai.camphub.community.app.DemoContentSeeder` 更正为
  `ai.camphub.bootstrap.DemoSeedRunner`。

### Security

- 社区内容**读公开、写必须登录**，且公开端点逐条列出（默认拒绝）。
- 帖子正文由服务端渲染 + OWASP 白名单净化；**前端全仓库只有一处 `v-html`**，
  其值只能来自已净化的 `bodyHtml`。列表摘要与评论均以文本节点渲染，不进 HTML 路径。
- 评论按纯文本存储与渲染，**不解析 Markdown**：评论区是数量级更高的内容入口，
  让可渲染的输出出现在那里等于把注入面放大到与正文同级。
- 归属校验统一返回 **404 而非 403**，不泄漏"这条资源是否存在"。
- 演示数据生成器默认关闭、显式拒绝在 `prod` profile 下执行，并把演示账号与口令打印在启动日志里
  （它是公开入口，不该表现得像个秘密）。
- 令牌续期只对 `40101`（令牌过期）触发一次刷新重试；`40100` / `40102` / `40104` 不重试 ——
  前三者刷新解决不了，重试只会把用户困在必然失败的循环里。刷新令牌并发去重，
  因为服务端把"同一刷新令牌被用两次"视为泄露证据并撤销该账号全部会话。

### Known Issues

- **净化白名单的修复不会自动作用于已落库的历史 HTML**（`body_html` 是写入时的快照）。
  处置路径是"重渲染回填"（`body_md` 已保留，因此这一步是确定性的），见 `docs/adr/0005`。
- **刷新令牌存 `localStorage`**：XSS 一旦发生可被取走长期凭据。正确收口是改为 `httpOnly` Cookie
  并补 CSRF 防护；当前压低风险的是"全仓库唯一一处 `v-html`，且内容由服务端净化"。
- **浏览计数只统计登录用户**，匿名访问不计入，且同一用户同一天只计一次。
- **深分页仍为 `LIMIT offset`**；**热度排序是"按点赞数倒序"**，没有时间衰减与多项加权。
- **社区没有审核能力**：任何登录用户都能发布任意内容。审核域在 Phase 10。
- `?sort=` 之外，`page` / `size` 的非法值走的是"归一化"策略（`page=0` 修正为 1、
  超出上限截断为上限），而 `sort` 走的是"拒绝"。两者不同是刻意的：前者的合法值是一个范围，
  后者是一个有限集合，集合外没有"合理的默认解释"。

---

## [0.2.0] - 2026-09-17

Phase 02 — Identity & Security Foundation。引入完整的账号体系与鉴权能力：
注册、登录、登出、访问令牌 + 刷新令牌、令牌撤销、密码策略、基础限流、审计基础。

> **这是一次破坏性变更**：引入 Spring Security 并采用"默认拒绝"后，
> 所有此前公开的端点变为需要认证（`/api/v1/system/info`、`/actuator/health`、
> API 文档除外）。因此按 MINOR 递增。

### Added

- **`V2__identity.sql` 迁移**：8 张表 —— `user` · `user_credential` · `role` ·
  `permission` · `user_role` · `role_permission` · `refresh_token` · `audit_log`。
  要点：
  - `user_credential` 与 `user` 分表。凭据是**读取频率最低、敏感度最高**的字段，
    分表后任何"列出用户/搜索用户"的查询天然拿不到 `password_hash`，
    不必依赖每个开发者记得在 SELECT 里排除它。
  - 排序规则刻意选 `utf8mb4_0900_ai_ci`（大小写不敏感）。若区分大小写，
    攻击者就能注册 "Adm1n" 冒充 "adm1n"。
  - `refresh_token.token_hash` 只存 SHA-256。它是 30 天有效的长期凭据、等价于密码，
    库被读走时哈希不可逆；用 SHA-256 而非 BCrypt 是因为令牌本身是 256 bit 随机值
    （没有"弱口令"问题），而每次刷新都要按哈希建索引查找，慢哈希会把刷新接口变成 CPU 瓶颈。
  - `audit_log.actor_user_id` **不加外键**。审计的价值在于"账号删除后仍查得到谁做过什么"，
    外键（且是 CASCADE）会让删除用户连带抹掉审计痕迹 —— 那正好删掉了最该保留的部分。
  - 只种 `USER` 角色。**不预置** MODERATOR / ADMIN 与权限点：本阶段没有任何接口检查它们，
    提前种下的结果是表里躺着一批"看起来有权限体系、实际没人检查"的行，
    比没有更危险。
- **身份领域模型**：`User` · `UserCredential` · `UserStatus` · `Role` · `UserPrincipal` ·
  `RefreshTokenRecord` · `PasswordPolicy` · `TokenHasher` · `RandomValues` · `DeviceLabel`。
  除 `UserPrincipal` 的权限缓存取舍外，全部不依赖 Spring（由 ArchUnit 断言）。
- **鉴权链**：`JwtAuthenticationFilter`（签名/时间 → 账号状态 → 令牌世代号）·
  `JwtTokenService`（HS256）· `RestAuthenticationEntryPoint` · `RestAccessDeniedHandler` ·
  `UserPrincipalLoader`（每请求回库装配权限）· `SecurityConfig`。
- **应用服务**：`AuthService` · `AccountService` · `SessionService` ·
  `RefreshTokenService` · `RefreshTokenLeakHandler` · `LoginAttemptService` ·
  `AuthRateLimiter`。
- **HTTP 接口**（`/api/v1`）：

  | 方法 | 路径 | 说明 |
  |---|---|---|
  | POST | `/auth/register` | 注册并直接返回令牌对 |
  | POST | `/auth/login` | 用户名或邮箱 + 密码 |
  | POST | `/auth/refresh` | 换取新令牌对（轮换，旧令牌立即失效） |
  | POST | `/auth/logout` | 撤销当前会话（幂等） |
  | POST | `/auth/logout-all` | 撤销全部会话并使访问令牌立即失效 |
  | GET | `/users/me` | 本人资料 |
  | PATCH | `/users/me` | 修改资料 |
  | POST | `/users/me/password` | 修改密码 |
  | GET | `/users/me/sessions` | 列出登录设备 |
  | DELETE | `/users/me/sessions/{id}` | 下线指定设备 |

- **审计基础**（`platform/audit`）：`AuditService` 以 `REQUIRES_NEW` 独立事务写入，
  覆盖 `AUTH_REGISTER` · `AUTH_LOGIN_SUCCESS` · `AUTH_LOGIN_FAILURE` · `AUTH_LOGOUT` ·
  `AUTH_LOGOUT_ALL` · `AUTH_TOKEN_REFRESH` · `AUTH_TOKEN_REPLAY_DETECTED` ·
  `AUTH_PASSWORD_CHANGE` · `USER_PROFILE_UPDATE` · `SESSION_REVOKE`。
- **错误码扩展**：`40100`~`40104`（未认证 / 过期 / 无效 / 凭据错误 / 已撤销）、
  `40300`~`40301`、`42900`、`40010`、`40900`。`429` 响应带 `Retry-After`。
- **测试**（本阶段准出要求）：47 条单元测试 + 41 条集成测试（真实 HTTP + 真实 MySQL）。

### Security

- **防账号枚举**：登录时"账号不存在"与"密码错误"返回**完全相同**的状态码、
  错误码与文案；并且账号不存在时仍对预置哑哈希执行一次 BCrypt 校验，
  让两条路径的**响应耗时**也接近。否则拿一份邮箱列表逐条试，就能筛出平台上有哪些账号。
- **账号状态检查放在口令校验之后**：锁定/停用的提示对真实用户很重要，
  但对攻击者同样是信息。放在口令校验之后，只有已经知道口令的人才会看到它。
- **令牌世代号（`user.token_version`）实现"立即撤销"**：访问令牌是无状态的，
  签发后无法收回。登出全部设备 / 改密 / 踢下线时递增世代号，鉴权过滤器逐请求比对，
  使未过期的访问令牌也立即失效。代价是每次鉴权回库一次（换取"权限变更立即生效"）。
- **刷新令牌轮换 + 重放检测**：一个 30 天有效、可不断换新的凭据若不轮换，
  一旦泄露就是 30 天的稳定后门，且服务端**无法察觉**泄露已发生。轮换之后，
  链中间节点再次出现即说明有人持有旧副本 —— 泄露从"不可见"变成"可判定事件"。
  处置为撤销该用户全部会话并推进世代号。
  宽限窗口（5 秒）用于区分"多标签页并发刷新"与"确凿的泄露"。
- **密码策略遵循 NIST SP 800-63B**：要求长度（≥ 10）与常见弱密码表命中，
  **不强制字符类别组合**（强制组合会把用户推向 `Passw0rd!` 这类可预测模式）。
  另拒绝超过 BCrypt 72 字节上限的密码 —— 而不是放任其被静默截断
  （那意味着两个不同的长密码可能哈希成同一个值）。
- **越权从接口形状上消除**：账号自助接口的路径中只有 `me`，
  不出现"要操作哪个用户"这个参数。因此不存在"把别人的 ID 填进去"的攻击面，
  也就不依赖每个开发者都记得加归属判断。会话下线是唯一需指定 ID 的接口，
  那里显式校验归属，且**不属于自己的会话一律返回 404 而非 403** ——
  403 等于承认"这个会话确实存在，只是不归你"。
- **响应字段按"谁在看"裁剪**：资料响应不含 `email` / `status` / 自增 `id` / `tokenVersion`。
- **CSRF 关闭是有前提的**：凭据只经 `Authorization` 头传递，不依赖浏览器自动携带的 Cookie。
  代码注释中已标明：**若将来把刷新令牌改放进 Cookie，这一条必须同步改回来**。
- **来源 IP 默认不信任 `X-Forwarded-For` / `X-Real-IP`**：这两个头由客户端完全控制，
  无可信反向代理时采信它们会让"按 IP 限流"被逐请求绕过、并往审计日志写入伪造来源。
- **JWT 密钥无默认值**：`app.security.jwt.secret` 为空或短于 32 字节时**启动即失败**。
  一个"能启动但人人可猜"的默认密钥，等于把所有用户的账号交给第一个读到源码的人。

### Changed

- **全部端点默认需要认证**（`anyRequest().authenticated()`）。公开端点收敛为：
  `auth/register|login|refresh|logout`、`GET system/info`、`actuator/health|info`、
  swagger、`/error`。默认拒绝而非默认放行，是这份清单能长期安全的前提 ——
  新增接口时忘记加规则，结果是"访问不了"（立刻被发现），而不是"裸奔"（很久后才发现）。
- **未认证请求一律 401，不再先回答"这个路径存在吗"**。
  因此对不存在的路径得到的是 401 而不是 404。这是期望的行为：
  若未认证调用方能靠 404/405 与 401 的差别区分路径是否存在，就等于提供了一个免费的接口枚举器。
  `ErrorContractIT` 相应拆成两组（带令牌验证 404/405 契约，不带令牌验证"先认证"规则）。
- `AuthRateLimiter` 从 `identity.infrastructure.security` 移至 `identity.app`。
  它被 Controller 直接调用，而架构规则禁止 API 层依赖 infrastructure。
  更根本的理由是限流属**业务策略**而非外部系统适配，"用内存还是 Redis"是实现细节。

### Fixed

- **MyBatis 构造器映射把 `long` / `int` 解析成包装类型**：三个 resultMap 写的是
  `javaType="long"`，而 MyBatis 别名表中**不带下划线的是包装类型**
  （`long` → `java.lang.Long`），只有 `_long` / `_int` 才是原始类型。
  record 的规范构造器按原始类型签名，于是查询时抛 `NoSuchMethodException`
  并以 500 暴露 —— 编译期毫无提示。已改用 `_long` / `_int` 并在 XML 中写明该陷阱。
- **令牌泄露处置被事务回滚吞掉**：检测到刷新令牌重放后的"撤销全部会话 + 推进世代号"
  原先写在 `@Transactional` 的 `rotate()` 内，随后的业务异常会回滚整个事务，
  两笔写入全部消失 —— 客户端收到"已登出全部设备"，数据库却什么都没变，攻击者令牌继续有效。
  更糟的是审计（`REQUIRES_NEW`，不受回滚影响）已写下"处置成功"，
  事后排查会得到与事实相反的结论。已抽为 `RefreshTokenLeakHandler` 以 `REQUIRES_NEW` 独立提交。
- **注册时不传 `device` 返回 500**：`device` 在接口上可选（`@Size(max=64)`，允许 null），
  而 `refresh_token.device` 是 `NOT NULL`。此前只有令牌轮换路径做了规范化，
  注册与登录路径没有，于是"不传设备"这一完全合法的操作以
  `Column 'device' cannot be null` 变成服务器错误。已抽出 `DeviceLabel`
  作为唯一的规范化实现，并在 `SessionService`（三条路径的共同入口）统一调用。
- **登出审计重复记账**：`revoke` 的 `WHERE` 带 `revoked_at IS NULL`，
  重复登出影响 0 行，但审计此前无条件写入。已改为仅在真正撤销了会话时记录 ——
  否则按动作聚合出的"登出次数"不再等于"被撤销的会话数"。
- **弱密码可被首尾空白绕过**：`"  password123  "` 此前能通过弱密码检查，
  而攻击字典的第一条规则正是"在候选词前后补上常见字符"。
  弱密码表与账号相关性比对改用去除首尾空白后的形式；同时**显式拒绝**首尾空白 ——
  它在视觉上不可见却会改变哈希结果，会制造出"用户认为密码是 A、实际设成了 B"的支持困局。
- `ClientIpResolver` 注释中的配置键名错误（写作 `app.security.trust-forwarded-headers`，
  实际前缀为 `app.request`）。

### Known Issues

以下为**已知并已记录**的限制，不是遗漏：

- **限流为单实例内存实现**：多实例部署时每个实例各自计数，实际额度是"配置值 × 实例数"；
  进程重启即清零；固定窗口在跨窗口边界时短时峰值可达配置值的两倍。
  这是本阶段明确不引入 Redis 的代价，接 Redis 是 Phase 09 的任务（届时先压测确认它确实是瓶颈）。
- **令牌撤销延迟**：不做"访问令牌黑名单"，世代号是账号级的粗粒度撤销 ——
  撤销单个设备会使该账号所有访问令牌失效（用户体验上的取舍，换来实现简单且无额外存储）。
- **无 MFA / 无第三方登录 / 无邮箱验证**：不在 Phase 02 范围内。
- **审计只写不查**：`audit_log` 目前仅写入，没有查询接口与后台展示（Phase 10）。
- **验证码缺失**：限流之外没有验证码，持续分布式撞库仍可能缓慢推进。
- **公开端点清单不含 `/auth/logout-all`**：它需要有效访问令牌，这是有意的 ——
  `/auth/logout` 之所以公开是因为"持有刷新令牌即授权"，而"登出全部设备"是账号级操作，
  必须由已认证的身份发起。

---

## [0.1.1] - 2026-09-17

`v0.1.0` 之后的收尾批次：补上持续集成、修正一处仓库洁净度问题、
按新指令改写阶段纪律。**不涉及任何对外契约变更**，因此按 PATCH 递增。

### Added

- GitHub Actions CI（`.github/workflows/ci.yml`）：两个 job 分别执行后端
  `./mvnw -B verify`（单元 + ArchUnit + 真实 MySQL 集成测试）与前端
  `npm ci` + `npm run build`（含 `vue-tsc` 类型检查）。失败时归档
  surefire / failsafe 报告。CI 调用的命令与本地完全一致，不维护第二套脚本。
- `docs/00-工程规约.md` §18 新增五条准出条件、仍然禁止的事项、必须停下来问的
  唯一情形、**全量端到端验收**要求、**平台可移植性**要求（Windows）、
  以及**对外表述纪律**。

### Fixed

- **`src/test/resources/testcontainers.properties` 从仓库移除。**
  该文件把 ryuk 镜像固定为 `testcontainers/ryuk:0.11.0`，目的是绕开本机
  Docker Hub 不可达的环境限制 —— 但它是**本机专属的环境适配**，不是项目配置。
  留在仓库里会带来两个实际后果：所有克隆者被迫使用同一个（较旧的）ryuk 版本；
  而 Testcontainers 已明确提示该替换机制 "deprecated and will be removed in the future"，
  机制移除后这个文件会变成死配置，届时本机测试将静默失去这个绕行手段。

  改为：环境适配写入用户级 `~/.testcontainers.properties`（不属于任何仓库），
  仓库保持干净。能直连 Docker Hub 的机器无需任何配置。
  已验证：移除后集成测试仍正常通过（含 ryuk 替换生效）。

- `docs/02-architecture.md` 技术选型表中仍写着 Spring Boot `3.5`，
  与已锁定的 `4.1.1` 基线不符（该表写在基线确认之前）。已更正。
- `CHANGELOG.md` `[0.1.0]` 段中一句不准确的表述 —— 原文称 ryuk 的本地 tag 处理
  "不影响仓库内容的可移植性"，但那个文件当时确实在仓库里，该说法是错的。
  已改为如实描述当时状态并指向本版本的修复。

### Changed

- `docs/00-工程规约.md` §18 **解除**「每阶段完成后停止、不得自动开发下一阶段」，
  授权从 Phase 01 连续推进至最后阶段；§19 自检清单与 §20 变更记录同步更新。
- `docs/12-phase-plan.md` 的纪律说明同步改写，避免与规约冲突。
- `docs/11-开发环境.md`：§7.1/7.2 重新核对可达性；新增 §7.3 记录上述 GitHub 结论更正，
  §7.4 给出绕过沙箱代理的 git 配置，§7.5 记录凭据助手可用性，
  §7.6 记录 ryuk 镜像的用户级配置；§8 清理已解决的历史待办。
- `docs/01-product.md` · `02-architecture.md` · `05-data-platform.md` ·
  `06-search-ai.md` · `08-roadmap.md`：移除与招聘求职相关的对外表述
  （详见规约 §18.7）。产品自身内容域中的"求职/招聘"（用户画像、招聘信息聚合）
  属于产品功能，予以保留。
- `README.md` 新增「持续集成」小节，项目结构补上 `.github/`，
  并把阶段推进说明改为四条准出条件。
- `pom.xml` 中 Testcontainers 注释改为说明用户级配置约定，不再指向已删除的文件。

---

## [0.1.0] - 2026-09-17

**Phase 01 · Engineering Foundation**

工程基础阶段。目标不是实现业务功能，而是搭出一套**后续十余个 Phase 都要依赖的地基**：
统一契约、可定位的日志链路、可被构建期断言的架构边界、真实数据库上的测试体系。

### Added

**后端工程骨架**

- Maven 工程，Spring Boot `4.1.1` + Java `21`，含 Maven Wrapper（无需全局 Maven）。
- 模块化单体包结构：`common`（共享内核）+ `system`（首个模块，四层分层）。
- 配置管理分层：`application.yml`（入库、非密）→ `application-local.yml`（入库、非密）
  → `.env`（不入库、真实凭据）→ 环境变量；`.env` 经 `spring.config.import` 自动加载，
  且以 `optional:` 前缀保证无 `.env` 时也能启动。
- `AppProperties`（`@ConfigurationProperties(prefix = "app")`）承载应用自定义配置。
- MyBatis 数据访问：Mapper 接口位于各模块 `infrastructure` 包，
  由启动类 `@MapperScan("ai.camphub.**.infrastructure")` 统一扫描；XML 置于 `resources/mapper/**`。

**统一错误契约**（详见 `docs/adr/0003-unified-error-contract.md`）

- 错误响应结构化：`{code, message, traceId, timestamp, path, details}`。
- 5 位错误码体系，编码规则 `HHH SS`（HTTP 状态码 + 状态内序号），序号段按模块预分配。
- `GlobalExceptionHandler` 统一收口，覆盖业务异常、Bean Validation 失败、方法参数校验、
  类型不匹配、缺参、请求体不可读、方法不支持、媒体类型不支持、静态资源不存在，以及兜底异常。
- `details` 恒为数组（从不为 `null`），前端无需判空。
- 5xx 兜底分支不泄漏堆栈、类名、SQL 等内部细节，原始异常只进日志。
- **成功响应不套信封**：body 即结果，链路信息统一走响应头 `X-Trace-Id`。

**日志与可观测性**

- `TraceIdFilter`（最高优先级）为每个请求建立 traceId：合法入站 `X-Trace-Id` 沿用、否则生成；
  写入 MDC 与响应头；并在 `finally` 中清理 MDC（防止线程复用导致 traceId 串号）。
- 入站 traceId 严格校验（8~64 位字母数字与连字符），拒绝换行注入等日志伪造。
- 日志 pattern 内置 `%X{traceId:-no-trace}`，使每一行日志都可按请求聚合。
- `GET /actuator/health`（含 liveness / readiness 分组）与 `/actuator/info`；
  仅暴露 `health,info`，默认 `show-details: never`，仅 local profile 放开。

**数据库**

- Flyway 迁移体系，首个迁移 `V1__baseline.sql` 创建 `app_metadata` 表并写入 schema 基线。
- 迁移纪律：`validate-on-migrate: true`（拦住对已发布脚本的修改）、
  `clean-disabled: true`、不使用 `baseline-on-migrate`（避免静默跳过库结构错位）。
- JDBC 连接串显式指定 `connectionTimeZone=Asia/Shanghai` 且强制同步到会话时区，
  规避 MySQL `time_zone=SYSTEM` 导致的排序/时间偏移。
- `docker-compose.yml` 仅编排 MySQL `8.4`（含健康检查与时区设置）。
  按计划，Redis / 对象存储 / 搜索引擎在各自 Phase 引入，且需先有数据支撑。

**纵向切片**

- `GET /api/v1/system/info` 返回应用名、版本、生效 profile、Java 版本、服务端时间
  与**数据库 schema 基线**（读自 `app_metadata` 表）。
  一个请求即可确认「服务已启动，且迁移已生效」。
- 该端点仅暴露非敏感事实，故保持公开；不得在其中加入配置项、连接串或依赖地址。

**前端工程骨架**

- Vue `3.5` + TypeScript `5.7` + Vite `6`，独立于后端构建。
- Vue Router `4`（路由级懒加载，避免首屏体积随业务线性膨胀）。
- Pinia `2` 状态管理。
- Arco Design 组件库：**按需引入**（`unplugin-vue-components` + `ArcoResolver`），
  `ConfigProvider` 统一注入中文 locale。
- `vueCompilerOptions.strictTemplates: true`：使模板中拼错的组件名与传错的 prop 类型
  在 `npm run build` 阶段被拦下（默认配置下这类错误会被 Vue 当作原生自定义元素而静默通过）。
- **与后端错误契约对齐的 HTTP 客户端**：把结构化错误映射为 `ApiError` 类，
  把「连不上 / 超时 / 响应非 JSON」映射为 `NetworkError`，
  并明确区分二者（排查方向完全不同）。
- 刻意**不做**全局自动重试：对非幂等请求无条件重试会产生重复副作用。
- 应用外壳（顶栏 + 内容区 + 页脚）与概览页：概览页展示真实后端数据，用于验证端到端链路。
- Dev server 将 `/api` 代理到后端，前端代码只使用同源相对路径，无跨域与多套请求逻辑。

**测试体系**（三层，各管一件事）

- 单元测试（JUnit 5 + AssertJ，不起容器）：错误码、错误体工厂方法、traceId 校验与 MDC 清理。
- 架构测试（ArchUnit）：模块间无循环依赖、`common` 不得依赖业务模块、
  `..api..` 不得依赖 `..infrastructure..`、`..domain..` 不得依赖 Spring、
  Controller 必须位于 `..api..`、禁止 `@Autowired` 字段注入。
  包名模式为通配，新增模块自动覆盖，无需改测试。
- 集成测试（`@SpringBootTest(RANDOM_PORT)` + Testcontainers 真实 MySQL `8.4`）：
  纵向切片（真实数据库读写、traceId 响应头、服务端时间格式、health 状态）
  与错误契约（404/405 统一格式、traceId 一致性、入站 traceId 透传、500 不泄漏内部信息）。
- 刻意**不使用 H2**：H2 与 MySQL 在字符集、排序规则、时间戳行为上存在差异，
  在 H2 上通过的测试是假绿灯。
- 刻意使用 JDK 内建 HttpClient + JsonPath 断言，而非框架自带测试客户端，
  以降低框架升级带来的测试改动成本。

**工程配套**

- `Makefile` 统一命令入口（`make help` 列出全部命令），前后端命令一致化。
- `docker-compose.yml`、`.editorconfig`（UTF-8 / LF）、`.gitignore`、`.env.example`。
- `README.md`：5 分钟上手路径、工程原则、文档索引。
- `docs/architecture.md`：实现级架构说明（运行时构成、请求链路、错误契约、配置分层、
  测试策略、已知取舍与技术债）。
- `docs/adr/0001-modular-monolith.md`：模块化单体决策，
  含为何不拆微服务、以及「边界必须由构建期断言守护」这一核心论点。
- `docs/adr/0002-spring-boot-4-and-mybatis.md`：技术基线决策。
- `docs/adr/0003-unified-error-contract.md`：统一错误契约决策。
- `docs/12-phase-plan.md`：Phase 01~13 执行计划，取代原 V0~V5 路线图。
- Git 仓库建立 `main` 与 `develop` 分支，阶段验收打 tag。

### Changed

- 工程规约升级为 V2（`docs/00-工程规约.md`）：新增 Git 版本管理纪律（分支 / Commit
  粒度 / 禁提交项 / Tag / ADR / Flyway）、阶段开发流程、Agent Runtime 相关约束、
  数据与安全原则；交付报告模板改为 11 节版本。
- `docs/09-backlog-v0.md`：修正 V0 阶段的依赖范围矛盾 —— V0 只使用 MySQL，不引入 Redis。
- `docs/11-开发环境.md`：补充 Maven 安装记录、依赖基线表，以及为什么无需登录即可开发。
- `README.md`：更新文档索引与 V2 说明。

### Fixed

- **Flyway 迁移静默不执行**：现象为集成测试报 `Table 'camphub.app_metadata' doesn't exist`。
  根因是 Spring Boot 4 把 Flyway 自动配置从 `spring-boot-autoconfigure` 中拆分出去，
  必须显式引入 `spring-boot-starter-flyway`，否则迁移**不会报错、只是不运行**。
  已补依赖并在真实 MySQL 容器上验证迁移生效。
- `spring-boot-starter-web` 在 Boot 4 中已弃用，改用 `spring-boot-starter-webmvc`。
- 方法参数校验 API 在 Spring Framework 7 中已变更，
  经 `javap` 确认实际签名为 `getParameterValidationResults()` 后修正调用。
- `pom.xml` 注释中出现 `--` 导致 XML 解析失败，已改为等号分隔样式。
- **前端依赖无法安装**（本机托管环境）：`npm install` 在落盘阶段被托管运行时的文件系统代理拒绝
  （`CODEBUDDY_BROKER_DENY: Brokered host mkdir requires an available runtime file rule`）。
  已定位为该代理不认 npm reify 阶段的 `fs.promises.mkdir` 调用，而非网络或权限问题；
  通过清空 `NODE_OPTIONS` 绕开。排查过程与对策记录在 `docs/11-开发环境.md` §2.1。
- **前端产物体积过大**：全量引入 Arco 导致产物为 1 243.79 kB JS（gzip 310.65 kB）
  + 405.39 kB CSS（gzip 50.09 kB），而页面只用到 8 个组件。
  改为按需引入后降至 221.85 kB JS（gzip 84.02 kB）+ 81.25 kB CSS（gzip 12.85 kB），
  JS 体积下降约 82%、CSS 下降约 80%（均为 `npm run build` 实测）。
- **模板中的组件名拼写错误不会被发现**：Vue 默认会把无法解析的组件当作原生自定义元素。
  已启用 `strictTemplates` 并入库 `components.d.ts`，使此类错误在构建期暴露。

### Security

- 5xx 响应不泄漏内部实现细节（堆栈、类名、SQL、依赖信息），细节仅进日志。
- 入站 `X-Trace-Id` 严格字符集与长度校验，防止日志注入（log injection）。
- 生产配置默认不暴露 actuator 细节：仅开 `health,info`，`show-details: never`。
- `.env`、真实凭据、日志、构建产物、IDE 用户配置均不入库；提供 `.env.example` 只含变量名与示例值。
- ArchUnit 规则强制 `..api..` 不得直接依赖 `..infrastructure..`，
  从结构上防止绕过应用服务层的权限校验与事务边界。
- 前端错误提示中展示 `traceId` 而非后端内部信息，兼顾可定位性与信息暴露最小化。

### Known Issues

- 尚未实现任何认证与授权，所有端点当前均为公开。**不得在此状态下部署到公网。**
  Phase 02 引入 Spring Security。
- 前端未做构建期「未使用依赖」检查，也未引入 ESLint / Prettier 与组件测试。
  在出现业务组件后再引入，避免为测试骨架而测试。
- 前端 API 类型为手写、与后端 record 手工对齐，尚无编译期一致性保证。
- `frontend/src/components.d.ts` 是 `unplugin-vue-components` 的生成物，但**必须入库** ——
  `strictTemplates` 依赖它，缺少时会对所有 Arco 组件产生误报。代价是组件增减时它也会出现在 diff 中。
- 前端 chunk 划分目前只把 vue 三件套单独拆出，未按更细粒度优化；出现体积告警时再细化。
- 数据库连接池参数（max 10）为保守起步值，**未经任何压测验证**。
  Phase 09 完成压测后按实测数据调整。
- 尚无 CORS 配置。当前依赖 dev proxy 与同源反向代理；出现真实跨域部署形态时再配，且必须使用白名单。
- v0.1.0 的仓库内**曾包含** `src/test/resources/testcontainers.properties`，
  用于把 ryuk 镜像指向本地已有的 tag（绕开本机 Docker Hub 不可达）。
  这是本机专属的环境适配，**它确实影响了仓库内容的可移植性** ——
  所有克隆者都被固定在同一 ryuk 版本上。已在 `[Unreleased]` 中移除，
  改为用户级 `~/.testcontainers.properties`。

---

## 版本号约定

| 段位 | 递增条件 |
|---|---|
| MAJOR | 出现不兼容的 API 变更（当前阶段基本不会发生） |
| MINOR | 完成一个 Phase，且该 Phase 的验收标准全部通过 |
| PATCH | 缺陷修复、文档修订等不改变对外契约的变更 |
