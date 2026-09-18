# resource-authorization.md · 资源级授权（Phase 04）

> **效力**：本文件是 Phase 04 的交付物，定义"私有数据谁能看到、谁能改"的完整规则。
> **事实来源**：权限矩阵的代码来源是 `WorkspaceAction`（`ai.camphub.workspace.domain`），
> 它会被 `WorkspaceActionTest` 逐格断言。文档与代码不一致时以代码为准 —— 因为代码会跑测试。
> **上位文档**：`docs/03-domain-permission.md` §10（模型与三层防线的原始设计）、
> `docs/04-security.md`（安全基线）。原始设计中与本实现不一致之处，逐条在 §7 说明。

---

## 1. 要解决的问题

Phase 01–03 的系统里只有两类数据：**公开的**（社区帖子、评论、板块）与**属于我自己的**（账号、资料）。
两者的授权分别是"登录即可读"与"只能是自己"，用 `@PreAuthorize` 加一句"是不是作者"就能表达。

Phase 04 引入了第三类：**属于一个小组的私有数据**。空间、成员、笔记、文档。
它带来两个新的问题：

1. **判定对象变多了**。"能不能读这个空间"不再只取决于我是谁，还取决于
   (我, 空间) 这个二元组的关系 —— 而那个关系会变（被邀请、被移除、角色被改、空间被删）。
2. **检索必须带权限。** 私有数据不可能"先查出来再过滤掉不该看的"：
   列表、分页、计数只要有一次漏过滤，泄漏就是静默的，而且不会被任何手工测试发现 ——
   用自己的数据怎么测都是对的。

第 2 点决定了本阶段的架构：权限不能只写在控制器的 `if` 里，
它必须**下沉到数据访问层**，成为"这条 SQL 天然只能看到我的数据"。

---

## 2. 三层防线

```
请求 → ① @PreAuthorize（身份能力）  → 403
     → ② AuthorizationService.assertCan（资源级判定） → 404 / 403
     → ③ WorkspaceScopeInterceptor（数据范围过滤）   → 空集
     → SQL
```

### 2.1 三层各自回答的问题

| 层 | 实现 | 回答的问题 | 失败时 | 谁写 |
|---|---|---|---|---|
| ① 身份能力 | 方法上的 `@PreAuthorize("hasAuthority('...')")` | 这个**身份**有没有做这类事的能力？ | `40300` | 每个控制器方法 |
| ② 资源判定 | `AuthorizationService.assertCan` | 这个人对**这一条数据**能不能做这件事？ | `40400` / `40300` | 每个应用服务方法 |
| ③ 数据范围 | MyBatis 拦截器 + `@ScopedTable` | 这条 SQL 有没有被限制在他的空间范围内？ | 授权集合为空 → `1 = 0` | 应用框架，全局一次 |

**三层不是三个备份，而是三个不同的判据。** 举例说明它们的不可互相替代：

- 第 ① 层通过、第 ② 层拒绝：`workspace:delete` 是授予所有用户的身份能力，
  但只有空间的拥有者能真正删掉它。
- 第 ① ② 层都通过、第 ③ 层兜底：某个成员被移出空间后，
  他手上尚未过期的一次请求；或将来某条新写的查询忘了带 `workspace_id` 条件。

### 2.2 为什么第 ① 层只判身份，而不按原始设计做资源判定

`docs/03-domain-permission.md` §10.3 的原始设计写的是
`@PreAuthorize("hasPermission(#req.workspaceId, 'Workspace', 'read')")`，
即在第一层就做资源级判断。**本实现刻意没有那样做**，两条理由：

1. **失败码会冲突。** 第一层失败一律 403，而"看不到别人的空间"必须返回 404（见 §2.3）。
   两层都在 404 语义上下判断，只会让"到底是谁拒绝的"变成需要读日志才能回答的问题。
2. **规则不会有两个实现。** 那需要额外装配 `PermissionEvaluator`，
   而它要做的事与 `AuthorizationService` 完全重复。同一套规则有两处实现，就有了分叉的可能，
   而分叉的表现是"某个入口的判定结果与其它入口不同"—— 这类问题极难被发现。

