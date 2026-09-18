-- ==========================================================================
-- V5 : workspace 域（Phase 04 — Workspace & Resource Authorization）
-- ==========================================================================
-- 纪律（docs/00-工程规约.md §15.8）：
--   · V1–V4 已应用，禁止再改；本次变更全部写在 V5
--   · 字符集 utf8mb4 / 排序 utf8mb4_0900_ai_ci，与库级默认保持一致
--   · 主键 BIGINT UNSIGNED；对外暴露的实体另有 public_id CHAR(22)（防 ID 遍历）
--   · 时间列统一 DATETIME(3)
--   · 表结构变更必须在末尾更新 app_metadata 的 schema.baseline
-- ==========================================================================

-- ==========================================================================
-- 相对 docs/03-domain-permission.md §8 的**刻意偏离**（逐条就地给理由）
-- ==========================================================================
-- 1) 只建 5 张表：workspace / workspace_member / workspace_invite / document / note。
--    **不建** workspace_task 与 document_version。
--    理由：这两个实体所属的能力（任务板、文档版本树）在本阶段既没有接口也没有
--    状态机。先建表等于先把"这两件事我们支持"这句话写进 schema ——
--    而实际没有任何代码会去读写它们，表里的数据只会随时间变成无法解释的遗迹。
--    等真正要做的阶段再新增迁移，成本只是几行 DDL。
--
-- 2) workspace.visibility 只有 PRIVATE | TEAM，**不含** PUBLIC_READONLY。
--    理由：项目硬约束是"私有内容绝不自动公开"。公开只读并不是把 visibility
--    改一个值那么简单 —— 它同时需要公开预览、撤回、被搜索引擎索引的取舍、
--    以及"公开的是空间还是空间内某几篇文档"这一层独立策略（docs/03 §10.5 第 3 条）。
--    在本阶段把它作为一个可选值放进 enum，会让人以为这条链路已经存在。
--
-- 3) **拥有者不重复写入 workspace_member**：owner_id 是"谁拥有该空间"的唯一事实来源，
--    workspace_member 只存 MEMBER / ADMIN 两种被授予的角色。
--    理由：若拥有者同时以 role='OWNER' 出现在成员表里，就有了两份必须同步的真相。
--    把拥有者移出成员、转让空间、级联清理——任何一次漏写都会造成
--    "owner_id 说是他、成员表说不是"的静默不一致，而这种不一致恰好会落在鉴权判定上。
--    代价是"我能在哪些空间里"要在 SQL 里 UNION 一次 owner_id（见 WorkspaceMapper），
--    这个代价是一次确定性的 UNION，而不是一类偶发故障。
--
-- 4) **不设 member_count / document_count 等计数列**。
--    post 表上的 like_count / comment_count 是为"最热排序"和列表卡片准备的，
--    它们的存在是被读路径逼出来的，代码里因此专门处理了"计数原地自增避免并发丢更新"。
--    空间成员数与文档数没有这样的读路径必须依赖计数列，
--    读时 COUNT 一次即可（两者天然有界）。此处刻意不引入计数，
--    免得为了一个没人问的数字，先在鉴权相关的表上引入了丢更新。
--
-- 5) document **没有** visibility 列。理由同 2)：本阶段不存在"空间内单个文档公开"的形态。
--
-- 6) document.storage_key **允许 NULL**，但上传路径一定会写入它。
--    本阶段落地的是**本地磁盘存储**（LocalFileObjectStorage，经 ObjectStorage 端口接入），
--    因此上传与下载都是真实可用的链路，不是占位实现。
--    允许 NULL 的理由与"上传路径会不会写它"无关：它是为了让**历史行**在 schema 层面
--    是显式的 —— 字节接入之前登记的元数据、或被外部清理过内容的行，
--    它们的"没有内容可下载"是一个可以被查询表达的状态，而不是靠约定。
--    下载这类行返回 503 而不是 404：资源存在、权限也有，只是承载它的内容取不到。
-- ==========================================================================

