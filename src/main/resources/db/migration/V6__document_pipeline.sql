-- ==========================================================================
-- V6 —— Phase 05：对象存储与文档处理流水线
-- ==========================================================================
-- 本迁移做三件事：
--   1) 给 document 补上"处理结果"相关的列（V5 只建了元数据，字段刻意留到现在）；
--   2) 新增 document_task —— 异步任务的持久化队列；
--   3) 新增 document_chunk —— 解析产出的分块。
--
-- --------------------------------------------------------------------------
-- 相对 docs/03 §9.2 表清单的偏离（两处，逐条理由）
-- --------------------------------------------------------------------------
-- 偏离 1：**不建 document_version**。
--   docs/03 为它规划的列是 (document_id, version, storage_key, parser_version,
--   chunk_strategy)。四条里有三条（parser_version / chunk_strategy / 每个版本
--   一个 storage_key）服务的是"同一份文档反复重解析、且要保留历史版本可比对"
--   这个场景。本阶段的重解析是**就地重跑**：同一份字节、同一个 storage_key，
--   解析失败重试不产生新字节，因此也不产生新版本。
--   先把一个没有写入方的版本表建出来，等于给后来者一个"这里应该有多版本"的
--   错误暗示，而它实际只会是一张空表。真正需要版本比对时（例如用户可以在
--   同名文档的多次修订间切换），再造 V-后面的迁移，并把回填策略一并想清楚。
--   parser_version 这一条不丢：它落在 document 上（见下），因为"这份文档是
--   用哪个解析器版本产出的"是一个**当前状态**，而不是一段历史。
--
-- 偏离 2：**document_task / document_chunk 不在 docs/03 的清单里**。
--   docs/03 §8.3 写的是"Document 的解析管道经 MQ，状态机驱动"。Phase 05 的计划
--   明确"暂不强制 MQ，先找出现有异步方案的瓶颈"，因此管道落在数据库里。
--   队列一旦落库，就必须有一张表承载"待处理 / 处理中 / 成功 / 失败 + 重试次数 +
--   下次可见时间"——这是任务的**状态**，不是可以塞进 document 行的附属属性
--   （一张文档同时可能有 PARSE 与 CLEANUP 两件事在排队，一行装不下）。
--   chunk 表则是阶段验收定义的一部分：READY 的含义是"可被检索的最小单元已就绪"。
--
-- --------------------------------------------------------------------------
-- 为什么 document_task 上不写 workspace_id（而 document_chunk 上写）
-- --------------------------------------------------------------------------
-- 第三层防线（MyBatis 拦截器按 @ScopedTable 追加 `workspace_id IN (...)`）的前提是
-- 表上有 workspace_id。两张新表对此的处理**刻意不同**：
--
--   · document_task：**不加**。它的读取方只有 worker，而 worker 是以
--     **系统身份**跨空间领取任务的 —— 它一次要处理所有空间的待办。
--     给它加 workspace_id 然后让 worker 用 @Unscoped 绕开，只会多一列无人使用的
--     冗余数据；真正约束 worker 的不是"能看到哪些空间"，而是"任务行本身的租约"。
--     因此这张表的每条查询都会显式标注 @Unscoped 并写明这条理由，
--     由 WorkspaceScopeCoverageTest 强制（漏标即构建失败）。
--
--   · document_chunk：**加**，且是刻意冗余。文档的 workspace_id 在文档的整个
--     生命周期内不可能改变（没有"把文档移到另一个空间"这个操作），因此这份冗余
--     是**不可变冗余**，不存在失同步的可能。换来的是：将来 Phase 07 做"空间内检索"
--     时，那条查询天然带 workspace_id 前缀，第三层防线能直接压在它上面，
--     而不必依赖"记得 JOIN 一次 document 才知道范围"。
--     这与 V5 里 note 与 post 分表是同一条理由：**这条 SQL 会不会读到别人的私有内容，
--     应该在表名上就能回答**。
-- ==========================================================================

-- --------------------------------------------------------------------------
-- document：补上处理结果列
-- --------------------------------------------------------------------------
-- 全部带 NOT NULL DEFAULT，因此对已有行是安全的加列（不需要回填脚本）：
-- 已存在的文档在本迁移之后仍然是 parse_status = 'PENDING'，会被 worker 当作
-- 待处理文档自动捡起来 —— 这正是我们想要的，V5 期间上传的文档不会变成孤儿。
-- --------------------------------------------------------------------------
ALTER TABLE `document`
    ADD COLUMN `parse_progress` TINYINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '解析进度 0~100。由 worker 分段更新，供前端轮询展示',
    ADD COLUMN `chunk_count` INT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '产出的分块数。仅在 parse_status = READY 时有意义',
    ADD COLUMN `text_length` INT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '抽取出的正文字符数。0 且有内容 = 该格式没有可抽取的文本层（如扫描件 PDF）',
    ADD COLUMN `parser_version` VARCHAR(32) DEFAULT NULL
        COMMENT '产出当前结果的解析器版本。解析器升级后可据此筛出需要重跑的文档',
    ADD COLUMN `parsed_at` DATETIME(3) DEFAULT NULL COMMENT '最近一次解析成功的时间';