代价是第一层变成了一句略显平庸的 `hasAuthority`。这是有意的：
**它只负责把"完全没有这类能力的身份"挡在最外面，不假装自己知道资源归属。**

### 2.3 404 与 403 的边界

| 情形 | 响应 | 理由 |
|---|---|---|
| 空间不存在 / 已删除 / **我根本不是它的成员** | `40400` | 三者合并。否则攻击者能用响应码把"这个 `public_id` 是否存在"读出来。代价是真正无权限的用户也会看到"找不到" —— 这是**有意的信息损失** |
| 未登录 | `40100` | 与"没权限"是两件事，且登录本身不泄漏任何资源信息 |
| 我能看到这个空间，但我的身份做不了这个动作 | `40300` | 此时"空间存在"已经是他本来就掌握的信息，再假装不存在只会让人困惑 |

判定"不可见"的实现只有一处：`AuthorizationService.roleIn` 返回 `NONE`。
`NONE` 的成因刻意不区分（空间不存在、已删除、不是成员）——
区分它们就需要在日志与响应里携带不同信息，而那正是我们要避免的。

**这条取舍会带来一个真实的用户困扰**：一个被移出空间的成员，看到的错误是"资源不存在"，
而不是"你已被移出"。这是接受的 —— 因为后者会让任何一个人都能通过"先加入、再退出"
来探测空间的存续状态。空间列表里"这个空间消失了"才是正确的沟通渠道。

---

## 3. 权限矩阵

`WorkspaceRoleInContext` 有四个取值，其中 `NONE` 表示"不是这个空间的人"：

| 取值 | 含义 | 来源 |
|---|---|---|
| `NONE` | 不是成员，或空间不存在/已删除 | 判定结果，非存储值 |
| `MEMBER` | 被授予 `MEMBER` 角色的成员 | `workspace_member.role` |
| `ADMIN` | 被授予 `ADMIN` 角色的成员 | `workspace_member.role` |
| `OWNER` | 空间拥有者 | `workspace.owner_id` |

### 3.1 空间与成员

| 动作 | OWNER | ADMIN | MEMBER | 对应身份能力 |
|---|:-:|:-:|:-:|---|
| 读取空间信息与成员列表 | ✅ | ✅ | ✅ | `workspace:read` |
| 修改空间设置（名称/描述/可见性） | ✅ | — | — | `workspace:update` |
| 删除空间 | ✅ | — | — | `workspace:delete` |
| 邀请他人加入 | ✅ | ✅ | — | `workspace:member:invite` |
| 移除成员 | ✅ | ✅ | — | `workspace:member:remove` |
| 修改成员角色 | ✅ | ✅ | — | `workspace:member:role:update` |
| 退出空间（移除自己） | — | ✅ | ✅ | **无身份能力要求** |

**两处刻意的例外：**

- **改设置与删空间只给拥有者。** 改 `visibility` 等于改"谁能看到这个空间"，
  删空间是不可逆的。把它们交给 `ADMIN`，意味着一次误操作可以改变整个空间的可见范围。
- **退出空间不设身份能力点。** 它是"移除我自己"，
  而"移除成员"（`workspace:member:remove`）的语义是管理他人。混用会让一个
  只想给"能退出"的身份顺带获得"能踢人"。
  拥有者不能退出，返回 `40023`：`owner_id` 必须指向一个真实存在的人，
  退出会让空间成为没有拥有者的孤儿。正确做法是转让或删除。

### 3.2 笔记与文档

| 动作 | OWNER | ADMIN | MEMBER | 归属维度 | 身份能力 |
|---|:-:|:-:|:-:|:-:|---|
| 读笔记（列表/详情） | ✅ | ✅ | ✅ | — | `note:read` |
| 创建笔记 | ✅ | ✅ | ✅ | — | `note:create` |
| **编辑**笔记 | ✅ | ✅ | ✅ | 不区分 | `note:update` |
| **删除**笔记 | ✅ | ✅ | 仅自己创建的 | 区分 | `note:delete` |
| 读文档元数据与列表 | ✅ | ✅ | ✅ | — | `document:read` |
| 上传文档 | ✅ | ✅ | ✅ | — | `document:upload` |
| 下载文档原文件 | ✅ | ✅ | ✅ | — | `document:download` |
| **删除**文档 | ✅ | ✅ | 仅自己上传的 | 区分 | `document:delete` |

