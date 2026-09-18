# Phase 04 · Workspace & Resource Authorization —— 交付报告

| 字段 | 内容 |
|---|---|
| **阶段** | Phase 04 — Workspace & Resource Authorization |
| **版本** | `v0.4.0` |
| **日期** | 2026-09-18 |
| **上一版本** | `v0.3.0` — Phase 03 · Community MVP & Bootstrap |
| **下一阶段** | Phase 05 — Object Storage & Document Workflow |
| **模板依据** | `docs/00-工程规约.md` §17（V2 十一节）；准出条件 §18.2 |
| **设计文档** | [`docs/resource-authorization.md`](../resource-authorization.md) |

> 本阶段的目标不是"把 Workspace 建出来"，而是让**私有数据的授权在架构上无法被漏掉**。
> 一个私有资源系统真正危险的不是"某处判错了"，而是"某处根本没判" ——
> 后者用自己造的数据怎么测都正常，只会在生产上把别人的内容返回出去。
> 因此本阶段的交付重心是**三层防线**与**构建期强制**，实体与接口是它的载体。

---

## Implemented

### 领域与应用层（`ai.camphub.workspace`）

- **四层分明**：`api` / `app` / `domain` / `infrastructure`，与既有模块同构；
  边界由 ArchUnit 的 9 条通配规则自动覆盖，未新增规则。
- **领域（23 个类型）**：`Workspace` · `WorkspaceDraft` · `WorkspaceMember` ·
  `MembershipRole` · `WorkspaceInvite` · `InviteStatus` · `InviteDraft` ·
  `NoteSummary` · `NoteDetail` · `NoteDraft` · `WorkspaceDocument` · `DocumentDraft` ·
  `DocumentParseStatus` · `WorkspaceVisibility` · `WorkspaceMemberRole` ·
  `WorkspaceRoleInContext` · `WorkspaceAction` · `WorkspaceResourceType` ·
  `ScopedTable` · `Unscoped` 等。
- **应用（11 个类型）**：`WorkspaceService` · `NoteService` · `DocumentService` ·
  `AuthorizationService` · `WorkspaceAccess` · `WorkspaceSummary` · `InviteView` ·
  `NoteCardView` · `NoteDetailView` · `DocumentView` · `ObjectStorage`（端口）
  与 `WorkspaceScopeContext`。
- **接口（14 个类型）**：4 个控制器 + 请求/响应 DTO。

### 三层防线

| 层 | 实现 | 回答的问题 | 失败时 |
|---|---|---|---|
| ① 身份能力 | 各方法的 `@PreAuthorize("hasAuthority('...')")` | 这个**身份**有没有做这类事的能力？ | `40300` |
| ② 资源判定 | `AuthorizationService.assertCan` | 这个人对**这一条数据**能不能做这件事？ | `40400` / `40300` |
| ③ 数据范围 | `WorkspaceScopeInterceptor` + `@ScopedTable` | 这条 SQL 有没有被限制在他的空间范围内？ | 空集 → `1 = 0` |

- **权限矩阵集中在 `WorkspaceAction`**（13 个动作）：`allowedRoles()` ·
  `isOwnershipSensitive()` · `allows(role, ownedBySelf)`。
  它由 `WorkspaceActionTest` **逐格**断言，而不是散在各 Service 的 `if` 里。
- **第 ③ 层由构建期断言强制**（`WorkspaceScopeCoverageTest`）：每条访问私有表的
  `select` / `update` / `delete` 必须在 `@ScopedTable` 与 `@Unscoped("理由")`
  之间二选一，理由必须非空；`INSERT` 不得标注；XML 语句与接口方法一一对应。

### 功能范围

- **空间**：创建 · 读取 · 修改设置 · 删除（软删除）· 我的空间列表（自己拥有的 + 被邀请加入的）。
- **成员**：成员列表（拥有者合成为首行）· 改角色 · 移除成员 · 退出空间。
- **定向邀请**：按登录名邀请 · 待处理列表（含邀请码）· 撤销 · 兑换加入。
- **协作笔记**：列表 · 详情 · 创建 · 编辑 · 删除。
- **文档**：multipart 上传 · 元数据列表/详情 · 流式下载 · 删除。

