# 0002 · 技术基线锁定为 Spring Boot 4.1.1 与官方 MyBatis

| 字段 | 内容 |
|---|---|
| **状态** | Accepted |
| **日期** | 2026-09-17 |
| **决策者** | 项目负责人 |
| **相关文档** | `docs/00-工程规约.md` §3.4 · `docs/11-开发环境.md` §3 · `docs/architecture.md` §7 |

---

## Context

项目启动于 2026 年 9 月，需要为后端选定框架基线。相关事实：

1. Spring Boot **3.5.x 已进入停止维护阶段**（end-of-life），不再是安全的选择 ——
   继续使用意味着后续所有安全补丁都要自行回移。
2. Spring Boot **4.1.1** 是当前的维护版本线，带来 Spring Framework 7.0.9 与 Spring Security 7.1.1。
3. Boot 4 相对 Boot 3 有若干**破坏性变更**，会影响选型：
   - `spring-boot-starter-web` 已弃用，改用 `spring-boot-starter-webmvc`；
   - **Flyway 自动配置从 `spring-boot-autoconfigure` 中拆出**，必须显式引入
     `spring-boot-starter-flyway`，否则迁移**静默不执行**；
   - 部分 API 签名变化（例如方法参数校验的 `getParameterValidationResults()`）。
4. 国内生态中 `MyBatis-Plus` 使用广泛，但截至本决策时**没有适配 Spring Boot 4 的版本**。

同时本项目对「框架升级带来的不确定性」有明确警觉：
规约要求技术基线必须**经实际验证**，不能依据记忆或推测（`docs/00-工程规约.md` §3.4）。

## Decision

**采用 Spring Boot 4.1.1 作为后端基线，使用官方 MyBatis 作为数据访问层。**

配套的具体决定：

| 项 | 选择 | 版本 |
|---|---|---|
| 框架 | Spring Boot | 4.1.1 |
| HTTP 层 | `spring-boot-starter-webmvc`（**非** `-web`） | 由 Boot 管理 |
| 数据库迁移 | `spring-boot-starter-flyway` + `flyway-mysql` | 12.4.0 |
| 数据访问 | `mybatis-spring-boot-starter`（官方） | 4.1.0 |
| JDBC 驱动 | `mysql-connector-j` | 由 Boot 管理 |
| API 文档 | `springdoc-openapi` | 3.1.1 |
| 架构断言 | ArchUnit | 1.5.0 |
| 集成测试容器 | Testcontainers | 2.0.5 |
| JDK | Java | 21 |

**验证方式（本次实际执行，非推测）**：
- 通过 `javap` 反编译确认 Spring Framework 7 的校验 API 实际签名；
- 通过实际启动 + 集成测试确认 Flyway 在缺少 `spring-boot-starter-flyway` 时**不会执行迁移**
  （现象为 `Table 'camphub.app_metadata' doesn't exist`），补上依赖后迁移正常；
- 通过检索确认 MyBatis-Plus 无 Boot 4 适配版本。

## Alternatives

### 备选 A：Spring Boot 3.5.x

**得到**：生态成熟，MyBatis-Plus 可用，网上示例与排错资料远多于 Boot 4，踩坑成本最低。

**代价**：
- 3.5 已 EOL，**后续不再有安全补丁**，会持续累积未修补漏洞；
- 项目在设计上要做 RAG / Agent / 搜索，生命周期至少数年，从起跑线就落后一个大版本，
  迁移窗口只会越来越难找；
- 规约明确要求技术基线不采用已停止维护的版本。

**否决理由**：用「现在省事」换「长期背负一个不会再更新安全补丁的框架」，不划算。
**但**：Boot 4 的真实破坏性变更必须先被验证清楚，而不是盲目跟进 —— 这正是下方「负面」一节存在的原因。

### 备选 B：Spring Boot 4.1.1 + MyBatis-Plus

**得到**：少写大量 CRUD 样板代码，`BaseMapper` / 条件构造器能显著缩短简单查询的代码量。