**"编辑开放、删除收紧"是刻意的，不是疏忽。**
编辑不会丢失内容：`body_md` 是事实来源，任何一次编辑都是一次可追溯的 `updated_by` 变更，
留下的是内容的新版本而不是内容的消失。删除会让其他成员的引用失效，
且对笔记而言内容真的没了。因此协作开放到编辑这一层，而删除要求正当性
（"是我创建的东西"或"我是管理者"）。

这条规则在实现上是 `WorkspaceAction.isOwnershipSensitive()`，
它由枚举自己回答"哪些动作区分归属"，而不是让调用方写
`switch (action) { case DELETE_NOTE, DELETE_DOCUMENT -> ... }`。
后者的意思是"哪些动作区分归属"这件事散落在调用点 ——
而新增一个删除类动作时，没有任何东西会提醒你去补上那一支。

**`deletableByMe` 字段。** 列表与详情响应里都带一个
`deletableByMe: boolean`，它是服务端按同一条规则算出来的，供前端决定是否显示删除按钮。
前端不用自己推导（"我是不是作者"），因此不存在"前端算出来能删、后端拒绝"的不一致 ——
接口契约里就是"这个字段为 true 时删除一定会成功（除非期间数据被改动）"。

### 3.3 身份能力点的来源

16 个权限点全部授予基础角色 `USER`（V5 迁移）：

```
workspace:create · workspace:read · workspace:update · workspace:delete
workspace:member:invite · workspace:member:remove · workspace:member:role:update
workspace:join
note:create · note:read · note:update · note:delete
document:upload · document:read · document:delete · document:download
```

**"人人都有"不等于"人人都是管理员"。** 这些码回答的是"身份有没有这类能力"，
而"这条数据能不能给他"由第 ②③ 层回答。
`workspace:delete` 授予了 `USER`，但只有拥有者能删 —— 判定在 `AuthorizationService` 里。

反过来，如果把 `workspace:delete` 只授给某个"管理员角色"，
"拥有者删自己的空间"就会变成一件需要额外角色才能做的事 —— 那不是我们想要的模型。

---

## 4. 身份从哪来：拥有者不在成员表

`workspace.owner_id` 是"谁拥有该空间"的**唯一事实来源**，
拥有者刻意**不**以 `role='OWNER'` 的身份重复写入 `workspace_member`。

**理由：** 若两处都记，就有了两份必须同步的真相。把拥有者移出成员、转让空间、
级联清理 —— 任何一次漏写都会造成"`owner_id` 说是他、成员表说不是"的静默不一致，
而这种不一致恰好落在鉴权判定上（判定要同时读这两处）。

**代价**是"我能在哪些空间里"要在 SQL 里多并一次 `owner_id`：

```java
// AuthorizationService.authorizedWorkspaceIds
Set<Long> ids = new HashSet<>(workspaceMapper.findOwnedIds(userId));
ids.addAll(memberMapper.findMemberWorkspaceIds(userId));
```

这是一次确定性的 `UNION`，而不是一类偶发故障。**漏掉任何一半，拥有者或成员
会在某些查询上莫名看不到自己的数据** —— 这也是把它收在**唯一一个方法**里的原因：
第三层防线与将来任何按空间过滤的检索都只通过它拿范围。

成员列表接口把拥有者**合成**为一行（`OWNER` 排在第一位），
因此调用方看到的是一个完整的成员列表，而不需要自己拼装。

---

## 5. 第三层防线（数据范围）

### 5.1 它是防护网，不是主防线

真正决定"这件事能不能做"的是第二层：它知道动作、知道身份、也能对单条资源做细致判断。
第三层只知道一件事：**这条 SQL 碰的是私有数据，而调用者被授权的空间是这些**。

它的价值在于覆盖一种失误 —— **某条查询忘了写 `workspace_id` 过滤**。
这种失误在手工测试里几乎看不出来（用自己造的数据怎么测都正常），
却会在生产上把别人的私有内容返回给不相干的人。

### 5.2 显式标注纪律

每条访问私有表的 `select` / `update` / `delete` **必须**二选一：