### 文档存储（本阶段落地本地磁盘）

- `ObjectStorage` 端口 + `LocalFileObjectStorage` 适配器。**真实可用的链路**，
  不是占位实现 —— 一个上传下载都返回 503 的文档功能对使用者等于不存在。
- 路径安全两道（存储键格式校验 + 拼接后归一化校验）；原始文件名绝不参与路径拼接；
  写入先落 `.part` 再 `ATOMIC_MOVE`，并校验字节数与声明一致；同时计算 SHA-256。

### 跨模块改动（4 处）

- `MarkdownRenderer` 迁至 `common.rendering`、`PageResponse` 迁至 `common.web`
  （ADR 0005 的渲染边界现在真的被两个模块共用），装配点从 `CommunityConfig`
  变为 `common.config.RenderingConfig`。
- `ErrorCode` 新增 5 个码；`GlobalExceptionHandler` 把
  `MaxUploadSizeExceededException` 翻成 `41300`（否则落兜底 500）；
  `AuditAction` 新增空间相关动作；`UserDirectory` 新增按登录名查询与批量查询。

### 配置

新增 `app.workspace.*` 五个配置块（`limits` / `members` / `notes` / `documents` / `feed`），
并由 `ApplicationConfigStructureTest` 断言**每个键都真实存在** ——
record 的构造器绑定在属性缺失时**不报错**，嵌套组件会变成 `null`，
前缀写错整块配置会被静默忽略而应用照常启动。

---

## 架构决策

### 1. 第 ① 层只判身份，不按原始设计用 `hasPermission` 做资源判定

`docs/03-domain-permission.md` §10.3 原文写的是在 `@PreAuthorize` 里做资源级判断。
本实现**刻意偏离**，两条理由：

- **失败码会冲突。** 第 ① 层失败一律 403，而"看不到别人的空间"必须返回 404。
  两层都在 404 语义上下判断，只会让"到底是谁拒绝的"变成需要读日志才能回答的问题。
- **规则不会有两个实现。** 那需要额外装配 `PermissionEvaluator`，
  而它要做的事与 `AuthorizationService` 完全重复。同一套规则有两处实现就有分叉的可能，
  而分叉的表现是"某个入口的判定结果与其它入口不同"。

代价是第 ① 层变成一句略显平庸的 `hasAuthority` —— 这是有意的：它只负责把
"完全没有这类能力的身份"挡在最外面，**不假装自己知道资源归属**。

### 2. 不可见一律 404，可见但无权限才 403

403 等于承认"这个资源存在，只是不给你看"，攻击者据此可以把一个空间是否存在、
甚至它的 `public_id` 是否正确从响应码里读出来。404 让"不存在"与"不归你"在外部无法区分。

**代价是真正无权限的用户也会看到"找不到"** —— 这是有意的信息损失，
与社区模块"非作者编辑帖子返回 404"一致。一个被移出空间的成员，看到的错误是
"资源不存在"而不是"你已被移出"；这是接受的，因为后者会让任何一个人都能通过
"先加入、再退出"探测空间的存续状态。

### 3. 拥有者不重复写入成员表

`workspace.owner_id` 是"谁拥有该空间"的唯一事实来源。两处都记就有了两份必须同步的真相，
而"把拥有者移出成员 / 转让 / 级联清理"任何一次漏写都会造成
"`owner_id` 说是他、成员表说不是"的静默不一致 —— 且恰好落在鉴权判定上（判定要读这两处）。

代价是"我能在哪些空间里"要多并一次 `owner_id`，收在
`AuthorizationService.authorizedWorkspaceIds` **一处**；漏掉任何一半，
拥有者或成员会在某些查询上莫名看不到自己的数据。

