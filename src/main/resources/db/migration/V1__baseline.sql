-- ==========================================================================
-- V1 : 基线迁移（Phase 01 — Engineering Foundation）
-- ==========================================================================
-- 纪律（docs/00-工程规约.md §15.8）：
--   · 本次迁移一旦提交并发布，**禁止再修改**；后续变更请新增 V2__xxx.sql
--   · 所有表显式指定 utf8mb4 / utf8mb4_0900_ai_ci，与 docs/11-开发环境.md §5.1
--     实测的库级默认保持一致，避免"库默认一套、表另一套"导致中文排序与
--     大小写比较行为不一致（这在标签去重、昵称唯一性判断上会真的出问题）
--   · 主键统一 BIGINT UNSIGNED，为后续可能的分库/数据迁移留余量
-- ==========================================================================

-- --------------------------------------------------------------------------
-- app_metadata：应用元数据（运维用键值表）
-- --------------------------------------------------------------------------
-- 定位：记录「部署实例」的技术性事实，不承载任何业务语义。
-- 为什么不直接读 Flyway 的 flyway_schema_history：那是第三方库的内部表，
-- 其结构随库升级可能变化；把对外承诺的 schema 版本放在自己的表里，语义清晰且稳定。
-- --------------------------------------------------------------------------
CREATE TABLE app_metadata
(
    meta_key   VARCHAR(64)  NOT NULL COMMENT '元数据键',
    meta_value VARCHAR(512) NOT NULL COMMENT '元数据值',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '最后更新时间（毫秒精度：便于排查"谁在什么时候改了配置"）',
    PRIMARY KEY (meta_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='应用元数据（运维用键值表）';

-- 写入 schema 基线标记：接口 /api/v1/system/info 会读取它，
-- 用于确认「应用起来了」且「迁移确实生效了」——两件事必须能分别验证。
INSERT INTO app_metadata (meta_key, meta_value)
VALUES ('schema.baseline', 'V1');

-- 记录本次初始化时间（仅供运维观察，不参与任何业务逻辑）
INSERT INTO app_metadata (meta_key, meta_value)
VALUES ('app.initialized_at', DATE_FORMAT(NOW(3), '%Y-%m-%dT%H:%i:%s.%f'));