- `@ScopedTable(column = "workspace_id")` —— 由第三层防线追加范围条件；
- `@Unscoped("理由")` —— 声明不受过滤，**理由必须非空**。

`INSERT` 不得标注（它不构成读取越权）。

**这不是靠评审自觉，而是构建期断言。** `WorkspaceScopeCoverageTest` 反射读取
五个 Mapper 接口的方法与 XML，逐条核对：

- 每条 `select`/`update`/`delete` 恰好命中一个标注；
- `@Unscoped` 的理由字符串非空 —— 空理由会让这个机制退化成"随手加一个注解就不报警了"；
- `INSERT` 上没有标注；
- XML 里的语句 id 与接口方法**一一对应**（防止"注解加在一个已经不存在的语句上"）；
- 五张私有表确实在 XML 里被查询（防止"整个 Mapper 文件被漏掉"这种整体性遗漏）。

**为什么是"二选一"而不是"默认过滤、需要时关掉"：**
默认过滤的失败模式是"有人显式关掉了它"；默认不过滤的失败模式是"有人忘了打开"。
两者都要防，但**必须让人在写这条查询时被强制回答一次**这个问题。
强制回答比默认值更能抵抗"下一个人的改动"。

### 5.3 SQL 改写

拦截点在 `StatementHandler.prepare` —— 这是能拿到最终 SQL 的最早位置：
`BoundSql` 已由 SqlSource 组装完成、动态标签都已求值，而 `?` 占位符还没绑定。
在它之前（Executor 层）拿不到 `BoundSql`；在它之后（ParameterHandler 层）
语句已经创建，改 SQL 也不生效。

`WorkspaceScopeSql` 负责拼接，它与"已授权的空间主键集合"的取值方式无关，
因此可以被独立测试。它需要处理的形状：

| 输入的 SQL | 追加结果 |
|---|---|
| 没有 `WHERE` | `... WHERE workspace_id IN (...)` |
| 已有 `WHERE` | `... WHERE <原条件> AND workspace_id IN (...)` |
| 含子查询的 `WHERE` | 括号配对计数，插到**外层**条件上 |
| 含 `ORDER BY` / `LIMIT` / `GROUP BY` / `HAVING` / `FOR UPDATE` | 插在这些子句**之前** |
| 语句末尾带 `;` | 插在分号之前 |
| `INSERT` / DDL | 原样返回 |

两处细节是踩出来的，都写在了 `WorkspaceScopeSqlTest` 里：

1. **`head` 末尾必须补一个空格。** 否则形如 `select * from t where a = ?` 的语句
   会拼成 `... a = ?AND workspace_id IN (...)`。MySQL 的词法恰好能接受，
   于是它**不会报错**，只是让生成出来的 SQL 在日志里看起来像坏的。
2. **取值只内联数字。** 空间主键是 `long`，拼接前逐个校验为数字类型，
   不引入第二个参数占位符。这既是防注入的要求，
   也避免了"参数顺序与占位符顺序"这一类只有运行期才暴露的错误。

### 5.4 空集语义与线程清理

**拿不到身份时绑定空集，查询退化成 `1 = 0`。**
这个默认方向是刻意的：**查不到数据，比查到全部要好。**
匿名请求、后台线程、没有 Spring Security 上下文的场景都会走到这一支。

`WorkspaceScopeContext` 是 `ThreadLocal`：

- **为什么需要它：** 一次请求内通常要跑好几条被限制的查询（列表 SQL + 计数 SQL 就是两条）。
  绑定后整条请求链路共用一份结果，而不是每条查询重算一次授权集合。
- **为什么必须有人清理：** Tomcat 的请求线程来自线程池、会被下一个请求复用。
  若本线程上的集合没被清掉，**下一个请求会继承上一个请求的授权范围** ——
  这正是"越权"最典型的成因之一，且只在并发下出现，本机单请求手工测试永远看不到。
  `WorkspaceScopeResetFilter` 在每次请求结束时**无条件**清理；
  这里不提供任何"自动过期"之类的兜底 —— 兜底会掩盖忘记清理的问题。

**`unscoped` 与 `runAs` 是两件不同的事，不要混用：**