### 4. 显式标注，而不是给第三层一个默认值

默认过滤的失败模式是"有人显式关掉了它"；默认不过滤的失败模式是"有人忘了打开"。
两者都要防，但**必须让人在写这条查询时被强制回答一次**这个问题。
因此是"二选一"而不是"默认 + 逃生门"。强制回答比默认值更能抵抗"下一个人的改动"。

`@Unscoped` 的理由字符串被断言**必须非空**：空理由会让这个机制退化成
"随手加一个注解就不报警了"。

### 5. 邀请是定向的，不是公开链接

公开链接一旦泄漏到群聊或搜索引擎，等于把"谁能进这个私有空间"的决定权交给了
任何一个拿到链接的人。定向邀请把决定权留在发起邀请的成员手上。

兑换时按 `code + invitee_id + status` 定位，因此**非受邀人凭同一串码得到 404**
（行不存在），而不是"这个码不是给你的" —— 后者会泄漏"码存在"这个事实。

### 6. 文档字节本阶段就落地，不等 Phase 05 的 MinIO

见 Implemented 一节。替换对象存储时变的只是一个 Bean，应用层与对外契约不动 ——
这正是先有端口、后有适配器的意义。

### 7. 迁移 V5 的六处刻意偏离（相对 `docs/03 §8`）

除了上面 3 与 5，还有：不建 `workspace_task` / `document_version`
（所属能力在本阶段既没有接口也没有状态机，先建表等于先把"我们支持这两件事"写进 schema）；
`visibility` 不含 `PUBLIC_READONLY`（它是一整条独立链路，放进枚举会让人以为链路已存在）；
不设 `member_count` / `document_count` 计数列（没有读路径必须依赖它，
而在鉴权相关的表上引入丢更新风险不划算）。逐条理由写在 `V5__workspace.sql` 开头。

---

## Git History

| Commit | 内容 |
|---|---|
| `273c134` | `refactor(common)`：渲染器与分页响应迁到公共包，社区改用新位置 |
| `ac4afb2` | `feat(workspace)`：空间模块本体、三层防线、迁移 V5、配置与跨模块接线 |
| `5bee5f6` | `test(workspace)`：权限矩阵 · SQL 改写 · 标注覆盖 · 本地存储 · 跨用户攻击 |
| `726a70e` | `docs(workspace)`：授权设计文档 + architecture / README / CHANGELOG |
| `8476d4e` | `build(release)`：版本号提升至 `0.4.0` |
| `b0787bb` | `merge`：Phase 04 合入 `develop` |
| `a99b1fd` | `merge`：Phase 04 发布到 `main` → `v0.4.0` |

标注 tag `v0.4.0` 打在 `a99b1fd`。功能分支 `feature/phase-04-workspace-authorization`
合并后已删除。本报告为发布后的文档补录（tag 不改写）。

规模：功能合并 `97 files changed, 9437 insertions(+), 69 deletions(-)`。

---

## Database Changes

**唯一迁移：`V5__workspace.sql`**（V1–V4 未改动，`validate-on-migrate: true` 会拦住对历史脚本的修改）。

| 表 | 说明 |
|---|---|
| `workspace` | 空间主表。`public_id CHAR(22)` 防 ID 遍历；`owner_id` 是拥有权的唯一来源；软删除 |
| `workspace_member` | 成员。主键 `(workspace_id, user_id)` 复合；`idx_workspace_member_user (user_id, workspace_id)` 是第三层防线的唯一热路径（反查"被授权哪些空间"走覆盖索引） |
| `workspace_invite` | 定向邀请。`code CHAR(22)` 唯一；`(invitee_id, status)` 既是"我的邀请"列表索引也是兑换定位索引；用 `expires_at` 而非布尔位，判定过期不需要定时任务 |
| `document` | 文档元数据。`parse_status` 默认 `PENDING`（本阶段只会出现这个值）；`storage_key`/`sha256` 允许 NULL 是为了让历史行在 schema 层面显式 |
| `note` | 协作笔记。与 `post` 同构（`body_md` 是事实来源、`body_html` 是写入时渲染净化的快照）；`author_id` 与 `updated_by` 分开，否则删除权限无从判定 |