-- --------------------------------------------------------------------------
-- workspace：空间主表
-- --------------------------------------------------------------------------
CREATE TABLE `workspace`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `public_id`   CHAR(22)        NOT NULL COMMENT '对外暴露的随机 ID，防 ID 遍历',
    `name`        VARCHAR(80)     NOT NULL,
    `description` VARCHAR(500)    DEFAULT NULL,
    `visibility`  VARCHAR(16)     NOT NULL DEFAULT 'PRIVATE'
        COMMENT 'PRIVATE | TEAM。用字符串而非数字：日志与 SQL 里可直接读懂',
    `owner_id`    BIGINT UNSIGNED NOT NULL
        COMMENT '拥有者。是"谁拥有该空间"的唯一事实来源，刻意不重复写入 workspace_member',
    `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted_at`  DATETIME(3)     DEFAULT NULL COMMENT '非空即已删除。软删除：成员行、文档与审计仍引用该空间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workspace_public_id` (`public_id`),
    -- "我拥有的空间"列表页。列顺序与 WHERE + ORDER BY 一致，避免 filesort
    KEY `idx_workspace_owner` (`owner_id`, `created_at` DESC),
    -- RESTRICT 而不是 CASCADE：账号被硬删除时，静默抹掉他拥有的空间
    -- （连同其中所有成员与文档）比直接报错危险得多。账号在本项目里是软删除的，
    -- 因此这条 RESTRICT 实际上只在有人执行物理删除时才可能触发。
    CONSTRAINT `fk_workspace_owner` FOREIGN KEY (`owner_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='协作空间';

-- --------------------------------------------------------------------------
-- workspace_member：空间成员（(user, workspace) 上的关系属性，不是全局角色）
-- --------------------------------------------------------------------------
-- docs/03 §10.1 的硬约束：WORKSPACE_MEMBER / WORKSPACE_ADMIN **不是**平台角色。
-- 它们放在这里而不是 role 表，是因为它们是 (user, workspace) 这个二元组的属性。
-- 一旦塞进全局角色表，立刻产生"在 A 空间是管理员，于是能管 B 空间"的越权。
--
-- 主键用 (workspace_id, user_id) 复合，与 user_role / post_tag 的既有写法一致：
-- 这张表没有独立的身份，它的身份就是那个二元组。多余的自增列只会多一个可被误用的 ID。
-- --------------------------------------------------------------------------
CREATE TABLE `workspace_member`
(
    `workspace_id` BIGINT UNSIGNED NOT NULL,
    `user_id`      BIGINT UNSIGNED NOT NULL,
    `role`         VARCHAR(16)     NOT NULL DEFAULT 'MEMBER'
        COMMENT 'MEMBER | ADMIN。拥有者不在此表中，见 workspace.owner_id',
    `joined_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`workspace_id`, `user_id`),
    -- 第三层防线（docs/03 §10.3）的唯一热路径：给定 user_id 反查"他被授权哪些空间"。
    -- 列顺序把 user_id 放在前，使这条反查走覆盖索引而不是先扫 workspace_id。
    KEY `idx_workspace_member_user` (`user_id`, `workspace_id`),
    CONSTRAINT `fk_workspace_member_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_workspace_member_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='空间成员与空间内角色';

-- --------------------------------------------------------------------------
-- workspace_invite：定向邀请
-- --------------------------------------------------------------------------
-- 形态是**定向邀请码**而不是"公开邀请链接"：
-- 邀请码只在被邀请人手里有效，非受邀人凭同一串码兑换返回 404。
-- 理由：公开链接一旦泄漏到群聊或搜索引擎，就等于把"谁能进这个私有空间"的决定权
-- 交给了任何一个拿到链接的人；而定向邀请把决定权留在发起邀请的成员手上。
--
-- 用"到期时间"而非"是否过期"布尔位：判定过期是一次时间比较，不需要任何定时任务。
-- 这与 user_credential.locked_until 的既有取舍一致。
-- --------------------------------------------------------------------------
CREATE TABLE `workspace_invite`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `code`         CHAR(22)        NOT NULL COMMENT '邀请码，与 public_id 同构（22 位 62 进制随机串）',
    `workspace_id` BIGINT UNSIGNED NOT NULL,
    `inviter_id`   BIGINT UNSIGNED NOT NULL COMMENT '发起邀请的成员',
    `invitee_id`   BIGINT UNSIGNED NOT NULL
        COMMENT '被邀请人。定向邀请：邀请码只有本人可兑换，非受邀人兑换一律 404（不泄漏邀请码是否存在）',
    `role`         VARCHAR(16)     NOT NULL DEFAULT 'MEMBER' COMMENT '兑换后获得的角色',
    `status`       VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING | ACCEPTED | REVOKED',
    `expires_at`   DATETIME(3)     NOT NULL,
    `accepted_at`  DATETIME(3)     DEFAULT NULL,
    `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workspace_invite_code` (`code`),
    -- 空间内"待处理邀请"列表
    KEY `idx_workspace_invite_workspace` (`workspace_id`, `status`),
    -- "我的邀请"列表；也是兑换时的定位索引
    KEY `idx_workspace_invite_invitee` (`invitee_id`, `status`),
    -- 邀请行的生命周期完全依附于空间与被邀请人；它本身不是审计记录
    -- （审计在 audit_log 里，那里才是不可变轨迹），因此一律 CASCADE ——
    -- 否则账号被硬删除时会留下一批永远无法解释的待处理邀请。
    CONSTRAINT `fk_workspace_invite_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_workspace_invite_inviter` FOREIGN KEY (`inviter_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_workspace_invite_invitee` FOREIGN KEY (`invitee_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='空间定向邀请';