| 方法 | 语义 | 允许的调用方 |
|---|---|---|
| `WorkspaceScopeContext.unscoped(...)` | **彻底关掉**过滤 | 仅第三层防线自身的测试。当前主代码里没有调用 |
| `AuthorizationService.runAs(userId, ...)` | 换一个身份，**过滤照常生效** | 演示数据播种（该进程没有 HTTP 请求，第三层会算出空集） |

`runAs` 刻意保留过滤：它把身份从"当前请求"换成"指定的用户"，
而不是提供一个"关掉权限"的开关。

---

## 6. 文档存储

### 6.1 端口与适配器

应用层只依赖 `ObjectStorage` 端口（`store` / `open` / `delete`），
Phase 04 的适配器是 `LocalFileObjectStorage`（本地磁盘）。

**为什么不等 Phase 05 的 MinIO：** 一个"上传永远返回 503、下载永远返回 503"
的文档功能对使用者等于不存在。本地磁盘是**真实的存储后端**（不是空壳实现），
它让整条链路在本阶段就能被真正验收；Phase 05 增加 S3 适配器时，
替换的只是一个 Bean，应用层与对外契约都不动。

本阶段**不做**：内容解析、分块、版本树。`document.parse_status` 只会出现 `PENDING`。

### 6.2 路径安全：两道，防的不是同一件事

1. **格式校验** —— `keyHint` 必须匹配 `[A-Za-z0-9_-]{1,64}`。
   本项目传入的是服务端生成的 `public_id`（22 位 62 进制随机串），本来就安全。
   但"本来就安全"不是一种可以依赖的性质：下一个人完全可能为了让文件名可读，
   把它改成"用原始文件名做前缀"。有了格式校验，那种改动会立刻写入失败，
   而不是变成一个路径穿越漏洞。
2. **归一化校验** —— 拼好的路径必须仍然位于存储根目录之内。
   它防的是格式校验被绕过、或存储根配置本身被写成相对路径时的意外逃逸。

**原始文件名只用于展示，绝不参与任何路径拼接**（`document.name` 上写明了这一点）。

### 6.3 写入的原子性与完整性

- **原子写**：先写 `.part` 临时文件，再 `ATOMIC_MOVE` 改名。否则一个在上传中途断开的
  请求会留下半份内容，而它的元数据行看起来完全正常 ——
  用户会下载到一个截断的文件，且没有任何东西能告出"这份文件是坏的"。
  临时文件以点开头，避免被当成正式文件枚举到。
- **字节数校验**：写完后比对实际字节数与调用方声明的大小，不一致就删掉并报错。
  **内容不完整比没有内容更糟**，因为它会被当成成品使用。
- **SHA-256**：写入时计算并落库（`document.sha256`，对外不返回）。
  当前用于完整性校验，也是将来做内容去重（秒传）的输入。
- **写入顺序**：字节先落存储、再登记元数据行。因此走到登记这一步时键与哈希都已存在。

### 6.4 上传与下载的边界

- **两层大小闸门，取值不同。** `spring.servlet.multipart.max-file-size` 是容器级硬上限；
  `app.workspace.documents.max-size-bytes`（10 MiB）**刻意更小**，
  让"文件太大"由应用自己判断并返回统一错误信封。
  容器抛出的异常形状不同，且发生在进入控制器**之前** ——
  `GlobalExceptionHandler` 把它翻成 `41300`，否则会落进兜底的 500。
- **MIME 白名单而不是黑名单。** 黑名单永远列不完，而漏掉的那一项就是一次真实的放行。
- **下载响应头**：`Content-Type` 恒为 `application/octet-stream`，
  `Content-Disposition: attachment`（用 RFC 5987 编码中文文件名），并带 `contentLength`。
  **绝不内联打开**（`download-inline: false`）：内联意味着用户上传的内容会在本站域的源下
  被浏览器解析渲染 —— 这正是"上传一个 HTML 就得到一个 XSS"的成因。
- **下载返回 503 而不是 404**：元数据说"有内容"但字节不在时（被外部清理、或历史行），
  资源确实存在、权限也确实有，只是承载它的东西取不到。404 会让使用者以为自己的文档被删了。

---

## 7. 邀请模型

### 7.1 定向邀请，不是公开链接