同迁移内：

- 新增 **16 个权限点**（`workspace:*` 8 个 + `note:*` 4 个 + `document:*` 4 个），
  显式指定 id 以免绑定关系依赖自增顺序；全部授予基础角色 `USER`。
- 更新 `app_metadata.schema.baseline = 'V5'` —— 不更新它，`/api/v1/system/info`
  会继续报 V4，一个看起来正常、实际错误的结论。

---

## API

新增 19 个端点（全部需要认证；`SecurityConfig` 仍是"默认拒绝"）。

| 方法 | 路径 | 身份能力 |
|---|---|---|
| `GET` / `POST` | `/api/v1/workspaces` | `workspace:read` / `workspace:create` |
| `GET` / `PUT` / `DELETE` | `/api/v1/workspaces/{workspacePublicId}` | `workspace:read` / `update` / `delete` |
| `GET` | `/api/v1/workspaces/{workspacePublicId}/members` | `workspace:read` |
| `DELETE` | `/api/v1/workspaces/{workspacePublicId}/members/me` | **无**（退出自己） |
| `DELETE` | `/api/v1/workspaces/{workspacePublicId}/members/{targetUserPublicId}` | `workspace:member:remove` |
| `PUT` | `/api/v1/workspaces/{workspacePublicId}/members/{targetUserPublicId}/role` | `workspace:member:role:update` |
| `GET` / `POST` | `/api/v1/workspaces/{workspacePublicId}/invites` | `workspace:member:invite` |
| `DELETE` | `/api/v1/workspaces/{workspacePublicId}/invites/{code}` | `workspace:member:invite` |
| `POST` | `/api/v1/workspace-invites/{code}/accept` | `workspace:join` |
| `GET` / `POST` | `/api/v1/workspaces/{workspacePublicId}/notes` | `note:read` / `note:create` |
| `GET` / `PUT` / `DELETE` | `.../notes/{notePublicId}` | `note:read` / `update` / `delete` |
| `GET` / `POST` | `/api/v1/workspaces/{workspacePublicId}/documents` | `document:read` / `document:upload` |
| `GET` | `.../documents/{docPublicId}` | `document:read` |
| `GET` | `.../documents/{docPublicId}/content` | `document:download` |
| `DELETE` | `.../documents/{docPublicId}` | `document:delete` |

**兑换邀请的端点刻意不在 `/workspaces` 下**：它没有空间标识，也不可能有 ——
兑换者此刻还不是成员，不该被提前告知那个空间的 `publicId`。强制要求一个
"兑换者本来就不该有"的参数，会逼着调用方从邀请消息里把它塞进路径。

**新增错误码**：`40021` 空间成员数已达上限 · `40022` 空间内容不符合要求 ·
`40023` 空间拥有者不能退出 · `41300` 请求内容过大 · `50300` 依赖服务暂时不可用
（复用既有码：`40020` 邀请无效或已过期 · `40900` 数据已存在）。

**列表与详情新增 `deletableByMe`**：由服务端按与删除判定完全相同的规则算出，
前端不再自行推导删除权限 —— 否则会出现"前端算出能删、后端拒绝"的不一致。

---

## Tests

实际运行（真实粘贴）：

```text
$ ./mvnw -B clean verify

[INFO] Tests run: 145, Failures: 0, Errors: 0, Skipped: 0     ← surefire：单元 + 架构
[INFO] Tests run: 113, Failures: 0, Errors: 0, Skipped: 0     ← failsafe：集成（真实 MySQL 容器）
[INFO] BUILD SUCCESS
```

```text
$ cd frontend && npm test && npm run build

# tests 7 / # pass 7 / # fail 0
✓ built in 1.40s
```