-- 更新三处已经过期的列注释。V5 写下它们时 Phase 05 还没实现，
-- 因此当时写的是"本阶段只会出现 PENDING"这类临时表述 —— 注释留在库里，
-- 下次有人用 `SHOW FULL COLUMNS` 看这张表，读到的必须是当前的事实。
ALTER TABLE `document`
    MODIFY COLUMN `parse_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING | PROCESSING | READY | FAILED。状态迁移只由 document_task 的 worker 推动',
    MODIFY COLUMN `storage_key` VARCHAR(512) DEFAULT NULL
        COMMENT '存储键，由服务端生成。NULL 表示字节已从存储清除（删除流程完成的标志）',
    MODIFY COLUMN `sha256` CHAR(64) DEFAULT NULL
        COMMENT '内容哈希：完整性校验。不做秒传去重，理由见本文件末尾的取舍说明';

-- --------------------------------------------------------------------------
-- document_task：持久化的异步任务队列
-- --------------------------------------------------------------------------
-- 形态是"一张表 + 轮询 worker"，不是 MQ。这是 Phase 05 的明确决策，
-- 理由与代价都写在 docs/document-pipeline.md 里，这里只记结构与约束。
--
-- 三处设计要点：
--
-- 1) UNIQUE KEY (document_id, task_type) —— **幂等的落脚点**。
--    同一份文档的同一类任务在库里最多只有一行。于是"重复上传"（用户网络重试）、
--    "重复入队"（应用层重入）都不会产生第二个解析任务：第二次入队撞唯一键，
--    按"已存在则不动"处理。没有这个键，"幂等"就只是应用层一句无法被验证的承诺。
--
--    重试走的是**重置同一行**（status 回 PENDING、attempt_count 递增），
--    而不是插入新行 —— 那样会撞唯一键，而"插入失败"显然不是重试的正确表达。
--
-- 2) lease_owner + lease_expires_at —— **崩溃恢复**。
--    worker 领走任务时写自己的实例标识与一个到期时间。进程被 kill 时任务留在
--    RUNNING，不会有任何代码去改它；回收靠另一个 worker 扫到"RUNNING 且租约已过期"
--    再放回队列。用"租约到期"而不是"心跳失败计数"，是因为前者只需一次时间比较，
--    不依赖任何组件记住上一位 owner 的状态。
--
-- 3) idx_document_task_dispatch (status, next_attempt_at) —— 领取路径的索引。
--    领取语句的 WHERE 是 status = 'PENDING' AND next_attempt_at <= NOW()，
--    列顺序与它一致；接在后面的 ORDER BY next_attempt_at 因此也走同一棵索引。
--
-- 不设 priority 列：当前没有"哪类任务更紧急"的证据，加一个恒为默认值的排序列
-- 只会让领取语句多一个不起作用的排序项。等真的出现需要插队的场景再加。
-- --------------------------------------------------------------------------
CREATE TABLE `document_task`
(
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `document_id`      BIGINT UNSIGNED NOT NULL,
    `task_type`        VARCHAR(16)     NOT NULL DEFAULT 'PARSE'
        COMMENT 'PARSE | CLEANUP。CLEANUP 负责删除后把字节真正从存储里清掉',
    `status`           VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING | RUNNING | SUCCEEDED | FAILED',
    `attempt_count`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '已尝试次数，包含当前这次',
    `max_attempts`     INT UNSIGNED    NOT NULL DEFAULT 3
        COMMENT '上限。达到上限后停在 FAILED，等人工重试，不再自动重试',
    `next_attempt_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '下次可见时间。失败重试时按指数退避往后推，退避实现见 worker',
    `lease_owner`      VARCHAR(64)     DEFAULT NULL COMMENT '持有租约的 worker 标识（主机名 + 实例序号）',
    `lease_expires_at` DATETIME(3)     DEFAULT NULL
        COMMENT '租约到期时间。过期的 RUNNING 任务会被回收重排，这是崩溃恢复的唯一依据',
    `last_error`       VARCHAR(500)    DEFAULT NULL COMMENT '最近一次失败原因，面向运维；不写入 document.parse_message',
    `started_at`       DATETIME(3)     DEFAULT NULL,
    `finished_at`      DATETIME(3)     DEFAULT NULL,
    `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_task_document_type` (`document_id`, `task_type`),
    KEY `idx_document_task_dispatch` (`status`, `next_attempt_at`),
    KEY `idx_document_task_lease` (`status`, `lease_expires_at`),
    CONSTRAINT `fk_document_task_document` FOREIGN KEY (`document_id`) REFERENCES `document` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='文档处理任务队列（数据库实现，非 MQ）';