形态是**定向邀请码**：邀请行带 `invitee_id`，**只有被邀请人能兑换**。

**理由：** 公开链接一旦泄漏到群聊或搜索引擎，就等于把"谁能进这个私有空间"的决定权
交给了任何一个拿到链接的人；而定向邀请把决定权留在发起邀请的成员手上。

兑换时的定位条件是 `code = ? AND invitee_id = 当前用户 AND status = 'PENDING'`，
因此**非受邀人凭同一串码兑换得到的是 404**（行不存在），而不是"这个码不是给你的"。
后者会泄漏"码存在"这个事实，让人可以批量试码探测空间。

### 7.2 四种拒绝，三种响应

| 情形 | 响应 | 理由 |
|---|---|---|
| 码不存在 / 不是发给我的 / 已兑换 / 已撤销 | `40400` | 合并成同一个码，让"这个码是否存在"从响应里读不出来 |
| 码是我的，但已过期 | `40020` | 调用方已证明他就是受邀人（码在他手上），此时"过期了"是可操作的反馈 |
| 我已经在这个空间里 | `40900` | 与邀请的有效性无关，给一个能读懂的状态码 |
| 我发起了但没写被邀请人 | `40400` | 被邀请人不存在 |
| 邀请自己 | `40900` | — |
| 被邀请人已在空间内 | `40900` | — |
| 空间成员数已达上限 | `40021` | — |

**过期判断刻意不写在 SQL 里。** 过期需要与"码不存在"给出不同的错误码
（`40020` 与 `40400`），所以必须把行取回 Java 之后按**同一个应用时钟**判断，
而不是让数据库按它自己的 `CURRENT_TIMESTAMP` 先把它过滤掉。
同理，"待处理邀请"列表里的"还有多久过期"与判定依据必须来自同一个时钟。

### 7.3 一次性语义靠条件更新

```sql
UPDATE workspace_invite SET status = 'ACCEPTED', accepted_at = ?
WHERE id = ? AND status = 'PENDING'
```

两个并发请求同时兑换同一个码，只有一个能把 `PENDING` 改走，另一个影响 0 行。
调用方按**影响行数**判定谁赢了，而不是"先查后改" ——
后者在并发下会写出两条成员关系。

兑换成功后的顺序是：**先标记邀请已接受，再插入成员行**。
若插入成员失败，标记也随之回滚（同一事务）—— 不会出现"码用掉了但人没进来"。

### 7.4 撤销必须带 `workspace_id`

```sql
UPDATE workspace_invite SET status = 'REVOKED'
WHERE workspace_id = ? AND code = ? AND status = 'PENDING'
```

只按 `code` 撤销，会让"我在别的空间里也是成员"变成一次**跨空间的越权改状态**。
`status = 'PENDING'` 则让"撤销一条已兑换的邀请"影响 0 行，由调用方翻成 404 ——
已经生效的成员关系不能被一次撤销抹掉。

### 7.5 有效期

`app.workspace.members.invite-valid-hours = 72`。
太短会让"发出去还没来得及点"变成常态；太长等于给一个长期有效的入门口令。
72 小时覆盖一个周末。

### 7.6 成员上限

`app.workspace.members.max-members = 50`（**不含拥有者**）。
它是**资源保护上限**：成员列表的规模、以及"是否已满"那次计数的上界都由它决定。

判定靠**数成员行**而不是维护一个计数列（见 V5 偏离说明 4）：
两张表被这个上限本身约束住，因此这次计数的代价上界是配置里的那个数字，不是数据的规模。

**计数必须用 `countMembers`（`@Unscoped`）而不是 `findMembers`。**
后者的自动空间过滤会让它数出 0 —— 因为有一条调用路径来自**还没加入空间的人**（兑换邀请码），
他的授权集合里没有这个空间。用后者会让上限在兑换路径上**静默失效**。
这条路径的安全性由"空间主键来自那条 `invitee_id = 当前用户` 的邀请行本身"保证。

---

## 8. 对外接口