架构断言 `ModuleBoundaryTest` **9 条规则全部通过**（模块间无循环依赖、
跨模块持久层隔离、`api` 不依赖 `infrastructure`、`domain` 不依赖 Spring 等）——
`workspace` 是第 4 个业务模块，未修改该测试即被自动覆盖。

### 本阶段新增测试（共 65 个用例）

| 类型 | 类 | 用例 |
|---|---|---|
| 单元 | `WorkspaceActionTest` | 10（角色矩阵 5 + 归属维度 5） |
| 单元 | `WorkspaceScopeSqlTest` | 18（边界 6 + 插入位置 7 + 语句类型 2 + 取值 3） |
| 单元 | `WorkspaceScopeCoverageTest` | 2 |
| 单元 | `LocalFileObjectStorageTest` | 7 |
| 单元 | `ApplicationConfigStructureTest`（扩充） | 1 个用例内断言全部 `app.workspace.*` 键 |
| 集成 | `WorkspaceAuthorizationIT` | 28（404 语义 6 · 可见但无权限 2 · 删除的归属 4 · 成员与邀请 12 · 第三层防线 4） |

### 测试写法的三个刻意选择

1. **第三层防线的测试必须绕过服务层。** 走服务层的话，被拒绝的原因永远是第二层，
   第三层**从未被真正执行过**。这一组用例直接调 Mapper，验证"绑定空集 → `1 = 0`"、
   "绑定到错误的空间 → 影响 0 行"、"`unscoped` 不吞异常"。
   这也是 `WorkspaceScopeContext.unscoped` 存在的唯一理由 ——
   不先关掉过滤，测的是过滤而不是兜底。
2. **删除的归属覆盖两个方向。** 只断言"成员不能删别人的"是不够的 ——
   一个把所有删除都拒绝的实现同样能通过。因此同时断言"成员能删自己的"、
   "拥有者与管理员能删任何一条"，以及 `deletableByMe` 与实际删除结果**一致**。
3. **准备动作进夹具，断言留在用例里。** `WorkspaceTestSupport` 把"建空间、拉人、写内容"
   这些最少 4 次 HTTP 调用的准备收成方法，但**不断言状态码**（少量"必须成功否则
   后续无意义"的除外）。把断言藏进夹具，会让测试读起来像是通过了，而实际什么都没验证。

### 由"真正运行"暴露、而不是由编写暴露的缺陷（4 个）

这四个都写完了、看起来是对的，跑起来才发现。它们的共同点是**失败模式为静默或错位**：

| # | 缺陷 | 表现 | 为什么难发现 |
|---|---|---|---|
| 1 | 结果映射里指名 `typeHandler="org.apache.ibatis.type.EnumTypeHandler"` | 全部 28 个用例 500，`No enum constant UserStatus.PRIVATE` | MyBatis 的类型注册表除按 `javaType` 建的那张表外，还有一张**以 handler 类为键**的表（`ALL_TYPE_HANDLERS_MAP`）。指名通用处理器时先命中后者，拿到的可能是**为另一个枚举**创建的实例 —— 谁是"上一个被解析的枚举"取决于 mapper 的加载顺序。只在真正查询时暴露，编译期毫无提示 |
| 2 | `InviteRequest.toRole()` 未实现文档承诺的"留空按 MEMBER" | 所有不传 `role` 的邀请返回 `40001` | 字段文档写了默认值，而实现把它交给了"无法识别即拒绝"的严格分支 |
| 3 | `WorkspaceInviteMapper.revoke` 只按 `code` 撤销 | 跨空间的越权改状态 | 主路径（同空间撤销）完全正常，只有在"我在另一个空间里也是成员"时才暴露 |
| 4 | 成员上限判断用带自动空间过滤的计数 | 上限在兑换路径上**静默失效** | 兑换者在兑换时还不是成员，授权集合里没有这个空间 → 计数恒为 0。用自己造的数据测永远触发不了 |