-- --------------------------------------------------------------------------
-- document：文档元数据 + 原始字节的登记
-- --------------------------------------------------------------------------
-- 本阶段交付元数据与原始字节（字节写本地磁盘，由服务端生成的键定位）。
-- **不做**：内容解析、分块、版本树。
--
-- name 是原始文件名，只用于展示，绝不参与任何路径拼接：
-- 用户上传的文件名是攻击者完全可控的输入，"../" 之类的构造在任何
-- "用文件名拼存储路径"的实现里都是路径穿越的入口。真实的存储键由服务端生成。
-- --------------------------------------------------------------------------
CREATE TABLE `document`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `public_id`     CHAR(22)        NOT NULL COMMENT '对外暴露的随机 ID，防 ID 遍历',
    `workspace_id`  BIGINT UNSIGNED NOT NULL,
    `uploader_id`   BIGINT UNSIGNED NOT NULL,
    `name`          VARCHAR(255)    NOT NULL COMMENT '原始文件名，仅展示用，不参与路径拼接',
    `mime_type`     VARCHAR(127)    NOT NULL,
    `size_bytes`    BIGINT UNSIGNED NOT NULL,
    `storage_key`   VARCHAR(512)    DEFAULT NULL
        COMMENT '存储键，由服务端生成（见 V5 偏离说明 6）。仅历史行允许为空',
    `sha256`        CHAR(64)        DEFAULT NULL COMMENT '内容哈希：完整性校验，以及将来的秒传去重',
    `parse_status`  VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING | PROCESSING | READY | FAILED。本阶段只会出现 PENDING',
    `parse_message` VARCHAR(300)    DEFAULT NULL COMMENT '解析失败原因，面向用户的简短说明，不含内部堆栈',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted_at`    DATETIME(3)     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_public_id` (`public_id`),
    KEY `idx_document_workspace` (`workspace_id`, `created_at` DESC),
    CONSTRAINT `fk_document_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_document_uploader` FOREIGN KEY (`uploader_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='空间文档元数据';

-- --------------------------------------------------------------------------
-- note：空间内协作笔记
-- --------------------------------------------------------------------------
-- 与 post 的关系：**同一个内容渲染边界**（ADR 0005），因此列结构与 post 同构 ——
-- body_md 是编辑的事实来源，body_html 是写入时渲染并净化的结果，summary 供列表页使用。
--
-- 为什么不复用 post 表加一个 workspace_id 可空列：
--   私有内容与公开内容一旦同表，权限过滤就必须出现在**每一次**查询里，
--   而任何一次遗漏都是一次越权读取。分表让"这条 SQL 会不会读到别人的私有内容"
--   这个问题在表名上就能回答。
--
-- author_id 与 updated_by 分开：笔记是协作的，任何成员都可能编辑，
-- 但"谁创建的"必须留下，否则删除权限无从判定。
-- --------------------------------------------------------------------------
CREATE TABLE `note`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `public_id`    CHAR(22)        NOT NULL COMMENT '对外暴露的随机 ID，防 ID 遍历',
    `workspace_id` BIGINT UNSIGNED NOT NULL,
    `author_id`    BIGINT UNSIGNED NOT NULL COMMENT '创建者。删除与改标题的权限判定以此为据',
    `updated_by`   BIGINT UNSIGNED NOT NULL COMMENT '最后一次编辑者',
    `title`        VARCHAR(200)    NOT NULL,
    `summary`      VARCHAR(300)    NOT NULL
        COMMENT '列表卡片摘要，写入时从正文派生（ADR 0005）。列表页因此不需要读出 MEDIUMTEXT',
    `body_md`      MEDIUMTEXT      NOT NULL COMMENT 'Markdown 原文，编辑时的事实来源',
    `body_html`    MEDIUMTEXT      NOT NULL COMMENT '服务端渲染 + 白名单净化后的 HTML，读路径直接返回',
    `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted_at`   DATETIME(3)     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_note_public_id` (`public_id`),
    KEY `idx_note_workspace` (`workspace_id`, `updated_at` DESC),
    CONSTRAINT `fk_note_workspace` FOREIGN KEY (`workspace_id`) REFERENCES `workspace` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_note_author` FOREIGN KEY (`author_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_note_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `user` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='空间内协作笔记';

-- ==========================================================================
-- 权限点种子（workspace 域）
-- ==========================================================================
-- V2 末尾的注释承诺"Phase 04 引入 workspace:*"。这里兑现它。
--
-- 两条自律，与 V2 里"不提前种权限"的理由是同一个：
--
-- 1) **只种本阶段真的有代码在检查的权限点。** 下面 16 个码，每一个都在
--    workspace/api 的某个方法上被 @PreAuthorize 引用（第一层防线）。
--    没有 @PreAuthorize 引用的权限行就是装饰 —— 它让人以为权限配好了。
--
-- 2) **顺带更正一处 V2 里已经过期的承诺。** V2 写的是"Phase 03 引入 post:* / comment:*"，
--    但 V3 并没有种这两个码，而且 Phase 03 的社区接口至今**不使用**权限点做鉴权 ——
--    它们靠的是服务层的归属校验（改/删帖子要求作者本人）。
--    这个不一致是刻意的结果而不是遗漏：给社区接口补上 post:create 之类的码，
--    在当前只有 USER 一个角色的前提下不会改变任何一次判定的结果，
--    却会让权限表里多出一批"存在但决定不了任何事"的行。
--    因此本条不在 V5 里补种；等真正出现第二个能发帖的角色（Phase 10 的运营角色）时，
--    再连同"谁能发帖"这条策略一起种下去。V2 那句注释以本段为准。
--
-- 相对 docs/03 §10.2 的**补充**（原清单 11 个码，此处 16 个）：
--   · workspace:join       —— 接受邀请加入空间。它与 workspace:read 不是同一个能力：
--                             read 是"看得到"，join 是"改成员关系"。混用一个码，
--                             将来任何一个只想给"看"的角色都会顺带获得"加入"。
--   · note:create/read/update/delete —— 笔记是空间内的一等资源。
--                             它的写权限不能复用 workspace:update ——
--                             后者是"改空间设置"，只有拥有者具备，而普通成员必须能写笔记。
--
-- 显式指定 id：与 V2 里 role 的做法一致，让绑定关系不依赖自增顺序。
-- ==========================================================================
INSERT INTO `permission` (`id`, `code`, `description`)
VALUES (1, 'workspace:create', '创建空间'),
       (2, 'workspace:read', '读取空间基本信息与成员列表'),
       (3, 'workspace:update', '修改空间设置（名称、描述、可见性）'),
       (4, 'workspace:delete', '删除空间'),
       (5, 'workspace:member:invite', '邀请他人加入空间'),
       (6, 'workspace:member:remove', '移除空间成员'),
       (7, 'workspace:member:role:update', '修改空间成员在空间内的角色'),
       (8, 'workspace:join', '接受邀请加入空间'),
       (9, 'note:create', '在空间内创建笔记'),
       (10, 'note:read', '读取空间内笔记'),
       (11, 'note:update', '编辑空间内笔记'),
       (12, 'note:delete', '删除空间内笔记'),
       (13, 'document:upload', '向空间上传文档'),
       (14, 'document:read', '读取空间文档元数据与列表'),
       (15, 'document:delete', '删除空间文档'),
       (16, 'document:download', '下载空间文档原文件');

-- 全部授予基础角色 USER。
--
-- 这不是"人人都是管理员"：上面这些码回答的是**身份有没有这类能力**，
-- 而"这条数据能不能给他看"由第二层（资源级判定）与第三层（数据范围过滤）回答。
-- 例如 workspace:delete 授予了 USER，但只有空间的拥有者能删掉它 ——
-- 判定在 AuthorizationService 里，而不是在这一行里。
-- 把 workspace:delete 只授给某个"管理员角色"，反而会让"拥有者删自己的空间"
-- 变成一件需要额外角色才能做的事，那不是我们想要的模型。
INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT 1, `id`
FROM `permission`
WHERE `code` LIKE 'workspace:%'
   OR `code` LIKE 'note:%'
   OR `code` LIKE 'document:%';

-- --------------------------------------------------------------------------
-- 更新 schema 基线标记
-- --------------------------------------------------------------------------
-- 语义见 V4：该值等于"已应用到的最新迁移版本号"。
-- 不更新它，/api/v1/system/info 会继续报 V4 —— 一个看起来正常、实际错误的结论。
-- --------------------------------------------------------------------------
UPDATE `app_metadata`
SET `meta_value` = 'V5'
WHERE `meta_key` = 'schema.baseline';