| 方法 | 路径 | 身份能力 |
|---|---|---|
| `GET` | `/api/v1/workspaces` | `workspace:read` |
| `POST` | `/api/v1/workspaces` | `workspace:create` |
| `GET` | `/api/v1/workspaces/{workspacePublicId}` | `workspace:read` |
| `PUT` | `/api/v1/workspaces/{workspacePublicId}` | `workspace:update` |
| `DELETE` | `/api/v1/workspaces/{workspacePublicId}` | `workspace:delete` |
| `GET` | `/api/v1/workspaces/{workspacePublicId}/members` | `workspace:read` |
| `DELETE` | `/api/v1/workspaces/{workspacePublicId}/members/me` | **无**（退出自己） |
| `DELETE` | `/api/v1/workspaces/{workspacePublicId}/members/{targetUserPublicId}` | `workspace:member:remove` |
| `PUT` | `/api/v1/workspaces/{workspacePublicId}/members/{targetUserPublicId}/role` | `workspace:member:role:update` |
| `GET` | `/api/v1/workspaces/{workspacePublicId}/invites` | `workspace:member:invite` |
| `POST` | `/api/v1/workspaces/{workspacePublicId}/invites` | `workspace:member:invite` |
| `DELETE` | `/api/v1/workspaces/{workspacePublicId}/invites/{code}` | `workspace:member:invite` |
| `POST` | `/api/v1/workspace-invites/{code}/accept` | `workspace:join` |
| `GET`/`POST` | `/api/v1/workspaces/{workspacePublicId}/notes` | `note:read` / `note:create` |
| `GET`/`PUT`/`DELETE` | `/api/v1/workspaces/{workspacePublicId}/notes/{notePublicId}` | `note:read` / `note:update` / `note:delete` |
| `GET`/`POST` | `/api/v1/workspaces/{workspacePublicId}/documents` | `document:read` / `document:upload` |
| `GET` | `/api/v1/workspaces/{workspacePublicId}/documents/{docPublicId}` | `document:read` |
| `GET` | `/api/v1/workspaces/{workspacePublicId}/documents/{docPublicId}/content` | `document:download` |
| `DELETE` | `/api/v1/workspaces/{workspacePublicId}/documents/{docPublicId}` | `document:delete` |

**兑换邀请的端点刻意不在 `/workspaces` 下**，因为它**没有空间标识**，也不可能有：
兑换的人此刻还不是成员，自然不知道（也不该被提前告知）那个空间的 `publicId` ——
告诉他这个标识，等于在他还没加入之前就泄漏了这个空间的存在。

若把它写成 `POST /workspaces/{workspacePublicId}/invites/{code}/accept`，
强制要求一个"兑换者本来就不该有"的参数，会逼着调用方去别处搞到它 ——
而那个"别处"通常就是邀请消息本身，于是路径里出现一个纯粹为了满足路由形状的字段。

---

## 9. 与原始设计（docs/03）的偏离

逐条给出理由。这一节的目的是让**下一个读原始设计的人不会以为本实现漏了东西**。

| # | 原始设计 | 本实现 | 理由 |
|---|---|---|---|
| 1 | 11 个权限点 | 16 个 | 补充 `workspace:join`（"加入"与"看"是不同能力，混用会顺带授予）、`note:*` 4 个（笔记是空间内一等资源，不能复用 `workspace:update`）、`document:*` 的拆分为 upload/read/download/delete |
| 2 | 第一层用 `hasPermission` 做资源判定 | 只判身份 | 见 §2.2：失败码冲突 + 规则不应有两处实现 |
| 3 | 拥有者以 `role='OWNER'` 在成员表 | 只记在 `workspace.owner_id` | 见 §4：避免两份必须同步的真相 |
| 4 | `workspace_task` / `document_version` 表 | 不建 | 这两个实体所属的能力（任务板、版本树）在本阶段既没有接口也没有状态机。先建表等于先把"我们支持这两件事"写进 schema |
| 5 | `PUBLIC_READONLY` 可见性 | 只有 `PRIVATE` / `TEAM` | 见 §9.1 |
| 6 | 成员表带 `member_count` 计数列 | 不设，读时 `COUNT` | 计数列在鉴权相关的表上引入丢更新风险，而本阶段没有读路径必须依赖它 |

### 9.1 关于 `PUBLIC_READONLY`（值得单独说明）