**代价**：
- **没有适配 Boot 4 的版本**。要用它就必须回到 Boot 3.5，即备选 A 的全部问题；
- 或者自行 fork / 打补丁适配，等于把一个第三方 ORM 的维护责任接到自己身上；
- 本项目的数据访问以**明确 SQL** 为主（涉及权限过滤、多表关联、检索语义），
  条件构造器的动态拼装能力用不上，而它带来的「SQL 藏在 Java 里、难以审查」的成本却会承担。

**否决理由**：为一个用不上的能力，付出「框架停在 EOL 版本」或「自维护 ORM」的代价，不成比例。

### 备选 C：Spring Boot 4.1.1 + Spring Data JPA / Hibernate

**得到**：官方一等公民，Boot 4 支持最完整，实体映射自动化程度高。

**代价**：
- ORM 的隐式行为（N+1、脏检查、懒加载时机、flush 顺序）在**需要精确控制 SQL** 的场景下
  是负担而非帮助，而本项目 Phase 07 的检索权限过滤、Phase 09 的性能调优都要求精确控制；
- 团队对 MyBatis 的 SQL 可控心智模型更熟悉，排错时能直接看到执行的 SQL。

**否决理由**：并非 JPA 不好，而是本项目的数据访问形态（权限前置过滤 + 多路召回 + 性能敏感）
更适合显式 SQL。这是一个**场景匹配**判断，不是优劣判断。

### 备选 D：Spring Boot 4.1.1 + jOOQ

**得到**：类型安全 SQL，编译期校验表名与字段，重构友好。

**代价**：需要从数据库 schema 生成代码，为构建增加一个代码生成环节与相应的缓存/失效管理；
对当前表数量（1 张）而言收益远小于成本。

**否决理由**：**时机问题**，不是能力问题。若后续 SQL 复杂度显著上升，可重新评估并新增 ADR。

## Consequences

### 正面

- 基线处于维护线上，可持续获得安全补丁，无需在项目中期做框架大版本迁移。
- 官方 MyBatis 与 Boot 4 版本对齐，依赖链清晰，无需维护 fork。
- SQL 显式可见，与「权限过滤必须前置到检索阶段」「性能数据必须可复现」两条规约天然契合。
- 提前踩清 Boot 4 的破坏性变更（webmvc 重命名、Flyway starter 拆分），
  这些经验已沉淀进 `docs/11-开发环境.md`，后续 Phase 不会重复踩。

### 负面 / 代价

- **生态资料少于 Boot 3**：遇到问题时搜索引擎给出的答案多数面向 Boot 3，
  需要自行判断适用性。缓解方式：**一切以实际 POM / jar 内容为准**，不依赖记忆与博客。
- **依赖库兼容性需要逐个验证**：Boot 4 较新，第三方库（如 springdoc）的适配版本需要确认。
  缓解方式：每次引入新依赖前先确认其 Boot 4 支持情况。
- **CRUD 样板代码更多**：没有 MyBatis-Plus 的 `BaseMapper`，基础增删改查需要手写 SQL。
  这是刻意接受的成本 —— 换取的是 SQL 完全可控。
- **破坏性变更会持续出现**：Boot 4.x 后续小版本仍可能有调整，
  因此测试体系必须能快速暴露问题（这也是 Phase 01 建立三层测试的原因之一）。

### 中性但有长期影响

- **版本号集中由 parent 管理**，业务依赖尽量不写死版本；
  只有 Boot 未管理的库（springdoc、ArchUnit）才显式指定版本，并在此 ADR 中登记。
- **引入任何新依赖前需确认 Boot 4 兼容性**，并更新 `docs/11-开发环境.md` 的依赖基线表。

---

## 复核触发条件

1. MyBatis-Plus（或其他被否决的方案）发布正式的 Boot 4 适配版本，且出现 SQL 样板代码显著拖慢开发的实证。
2. 官方 MyBatis 在 Boot 4 上出现无法绕过的兼容问题。
3. 出现必须使用 ORM 隐式能力的场景（例如复杂对象图的部分更新）。

**在此之前，维持本基线。**
