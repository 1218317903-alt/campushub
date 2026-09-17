-- ==========================================================================
-- V3 : community 域（Phase 03 — Community MVP & Bootstrap）
-- ==========================================================================
-- 纪律（docs/00-工程规约.md §15.8）：V1 / V2 已发布，禁止再改。
-- 字符集 utf8mb4 / 排序 utf8mb4_0900_ai_ci，时间列统一 DATETIME(3)。
--
-- 本迁移相对 docs/03-domain-permission.md 的「初步模型」有三处**刻意的偏离**，
-- 每一处都在下面就地写明理由。那份文档是设计阶段的初稿，本文档是实现级事实。
-- ==========================================================================

-- --------------------------------------------------------------------------
-- category：板块
-- --------------------------------------------------------------------------
-- 对外标识用 slug 而不是自增 id：
--   · slug 与业务含义绑定，重跑种子或换环境不会漂移；id 依赖插入顺序
--   · 不对外暴露连续整数，沿用本项目"对外一律用不透明标识"的一贯做法
--     （identity 域的 public_id 是同一思路的一个更强版本）
-- 刻意**不**存 post_count：
--   它就是 SELECT COUNT(*) 的结果，存成列等于凭空多出一处必须与 post 表
--   保持同步的冗余；而板块数量是几十个量级，COUNT 走索引完全可接受。
--   "把能算出来的东西存起来"是缓存决策，应该等到有压测数据证明它确实是瓶颈时再做。
-- --------------------------------------------------------------------------
CREATE TABLE `category`
(
    `id`          SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `slug`        VARCHAR(32)       NOT NULL COMMENT '对外标识，URL 安全，如 study-notes',
    `name`        VARCHAR(32)       NOT NULL COMMENT '展示名',
    `description` VARCHAR(200)      DEFAULT NULL,
    `sort_order`  SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '展示排序，小的在前',
    `created_at`  DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`  DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_slug` (`slug`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='社区板块';

-- --------------------------------------------------------------------------
-- tag：标签
-- --------------------------------------------------------------------------
-- 与 category 一样用 slug 对外。tags 允许用户发布帖子时创建（社区常见做法），
-- 因此 slug 由服务端从标签名规范化生成，而不是让用户直接填。
-- uk_tag_slug 唯一：不同写法（Java / java / JAVA）在 utf8mb4_0900_ai_ci 下
-- 会撞同一个唯一键，这正是期望行为 —— 否则标签页会被大小写拆成很多份。
-- --------------------------------------------------------------------------
CREATE TABLE `tag`
(
    `id`         INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `slug`       VARCHAR(48)  NOT NULL COMMENT '对外标识，由标签名规范化（小写、空格转连字符）',
    `name`       VARCHAR(32)  NOT NULL COMMENT '展示名，保留用户输入的大小写',
    `created_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tag_slug` (`slug`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='内容标签';

-- --------------------------------------------------------------------------
-- post：帖子
-- --------------------------------------------------------------------------
-- 关于"正文存两份（body_md + body_html）"：
--   Markdown → HTML 的渲染是**安全边界**，因此放在服务端一次完成，而不是
--   交给每个客户端各渲染一遍（客户端渲染意味着"每个客户端都必须记得净化"，
--   漏一个就是一个存储型 XSS 入口）。存下渲染结果同时也省掉列表与详情页的
--   重复渲染开销。代价是编辑时必须两份一起更新 —— 这一点由服务层单一入口保证。
--
-- 关于**没有 status 列**：
--   设计初稿里有 status(ACTIVE|FOLDED|REMOVED)。本阶段只有"存在 / 已被作者删除"
--   两种真实状态，而后者用 deleted_at 表达即可（项目约定：内容类表软删除）。
--   同时存在 status 与 deleted_at 两套"内容是否可见"的机制，迟早会互相矛盾
--   （一条 status=PUBLISHED 但 deleted_at 非空的行该怎么解释？）。
--   折叠/下架是审核域的真实需求，等 Phase 10 引入 moderation 时再加列，
--   那时它与 deleted_at 的语义分工也才有明确的定义。
--
-- 关于计数列内联在 post 上，而没有按设计初稿拆出 content_stats 汇总表：
--   content_stats 的价值在于"多种内容类型共用一张统计表"（外部采集内容、
--   workspace 公开文档）。本阶段只有 post 一种内容类型，拆表的结果是一张
--   与 post 严格 1:1、永远同时读写的表，徒增一次 join。
--   拆分触发条件：Phase 06 引入 content_item 后，若多种内容类型确实共用统计口径，
--   再把计数迁到 content_stats。见 docs/adr/0004。
--
-- 关于索引里不放 deleted_at：
--   §9.1 的约定是"内容类索引包含 deleted_at IS NULL 条件"。这里没有照做，
--   理由是：deleted_at 作为复合索引的**末列**对过滤毫无帮助，作为**首列**则会让
--   "按发布时间倒序取前 N 条"失去有序扫描能力。而"已删除占比极小"这一前提
--   （正常运营下删除率是个位数百分比）使得"沿 published_at 倒序扫描 + 过滤
--   deleted_at"的实际代价可以忽略。等删除率真的变高再调整索引，而不是先加一列。
-- --------------------------------------------------------------------------
CREATE TABLE `post`
(
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    `public_id`        CHAR(22)         NOT NULL COMMENT '对外暴露的随机 ID，防 ID 遍历',
    `author_id`        BIGINT UNSIGNED  NOT NULL,
    `category_id`      SMALLINT UNSIGNED NOT NULL,
    `title`            VARCHAR(120)     NOT NULL,
    `summary`          VARCHAR(300)     NOT NULL
        COMMENT '列表卡片摘要。发布时从正文派生（剥掉 Markdown 标记后截断），存储而非读时计算：列表页不应为了取摘要把 MEDIUMTEXT 正文整列读出来',
    `body_md`          MEDIUMTEXT       NOT NULL COMMENT 'Markdown 原文，编辑时的事实来源',
    `body_html`        MEDIUMTEXT       NOT NULL COMMENT '服务端渲染 + 白名单净化后的 HTML，读路径直接返回',
    `view_count`       INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '仅统计登录用户且按天去重，口径见 post_view_daily',
    `like_count`       INT UNSIGNED     NOT NULL DEFAULT 0,
    `favorite_count`   INT UNSIGNED     NOT NULL DEFAULT 0,
    `comment_count`    INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '含回复，与该帖未被删除的评论行数一致',
    `published_at`     DATETIME(3)      NOT NULL,
    `created_at`       DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`       DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted_at`       DATETIME(3)      DEFAULT NULL COMMENT '非空即已删除。软删除：评论与审计仍需引用该行',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_public_id` (`public_id`),
    -- 社区首页与列表页的主访问路径：按发布时间倒序
    KEY `idx_post_published` (`published_at` DESC),
    -- 板块页：先按板块筛，再按时间倒序。复合索引列顺序与 WHERE+ORDER BY 一致，避免 filesort
    KEY `idx_post_category_published` (`category_id`, `published_at` DESC),
    -- 个人主页："Ta 发的帖子"
    KEY `idx_post_author_published` (`author_id`, `published_at` DESC),
    -- "最热"排序。用已落库的 like_count 排序，而不是引入 hot_score 列：
    -- 后者是"点赞×权重 + 评论×权重 + 时间衰减"的产物，需要定时重算（Phase 09 的事）。
    -- 当前阶段"按点赞数排序"就是一个语义清晰、可解释的热度。
    KEY `idx_post_hot` (`like_count` DESC, `published_at` DESC),
    CONSTRAINT `fk_post_author` FOREIGN KEY (`author_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_post_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='社区帖子';

-- --------------------------------------------------------------------------
-- post_tag：帖子-标签关联
-- --------------------------------------------------------------------------
CREATE TABLE `post_tag`
(
    `post_id`    BIGINT UNSIGNED NOT NULL,
    `tag_id`     INT UNSIGNED    NOT NULL,
    `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`post_id`, `tag_id`),
    KEY `idx_post_tag_tag` (`tag_id`, `post_id`) COMMENT '标签页：按标签取帖子',
    CONSTRAINT `fk_post_tag_post` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_post_tag_tag` FOREIGN KEY (`tag_id`) REFERENCES `tag` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='帖子-标签关联';

-- --------------------------------------------------------------------------
-- comment：评论与回复（严格两层）
-- --------------------------------------------------------------------------
-- 关于**没有 root_id**：
--   设计初稿里 comment 有 parent_id + root_id。但本文档把讨论深度**限制为两层**：
--   顶层评论（parent_id IS NULL）与回复（parent_id = 某条顶层评论的 id）。
--   在这个约束下 root_id 与"回复的 parent_id"恒等，多一列就多一处可能不一致的地方。
--   放开到任意层级时再补 root_id，并用一次性迁移回填（那时"根"的含义也需要重新定义）。
--
-- 关于正文用纯文本而**不是** Markdown：
--   评论是短回复，Markdown 带来的收益（结构、表格、代码块）很有限，而它会让
--   注入面扩大到每条评论 —— 收益与风险不匹配。因此评论按纯文本存储、保留换行，
--   由前端以文本节点渲染（不是 v-html），从根上不存在 XSS。
--   帖子正文不同：它是长内容，Markdown 是核心表达形式，所以那里做了服务端渲染 + 净化。
--
-- 关于**没有 like_count**：
--   本阶段没有"给评论点赞"这个功能。加一个没人写的计数列，只会让"这个数字是否可信"
--   变成需要逐处确认的问题。要评论点赞时再连同 comment_reaction 一起加。
-- --------------------------------------------------------------------------
CREATE TABLE `comment`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `public_id`  CHAR(22)        NOT NULL,
    `post_id`    BIGINT UNSIGNED NOT NULL,
    `parent_id`  BIGINT UNSIGNED DEFAULT NULL
        COMMENT 'NULL=顶层评论；非空=回复，且必须指向同一帖子的顶层评论（由服务层保证，见 CommentService）',
    `author_id`  BIGINT UNSIGNED NOT NULL,
    `body`       VARCHAR(1000)   NOT NULL COMMENT '纯文本，保留换行。1000 字符对"回复"这个体裁足够',
    `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted_at` DATETIME(3)     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_comment_public_id` (`public_id`),
    -- 一个索引同时服务两种查询：
    --   · 顶层评论列表：WHERE post_id=? AND parent_id IS NULL ORDER BY created_at
    --   · 某条顶层评论的回复：WHERE post_id=? AND parent_id=? ORDER BY created_at
    -- parent_id IS NULL 在 InnoDB 里是等值范围的前缀扫描，两种形状都能用上索引
    KEY `idx_comment_post_parent_created` (`post_id`, `parent_id`, `created_at`),
    KEY `idx_comment_author_created` (`author_id`, `created_at`),
    CONSTRAINT `fk_comment_post` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_comment_author` FOREIGN KEY (`author_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_comment_parent` FOREIGN KEY (`parent_id`) REFERENCES `comment` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='帖子评论与回复（两层）';

-- --------------------------------------------------------------------------
-- post_reaction：点赞与收藏（合并为一张表，用 type 区分）
-- --------------------------------------------------------------------------
-- 用 (user_id, post_id, type) 做主键，而不是加一个自增 id：
--   主键的唯一性本身就是"同一个人对同一条帖子同一类型只能有一条记录"这个业务约束。
--   这样幂等由数据库强制保证，不依赖服务层"先查再写"——后者在并发下必然漏。
--   （对比设计初稿的 UK(user_id, target_type, target_id, type)，那里因为 target 是
--     泛化的而无法建外键；本文档把 target 收敛为 post，于是两张关联表都能建真外键。）
--
-- 关于**没有**给 comment 建同样的 reaction 表：本阶段没有评论点赞功能。
--
-- 计数与明细的一致性：明细写入与 post.like_count 自增在**同一事务**内完成，
--   因此不存在"计数与明细不一致"的窗口。代价是热点帖的行锁竞争 ——
--   这正是 Phase 09 要测量的东西（docs/07-community-ops.md 场景 B3），
--   而不是现在凭猜测提前引入 Redis 或异步落库。
-- --------------------------------------------------------------------------
CREATE TABLE `post_reaction`
(
    `user_id`    BIGINT UNSIGNED NOT NULL,
    `post_id`    BIGINT UNSIGNED NOT NULL,
    `type`       VARCHAR(16)     NOT NULL COMMENT 'LIKE | FAVORITE',
    `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`, `post_id`, `type`),
    KEY `idx_post_reaction_post` (`post_id`, `type`) COMMENT '统计某帖的点赞/收藏数、列出点赞者',
    KEY `idx_post_reaction_user_type` (`user_id`, `type`, `created_at`) COMMENT '"我的收藏"列表：按时间倒序',
    CONSTRAINT `fk_post_reaction_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_post_reaction_post` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='帖子互动（点赞 / 收藏）';

-- --------------------------------------------------------------------------
-- post_view_daily：浏览去重明细（按天滚动）
-- --------------------------------------------------------------------------
-- 为什么需要这张表，而不是直接 UPDATE post SET view_count = view_count + 1：
--   1. 直接自增的口径毫无意义 —— 刷新 10 次就是 10 次浏览，这个数字不能用于
--      排序、也不能用于任何决策，等于白占一个字段。
--   2. 更实际的是锁竞争：详情页是最热的读路径，让每次读都去写同一行，
--      会把一个读多写少的场景变成行锁排队（Phase 09 的场景 B1 要量化的正是它）。
--   去重后写入量降到"每个用户每天每帖至多一次"，计数才有意义。
--
-- 为什么只统计登录用户：
--   匿名浏览要按 IP 去重，就必须把 IP 变成某种标识存下来。IP 地址空间很小，
--   未加盐的哈希用穷举就能还原 —— 那等于把用户 IP 明文存进业务表，只是看起来像脱敏。
--   为一个次要指标引入这种隐私负担不划算。因此：口径明确写死为"登录用户浏览量"，
--   匿名访问照常返回内容、只是不计数。匿名流量的规模统计属于接入层日志的职责。
--
-- 表会增长（帖子数 × 天数 × 日活用户），必须在设计阶段就定清理策略：
--   保留 90 天，由 Phase 13 的定时任务按 view_date 分区删除（idx_post_view_date 服务它）。
--   这条已经写入 docs/architecture.md 的已知限制，不允许被遗忘。
-- --------------------------------------------------------------------------
CREATE TABLE `post_view_daily`
(
    `post_id`    BIGINT UNSIGNED NOT NULL,
    `view_date`  DATE            NOT NULL COMMENT '按服务器统一时区（Asia/Shanghai）切分的一天',
    `user_id`    BIGINT UNSIGNED NOT NULL,
    `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`post_id`, `view_date`, `user_id`),
    KEY `idx_post_view_date` (`view_date`) COMMENT '按日期批量清理过期明细',
    KEY `idx_post_view_user` (`user_id`, `view_date`) COMMENT '反查"某人某天的浏览"，用于排查异常刷量',
    CONSTRAINT `fk_post_view_post` FOREIGN KEY (`post_id`) REFERENCES `post` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_post_view_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='帖子浏览去重明细（按天，90 天滚动）';

-- ==========================================================================
-- 种子数据：板块与标签
-- ==========================================================================
-- 只种「结构性」数据：板块和标签是内容分类的骨架，没有它们无法发布帖子。
-- **演示帖子、评论、互动不在这里** —— 那些由 Synthetic Demo Seed 生成
-- （ai.camphub.community.app.DemoContentSeeder），理由见该类的注释：
--   1. 种子进入 Flyway 迁移会污染所有环境，包括集成测试。
--      测试里的"列表应该有 N 条"会变成对种子行数的隐式依赖，一改种子就红一片。
--   2. 演示数据需要的是"一次性生成一批逼真的内容"，用 SQL 写出来既冗长又难维护。
--
-- 显式指定 id：让种子与引用稳定，不依赖自增顺序（与 V2 的角色种子同一做法）。
-- ==========================================================================
INSERT INTO `category` (`id`, `slug`, `name`, `description`, `sort_order`)
VALUES (1, 'tech', '技术讨论', '编程语言、框架、工具与工程实践', 10),
       (2, 'study-notes', '学习笔记', '课程笔记、读书摘要、知识整理', 20),
       (3, 'campus', '校园生活', '选课、社团、宿舍、食堂与日常', 30),
       (4, 'qa', '求助问答', '遇到具体问题，向同学求助', 40),
       (5, 'resources', '资源分享', '课件、题库、工具与开源项目', 50),
       (6, 'career', '实习与竞赛', '实习经历、竞赛组队与项目复盘', 60);

INSERT INTO `tag` (`id`, `slug`, `name`)
VALUES (1, 'java', 'Java'),
       (2, 'spring-boot', 'Spring Boot'),
       (3, 'mysql', 'MySQL'),
       (4, 'algorithm', '算法'),
       (5, 'frontend', '前端'),
       (6, 'database', '数据库'),
       (7, 'operating-system', '操作系统'),
       (8, 'computer-network', '计算机网络'),
       (9, 'postgraduate-exam', '考研'),
       (10, 'english', '英语'),
       (11, 'internship', '实习'),
       (12, 'open-source', '开源项目'),
       (13, 'exam-review', '期末复习'),
       (14, 'scholarship', '奖学金'),
       (15, 'contest', '学科竞赛');