项目硬约束是**私有内容绝不自动公开**。而"公开只读"并不是把 `visibility` 改一个值那么简单：
它同时需要公开预览（谁能看到什么）、撤回（撤回后在他人的缓存与搜索结果中如何失效）、
被搜索引擎索引的取舍，以及"公开的是整个空间还是空间内某几篇文档"这一层独立策略。

**在本阶段把它作为一个可选值放进枚举，会让人以为这条链路已经存在。**
枚举里放进一个尚未有代码支持的取值，比缺一个取值危险得多。

同样地，`document` 表没有 `visibility` 列 —— 本阶段不存在"空间内单个文档公开"的形态。

---

## 10. 验证

### 10.1 单元测试

| 测试 | 断言的内容 |
|---|---|
| `WorkspaceActionTest` | 权限矩阵**逐格**断言；`NONE` 永不允许；`ownedBySelf` 不会成为 `NONE` 的通行证 |
| `WorkspaceScopeSqlTest` | SQL 改写的全部形状（见 §5.3 的表），含空集 → `1 = 0`、`?AND` 隐患、取值只内联数字 |
| `WorkspaceScopeCoverageTest` | 每条私有查询显式回答"是否过滤"；`@Unscoped` 理由非空；XML 与接口一一对应 |
| `LocalFileObjectStorageTest` | 存取删、路径穿越拒绝且根目录外无残留文件、大小不符失败、缺内容 503、自动建目录 |
| `ApplicationConfigStructureTest` | `app.workspace.*` 全部键在配置文件里真实存在（配置绑不上不会报错，靠这条断言兜住） |

### 10.2 集成测试（`WorkspaceAuthorizationIT`）

真实 HTTP + 真实 MySQL，五个维度：

| 分组 | 覆盖 |
|---|---|
| 不可见即不存在（404） | 匿名 401；非成员对空间/笔记/文档/成员的读、改、删、下载**全部** 404；跨空间访问他人资源的 `public_id` 一律 404 |
| 可见但无权限（403） | 普通成员改设置/删空间/移除他人/改他人角色 → 403；成员在空间内仍可正常读写 |
| 删除的归属 | 成员删自己的可以、删他人的不行；拥有者与管理员删任何一条都可以；`deletableByMe` 与实际删除结果**一致** |
| 成员与邀请 | 拥有者出现在成员列表首位；拥有者不能退出（40023）；退出/被移除后立即失去访问；非受邀人兑换 404；过期 40020；重复兑换 409；撤销跨空间 404 |
| 第三层防线（数据范围） | **绕过服务层**直接调 Mapper：绑定空集 → `1 = 0`；绑定到错误的空间 → 影响 0 行；`unscoped` 不吞异常 |

第五组的写法值得说明：它**不经过服务层**，因为服务层的判定与它是两道不同的防线。
若测试走服务层，被拒绝的原因永远是第二层 —— 而第三层**从未被真正执行过**。
这也解释了为什么 `unscoped` 的存在是必需的：不先关掉过滤，测的是过滤而不是兜底。

### 10.3 命令

```bash
./mvnw -B clean verify
```

---

## 11. 已知限制

- **`parse_status` 只会是 `PENDING`。** 内容解析、分块、`READY`/`FAILED` 的流转属 Phase 05。
- **没有通知。** 邀请码需要邀请人自己转达给被邀请人，系统不代发消息（属 Phase 10）。
  列表页会显示邀请码，就是为了这件事。
- **没有转让空间。** 拥有者不能退出（`40023`），当前只能删除空间或保持原样。
- **`TEAM` 可见性与 `PRIVATE` 在权限判定上没有区别。** 两者都只有成员能看到内容。
  `TEAM` 目前只表达"这个空间是给团队用的"这一意图，为将来的发现与推荐留位置。
  这是刻意的：让一个取值先出现、但没有与之对应的**新行为**，
  比让一个取值先出现、并假装它有新行为要好。
- **本地磁盘存储没有配额与清理。** 删除文档只标记 `deleted_at`，字节仍留在磁盘上。
  真正的清理策略要与 Phase 05 的对象存储一起设计（生命周期规则、孤儿对象的回收）。
- **第三层防线只覆盖 `workspace_id` 这一个维度。** 它防的是"空间之间"的越权，
  防不了"同一空间内"的越权（那是第二层的职责）。