-- --------------------------------------------------------------------------
-- document_chunk：解析产出的分块
-- --------------------------------------------------------------------------
-- 分块是文档"可被检索的最小单元"。本阶段没有 AI 消费者（Phase 08 已从计划中
-- 移除），但 Phase 07 的空间内检索需要它 —— 那是**命中高亮的粒度**：
-- 没有分块，"这份文档里哪一段匹配"就只能答"整篇匹配"，而对一份 50 页的 PDF
-- 来说那个答案没有用。
--
-- UK (document_id, ordinal) 不只是唯一约束，它是**重试幂等的工具**：
-- 重跑解析时先按 document_id 删掉旧块再插入，唯一键保证"删漏了"会被立刻发现，
-- 而不是悄悄留下两套编号相同的块，让检索结果出现重复。
--
-- 不建 FULLTEXT 索引：Phase 07 的检索要走独立的搜索栈（MySQL 基线 + ES 双跑，
-- 见 docs/12-phase-plan.md）。现在建一个 ngram 全文索引，到 Phase 07 会被推开，
-- 属于"提前实现"。
-- --------------------------------------------------------------------------
CREATE TABLE `document_chunk`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `document_id`  BIGINT UNSIGNED NOT NULL,
    `workspace_id` BIGINT UNSIGNED NOT NULL
        COMMENT '不可变冗余（文档不跨空间移动），用于让第三层防线可直接作用于本表',
    `ordinal`      INT UNSIGNED    NOT NULL COMMENT '块序号，自 0 连续递增，无空洞',
    `heading`      VARCHAR(500)    DEFAULT NULL
        COMMENT '所属标题路径，如 "部署 > 环境要求"。无标题层级时为空',
    `content`      MEDIUMTEXT      NOT NULL,
    `char_count`   INT UNSIGNED    NOT NULL COMMENT '字符数。不用 token 数：本阶段没有 tokenizer，估出来的值是假的',
    `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_chunk_ordinal` (`document_id`, `ordinal`),
    KEY `idx_document_chunk_workspace` (`workspace_id`, `document_id`),
    CONSTRAINT `fk_document_chunk_document` FOREIGN KEY (`document_id`) REFERENCES `document` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_document_chunk_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='文档解析分块（可检索的最小单元）';

-- ==========================================================================
-- 权限点种子（Phase 05 新增 1 个）
-- ==========================================================================
-- 自律与 V5 相同：**只种真的有代码在检查的权限点**。
-- 下面这个码被 DocumentController#retryParse 上的 @PreAuthorize 引用。
--
-- 为什么"重新解析"不复用 document:upload：
--   两者在能力上的区别不是有意为之的细分，而是真实的差异 ——
--   上传是"往空间里加一份新东西"，代价与影响都局限于自己；
--   重新解析会**替换这份文档现有的可检索内容**（旧分块先删后写），
--   并且占用解析资源。混用一个码，将来任何"允许上传但不允许重跑解析"的
--   角色划分都会立刻失效，而失效的形式是"权限比预期更宽"。
--   这与 V5 里"note:* 不复用 workspace:update"是同一条理由。
--
-- 显式指定 id（接 V5 的 16）：让绑定关系不依赖自增顺序。
-- ==========================================================================
INSERT INTO `permission` (`id`, `code`, `description`)
VALUES (17, 'document:retry', '重新解析空间文档');

-- 与 V5 同一条纪律：授予基础角色 USER。
-- 它回答的是"这个身份有没有这类能力"，而"这条文档能不能给他重解析"
-- 由第二层判定（DocumentService#retryParse 里的归属校验：普通成员只能重试
-- 自己上传的，拥有者与管理员可以处理任何一条），第三层则保证查询范围。
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT 1, `id`
FROM `permission`
WHERE `code` = 'document:retry';

-- --------------------------------------------------------------------------
-- 更新 schema 基线标记
-- --------------------------------------------------------------------------
-- 语义同 V4 / V5：该值等于"已应用到的最新迁移版本号"。
-- 不更新它，/api/v1/system/info 会继续报 V5 —— 一个看起来正常、实际错误的结论。
-- --------------------------------------------------------------------------
UPDATE `app_metadata`
SET `meta_value` = 'V6'
WHERE `meta_key` = 'schema.baseline';

-- ==========================================================================
-- 本迁移**刻意没有做**的两件事
-- ==========================================================================
-- 1) 不做内容级秒传去重（sha256 唯一索引 + 复用已有存储对象）。
--    它能省的是一次上传，代价是把删除从"删掉这一行的字节"变成"是否还有别的行
--    引用同一个字节"的引用计数问题 —— 而在 sha256 上建唯一索引会让"两个人各自
--    上传同一份公开课件"变成一次跨用户的存储冲突（还泄漏了"这份文件已有人传过"
--    这个事实）。当前没有证据显示重复上传是真实痛点，不做。
--
-- 2) 不在本阶段做 outbox 表。
--    Phase 05 的异步是"任务表 + 轮询"，它在**同一个本地事务**里写业务数据与任务行，
--    因此不需要 outbox 那套"业务写入与事件发布原子化"的补偿机制。
--    outbox 是 Phase 06（采集）与跨模块事件才需要的东西，届时随该阶段一并引入。
-- ==========================================================================