第 1 条已把结论写回 `WorkspaceMapper.xml` 的注释里，并统一回到项目既有约定
（resultMap 只写 `javaType`，参数只写 `#{x}`）；第 3、4 条由集成测试
"撤销跨空间 404"与"容量判断"两组用例钉住。

此外还修掉一处 SQL 拼接隐患：第三层防线在 `head` 末尾未补空白时会拼出
`?AND workspace_id` —— MySQL 的词法恰好接受，于是**不报错**，只是日志里的 SQL
看起来像坏的。已补 `separator` 空白判定并有单测覆盖。

---

## Security

本阶段新增的安全保障（全部有对应测试）：

- **私有内容只有成员可见。** 不可见一律 `404`（不区分"不存在"与"不归你"），
  可见但无权限才 `403` —— 响应码不再泄漏某个空间或资源是否存在。
- **第 ③ 层由构建期断言强制。** 每条访问私有表的语句必须在 `@ScopedTable` 与
  `@Unscoped("理由")` 之间二选一，理由非空；授权集合为空时查询退化成 `1 = 0` ——
  **拿不到身份时查不到数据，而不是查到全部**。
- **范围绑定必须清理。** `WorkspaceScopeContext` 是 `ThreadLocal`，由
  `WorkspaceScopeResetFilter` 在请求结束时**无条件**清理（无兜底）。
  不清理会让线程池复用时的下一个请求继承上一个请求的授权范围 ——
  越权最典型的成因之一，且只在并发下出现。
- **`unscoped` 不是业务代码的逃生门。** 名字说清它关掉的是哪一层；
  主代码里没有调用，只有防线自身的测试用它。
- **文档路径两道路径安全**；原始文件名绝不参与路径拼接；上传先落临时文件再原子改名，
  字节数与声明不符即删除并报错（**半份内容比没有内容更危险**，因为它会被当成成品使用）。
- **下载恒为附件**（`Content-Disposition: attachment`；`download-inline: false`），
  MIME 用白名单 —— 内联打开用户上传的 HTML 等于一次 XSS。
  缺内容返回 503 而不是 404：资源存在、权限也有，只是承载它的东西暂时取不到。
- **两层大小闸门取值不同**：容器级上限更大，应用级上限更小且更严，
  让"文件太大"由应用返回统一错误信封，而不是由容器抛出形状不同的异常。
- **定向邀请不泄漏码的存在性**：非受邀人、已兑换、已撤销一律 `404`。
- **并发兑换只成功一次**：靠条件更新（`WHERE status = 'PENDING'`）的影响行数判定，
  而不是"先查后改"—— 后者在并发下会写出两条成员关系。
- **16 个权限点全部授予基础角色 `USER`**，但资源归属判定在第 ②③ 层 ——
  "人人都有这类能力"不等于"人人都是管理员"。

---

## Performance

**Not benchmarked yet.**

本阶段未做压测。既有的可复现基线只有 Phase 03 的社区只读接口
（`scripts/bench/query-baseline.mjs` + `docs/experiments/EX-000-query-baseline.md`），
它不覆盖空间模块的接口。

不做压测的判断依据：本阶段新增的查询都在**已有索引**上（
`idx_workspace_member_user` 覆盖第三层防线唯一的反查、
`idx_workspace_owner` 服务于"我拥有的空间"列表），且成员数与文档数由配置与上传量
约束住规模。按规约 §18.3，**不为此提前引入缓存**；是否加缓存与分页优化
留给 Phase 09 的压测结论，而不是现在猜。

---

## Known Limitations

- `document.parse_status` 只会是 `PENDING`。内容解析、分块与 `READY` / `FAILED`
  状态流转属 Phase 05。
- **没有通知机制**：邀请码需要邀请人自行转达（通知属 Phase 10）。列表页显示邀请码
  就是为了这件事。
