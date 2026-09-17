-- ==========================================================================
-- V2 : identity 域 + platform.audit（Phase 02 — Identity & Security Foundation）
-- ==========================================================================
-- 纪律（docs/00-工程规约.md §15.8）：
--   · V1 已发布，禁止再改；本次变更全部写在 V2
--   · 字符集 utf8mb4 / 排序 utf8mb4_0900_ai_ci，与库级默认保持一致
--   · 主键 BIGINT UNSIGNED；对外暴露的实体另有 public_id CHAR(22)（防 ID 遍历）
--   · 时间列统一 DATETIME(3)（毫秒精度：令牌过期与登录风控都靠时间比较，
--     秒级精度在"同一秒内先撤销后校验"的场景下会给出错误结论）
-- ==========================================================================

-- --------------------------------------------------------------------------
-- user：账号主表
-- --------------------------------------------------------------------------
-- 关于排序规则的一个**刻意选择**：
--   utf8mb4_0900_ai_ci 是大小写不敏感 + 重音不敏感的。这意味着
--   Alice / alice / ÁLICE 会撞在同一个唯一键上。
--   这在用户名与邮箱上是**期望行为**：若区分大小写，攻击者就能注册
--   "Adm1n" 冒充 "adm1n"，同名冒充类钓鱼会变得容易。
-- --------------------------------------------------------------------------
CREATE TABLE `user`
(
    `id`            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    `public_id`     CHAR(22)         NOT NULL COMMENT '对外暴露的随机 ID（22 位），避免用自增 ID 被遍历',
    `username`      VARCHAR(32)      NOT NULL COMMENT '登录名，不区分大小写',
    `email`         VARCHAR(254)     NOT NULL COMMENT '邮箱，不区分大小写。254 是 RFC 5321 规定的最大长度',
    `nickname`      VARCHAR(32)      NOT NULL COMMENT '展示名，可与 username 不同',
    `avatar_url`    VARCHAR(512)     DEFAULT NULL,
    `bio`           VARCHAR(200)     DEFAULT NULL COMMENT '个人简介，长度上限按"一句话介绍"设定',
    `status`        VARCHAR(16)      NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE | LOCKED | DISABLED。用字符串而非数字：日志与 SQL 里可直接读懂',
    `token_version` INT UNSIGNED     NOT NULL DEFAULT 1
        COMMENT '令牌世代号。改密/登出全部设备/踢下线时 +1，使所有已签发的访问令牌立即失效',
    `created_at`    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted_at`    DATETIME(3)      DEFAULT NULL COMMENT '软删除。审计与内容引用都需要账号行继续存在',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_public_id` (`public_id`),
    UNIQUE KEY `uk_user_username` (`username`),
    UNIQUE KEY `uk_user_email` (`email`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户账号';

-- --------------------------------------------------------------------------
-- user_credential：凭据与登录风控状态
-- --------------------------------------------------------------------------
-- 为什么与 user 分表：凭据是**读取频率最低、敏感度最高**的字段。
-- 分表后，任何"列出用户/搜索用户"的查询天然拿不到 password_hash，
-- 不必依赖每个开发者记得在 SELECT 里排除它。
--
-- 本阶段不含 mfa_secret（MFA 不在 Phase 02 范围内），需要时再新增迁移。
-- --------------------------------------------------------------------------
CREATE TABLE `user_credential`
(
    `user_id`             BIGINT UNSIGNED   NOT NULL,
    `password_hash`       VARCHAR(100)      NOT NULL
        COMMENT 'BCrypt cost=12 输出（60 字符，含算法标识与 salt）。长度留余量以便将来平滑换算法',
    `failed_login_count`  SMALLINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '连续登录失败次数，成功后清零。用于触发临时锁定',
    `locked_until`        DATETIME(3)       DEFAULT NULL
        COMMENT '锁定到期时间。用"到期时间"而非"是否锁定"布尔位：避免需要定时任务去解锁',
    `last_login_at`       DATETIME(3)       DEFAULT NULL,
    `password_updated_at` DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `created_at`          DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`          DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_credential_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户凭据与登录风控状态';

-- --------------------------------------------------------------------------
-- role / permission / user_role / role_permission：标准 RBAC 四表
-- --------------------------------------------------------------------------
-- 重要建模约束（docs/03-domain-permission.md §10.1）：
--   这里只放**平台级**角色。WORKSPACE_MEMBER / WORKSPACE_ADMIN **不是**全局角色，
--   而是 (user, workspace) 上的关系属性，它们属于 workspace 域的 workspace_member 表。
--   把上下文角色塞进全局角色表，会直接产生"在 A 空间是管理员，于是能管 B 空间"的越权。
-- --------------------------------------------------------------------------
CREATE TABLE `role`
(
    `id`          SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(32)       NOT NULL COMMENT '角色码，如 USER。代码中按 code 引用，不依赖自增 id',
    `name`        VARCHAR(64)       NOT NULL COMMENT '展示名',
    `description` VARCHAR(200)      DEFAULT NULL,
    `is_system`   TINYINT           NOT NULL DEFAULT 0 COMMENT '1=内置角色，不允许通过接口删除',
    `created_at`  DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`  DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='平台级角色';

CREATE TABLE `permission`
(
    `id`          SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(64)       NOT NULL COMMENT '权限码，形如 resource:action[:scope]',
    `description` VARCHAR(200)      DEFAULT NULL,
    `created_at`  DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='权限点注册表';

CREATE TABLE `user_role`
(
    `user_id`    BIGINT UNSIGNED   NOT NULL,
    `role_id`    SMALLINT UNSIGNED NOT NULL,
    `created_at` DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`, `role_id`),
    KEY `idx_user_role_role` (`role_id`) COMMENT '反查"哪些人拥有该角色"',
    CONSTRAINT `fk_user_role_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_user_role_role` FOREIGN KEY (`role_id`) REFERENCES `role` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户-角色关联';

CREATE TABLE `role_permission`
(
    `role_id`       SMALLINT UNSIGNED NOT NULL,
    `permission_id` SMALLINT UNSIGNED NOT NULL,
    `created_at`    DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`role_id`, `permission_id`),
    KEY `idx_role_permission_permission` (`permission_id`),
    CONSTRAINT `fk_role_permission_role` FOREIGN KEY (`role_id`) REFERENCES `role` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_role_permission_permission` FOREIGN KEY (`permission_id`) REFERENCES `permission` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='角色-权限关联';

-- --------------------------------------------------------------------------
-- refresh_token：可撤销、可轮换、可追溯的刷新令牌
-- --------------------------------------------------------------------------
-- 三条设计要点：
--   1. **只存哈希**。刷新令牌是长期凭据（30 天），等价于密码；
--      库被读走时，哈希不可逆，攻击者无法直接冒用。
--      这里用 SHA-256 而不是 BCrypt：令牌本身是 256 bit 随机值，没有"弱口令"问题，
--      不需要慢哈希；而每次刷新都要按 token_hash 建索引查找，慢哈希会让刷新接口变成 CPU 瓶颈。
--   2. **轮换**：每次刷新都签发新令牌并作废旧令牌（revoked_at + rotated_from 串成链）。
--   3. **重放检测**：被轮换掉的旧令牌若再次出现，说明令牌已泄露，服务端据此撤销该用户全部会话。
-- --------------------------------------------------------------------------
CREATE TABLE `refresh_token`
(
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`      BIGINT UNSIGNED NOT NULL,
    `token_hash`   CHAR(64)        NOT NULL COMMENT 'SHA-256 十六进制（小写），唯一',
    `device`       VARCHAR(64)     NOT NULL DEFAULT 'unknown'
        COMMENT '设备标识，用于"查看并单独登出某台设备"。由客户端上报，因此仅作展示，不作安全判据',
    `expires_at`   DATETIME(3)     NOT NULL,
    `revoked_at`   DATETIME(3)     DEFAULT NULL COMMENT '非空即已失效（登出 / 轮换 / 重放处置）',
    `rotated_from` BIGINT UNSIGNED DEFAULT NULL COMMENT '由哪条令牌轮换而来，构成可追溯的令牌链',
    `last_used_at` DATETIME(3)     DEFAULT NULL,
    `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_refresh_token_hash` (`token_hash`),
    KEY `idx_refresh_token_user_expires` (`user_id`, `expires_at`) COMMENT '列出某用户的全部会话',
    CONSTRAINT `fk_refresh_token_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='刷新令牌（只存哈希）';

-- --------------------------------------------------------------------------
-- audit_log：安全审计（platform 域）
-- --------------------------------------------------------------------------
-- 为什么 **不** 给 actor_user_id 加外键：
--   审计日志的价值在于"账号已删除之后仍然查得到谁做过什么"。
--   加外键（且它在 user 表上是 CASCADE）会让删除用户连带抹掉审计痕迹，
--   那正好把审计最需要保留的部分删掉了。此处刻意保留裸 ID。
-- --------------------------------------------------------------------------
CREATE TABLE `audit_log`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `actor_user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '操作者；未认证动作（如登录失败）为 NULL',
    `action`        VARCHAR(64)     NOT NULL COMMENT '动作码，如 AUTH_LOGIN / AUTH_PASSWORD_CHANGE',
    `target_type`   VARCHAR(32)     DEFAULT NULL COMMENT '操作对象类型，如 USER / SESSION',
    `target_id`     VARCHAR(64)     DEFAULT NULL COMMENT '操作对象标识（存 public_id，不存自增 ID）',
    `result`        VARCHAR(16)     NOT NULL COMMENT 'SUCCESS | FAILURE',
    `ip`            VARCHAR(64)     DEFAULT NULL COMMENT '来源 IP。64 位可容纳 IPv6 及其端口',
    `user_agent`    VARCHAR(512)    DEFAULT NULL,
    `detail_json`   JSON COMMENT '结构化补充信息。**不得写入密码、令牌原文等凭据**',
    `trace_id`      VARCHAR(64)     DEFAULT NULL COMMENT '与请求日志的 traceId 对齐，便于串起完整链路',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_audit_actor_created` (`actor_user_id`, `created_at`) COMMENT '"某人的操作历史"',
    KEY `idx_audit_target` (`target_type`, `target_id`) COMMENT '"这个对象被谁动过"',
    KEY `idx_audit_action_created` (`action`, `created_at`) COMMENT '"某类安全事件的时间线"'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='安全审计日志';

-- ==========================================================================
-- 种子数据
-- ==========================================================================
-- 只种「本阶段真实会被用到」的数据。
--
-- 刻意**不**预置 MODERATOR / ADMIN 角色与权限点清单：
--   本阶段没有任何接口检查它们。提前种下去的结果是表里躺着一批
--   "看起来有权限体系、实际没人检查"的行 —— 这种装饰性数据比没有更危险，
--   因为它会让人误以为权限已经配好了。权限点随各模块引入时各自种子化：
--   Phase 03 引入 post:* / comment:*，Phase 04 引入 workspace:*，Phase 10 引入 moderation:* / admin:*。
--
-- 显式指定 id=1：让"新用户默认角色"这条链路不依赖自增顺序，
-- 后续新增角色时也不会因为插入顺序变化而错位。
-- ==========================================================================
INSERT INTO `role` (`id`, `code`, `name`, `description`, `is_system`)
VALUES (1, 'USER', '普通用户', '所有注册用户的基础角色', 1);