- **无法转让空间**：拥有者不能退出（`40023`），当前只能删除空间或保持原样。
- **`TEAM` 与 `PRIVATE` 在授权判定上没有区别**：两者都只有拥有者与成员可见。
  `TEAM` 目前只表达"这个空间是给团队用的"这一意图。这是刻意的 ——
  让一个取值先出现但没有对应**新行为**，比让一个取值先出现并假装它有新行为要好。
- **本地磁盘存储没有配额与清理**：删除文档只标记 `deleted_at`，字节仍留在磁盘上。
- 空间成员列表与文档列表都未分页（分别受 `max-members = 50` 与当前上传量约束）。
- **前端未接入**：本阶段只交付后端与接口，空间相关界面不在 Phase 04 范围内。
- 第 ③ 层只覆盖 `workspace_id` 这一个维度：它防"空间之间"的越权，
  防不了"同一空间内"的越权（那是第 ② 层的职责）。

---

## Technical Debt

记录在 `docs/architecture.md` §10，本阶段新增 7 项：

| 项 | 现状 | 触发条件 / 计划 |
|---|---|---|
| 文档字节存本地磁盘，无配额与清理 | 删除只标记 `deleted_at` | Phase 05 与对象存储一起设计（生命周期规则、孤儿对象回收） |
| 空间无法转让 | 拥有者不能退出 | 出现真实需求时新增"转让空间"，事务内同时改 `owner_id` 与成员角色 |
| `TEAM` 与 `PRIVATE` 在授权上无区别 | 两者都只有成员可见 | 与 Phase 06/07 的发现与推荐一起设计 |
| 空间成员列表未分页 | 受 `max-members` 约束 | 上限调到百级时再分页 |
| 文档列表未分页 | 受上传量约束 | 与 Phase 05 配额一起处理 |
| 邀请不代发通知 | 由邀请人转达 | Phase 10 通知域 |
| 权限判定无缓存（每请求回库） | 换来实现简单、权限变更立即生效 | Phase 09 压测后按实测决定 |

另外两项与本阶段相关但非新引入：
**净化白名单的修复不追溯历史数据**（`body_html` 是写入时的快照，
处置路径是重渲染回填，`body_md` 已保留，属 ADR 0005 的既有取舍）；
**刷新令牌仍存 `localStorage`**（沿用 Phase 02 的知情取舍，收口方式见 §3.7）。

---

## Next Phase Dependencies

Phase 05（Object Storage & Document Workflow）从本阶段接手的东西：

1. **`ObjectStorage` 端口已就位。** Phase 05 增加 S3/MinIO 适配器时替换的只是一个 Bean
   （`WorkspaceConfig#objectStorage`）；应用层与对外契约不动。
   应用层对 `app.workspace.documents.storage-dir` 的引用只存在于这个装配点 ——
   这是刻意的，为的就是这一步。
2. **`document` 表的状态机字段已就位**：`parse_status`（`PENDING` / `PROCESSING` /
   `READY` / `FAILED`）与 `parse_message`（面向用户的简短说明，不含内部堆栈）
   都已建好，但本阶段只会出现 `PENDING`。Phase 05 接管"状态的写入方"。
3. **`sha256` 已在写入时计算并落库**，对外不返回。它是 Phase 05 做内容去重（秒传）
   与完整性校验的输入。
4. **本阶段刻意不做的**：内容解析、分块、版本树。`chunk` 与 `document_version`
   两张表也刻意没建 —— 所属能力在本阶段既没有接口也没有状态机。
5. **`DocumentService` 的写入顺序是"字节先落存储、再登记元数据"**，
   因此走到登记那一步时键与哈希都已存在。Phase 05 引入异步解析后，
   这个顺序不能反过来：一条"元数据说可检索、内容还没落"的行比没有行更糟。
6. **迁移纪律**：V5 已应用，禁止再改；Phase 05 的变更一律写 `V6`，
   并在末尾更新 `app_metadata` 的 `schema.baseline` —— 不更新它，
   `/api/v1/system/info` 会继续报 V5，一个看起来正常、实际错误的结论。
