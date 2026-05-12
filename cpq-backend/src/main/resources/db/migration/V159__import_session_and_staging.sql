-- =============================================================================
-- V159: V6 导入向导 — import_session + 7 张 staging 表
--
-- 设计文档: docs/superpowers/specs/2026-05-12-import-v6-staging-design.md
-- RECORD:   docs/RECORD.md [2026-05-12]
--
-- 用途: Excel 导入向导 V6 staging 三步流程
--   * upload  解析 Excel 写 staging + 检测差异 + 写决策
--   * decisions 用户切换每料号 BUMP/NO_BUMP/CONFLICT/ORPHAN 决策
--   * commit  staging → mat_* atomic 合并 + 建报价单 + 生成 snapshot
--   * cancel  CASCADE 清掉整个 session（含 staging + decisions）
--
-- 设计原则:
--   * mat_*_staging 列结构 = mat_* + import_session_id + staging_id（独立 PK）
--   * 不继承源表的 PK/UNIQUE/CHECK 约束（避免重复键冲突 + 跟 staging 语义不符）
--   * import_session 删除 CASCADE 触发整个 session 的所有 staging 数据清理
-- =============================================================================

-- ------------------------------------------------------------
-- 1. import_session 主表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS import_session (
    id            UUID PRIMARY KEY,
    customer_id   UUID NOT NULL REFERENCES customer(id),
    user_id       UUID,
    status        TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING / COMMITTED / CANCELLED / EXPIRED
    source_excel  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '24 hours'),
    committed_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_import_session_status_expires
    ON import_session(status, expires_at);
CREATE INDEX IF NOT EXISTS ix_import_session_customer
    ON import_session(customer_id);

COMMENT ON TABLE  import_session IS 'V6 Excel 导入会话主表 (staging 隔离 + 24h TTL)';
COMMENT ON COLUMN import_session.status IS 'PENDING | COMMITTED | CANCELLED | EXPIRED';

-- ------------------------------------------------------------
-- 2. import_session_decision 决策表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS import_session_decision (
    import_session_id UUID NOT NULL REFERENCES import_session(id) ON DELETE CASCADE,
    decision_type     TEXT NOT NULL,    -- PART_VERSION / CUSTOMER_CONFLICT / ORPHAN
    decision_key      TEXT NOT NULL,    -- "cpn|hf" / "conflictType|primaryKey" / "sheetCode|rowIndex"
    decision_value    JSONB NOT NULL,
    PRIMARY KEY (import_session_id, decision_type, decision_key)
);

COMMENT ON TABLE  import_session_decision IS 'V6 导入会话决策表 (BUMP/NO_BUMP/USE_EXCEL/...)';
COMMENT ON COLUMN import_session_decision.decision_type IS 'PART_VERSION | CUSTOMER_CONFLICT | ORPHAN';
COMMENT ON COLUMN import_session_decision.decision_value IS 'JSONB: {action, currentVersion?, suggestedVersion?, appliedVersion?, ...}';

-- ------------------------------------------------------------
-- 3-9. mat_*_staging 7 张表
-- 列结构 = 源表 + staging_id (PK) + import_session_id (FK CASCADE)
--
-- 用 PL/pgSQL DO 块批量生成，幂等（IF NOT EXISTS 守护）。
-- ------------------------------------------------------------
DO $$
DECLARE
    src_tables TEXT[] := ARRAY[
        'mat_part',
        'mat_customer_part_mapping',
        'mat_bom',
        'mat_process',
        'mat_fee',
        'mat_plating_fee',
        'mat_plating_plan'
    ];
    t TEXT;
    staging_t TEXT;
BEGIN
    FOREACH t IN ARRAY src_tables LOOP
        staging_t := t || '_staging';

        -- 仅当 staging 表不存在时创建
        IF NOT EXISTS (
            SELECT 1 FROM pg_tables WHERE schemaname = current_schema() AND tablename = staging_t
        ) THEN
            -- 1) 用 LIKE INCLUDING DEFAULTS 拷贝列 + 默认值（不带任何约束/索引）
            EXECUTE format(
                'CREATE TABLE %I (LIKE %I INCLUDING DEFAULTS)',
                staging_t, t
            );

            -- 2) 把源表 id 列（如果有）的 NOT NULL 限制去掉
            --    staging_id 才是 PK，源 id 可以留 null 让 commit 时再 gen_random_uuid()
            BEGIN
                EXECUTE format('ALTER TABLE %I ALTER COLUMN id DROP NOT NULL', staging_t);
            EXCEPTION WHEN OTHERS THEN
                NULL;  -- id 列不存在或非 NOT NULL，跳过
            END;

            -- 3) 加 staging_id (新 PK) 和 import_session_id (FK CASCADE)
            EXECUTE format(
                'ALTER TABLE %I ADD COLUMN staging_id UUID PRIMARY KEY DEFAULT gen_random_uuid()',
                staging_t
            );
            EXECUTE format(
                'ALTER TABLE %I ADD COLUMN import_session_id UUID NOT NULL '
                    || 'REFERENCES import_session(id) ON DELETE CASCADE',
                staging_t
            );

            -- 4) 加 session 索引（commit/cancel 流程的批量 WHERE 谓词）
            EXECUTE format(
                'CREATE INDEX %I ON %I (import_session_id)',
                'ix_' || staging_t || '_session', staging_t
            );

            -- 5) 注释
            EXECUTE format(
                'COMMENT ON TABLE %I IS %L',
                staging_t,
                'V6 导入 staging 表 (' || t || ')，commit 时合并到正式表，cancel 时 CASCADE 清空'
            );

            RAISE NOTICE 'Created staging table: %', staging_t;
        ELSE
            RAISE NOTICE 'Staging table already exists, skipping: %', staging_t;
        END IF;
    END LOOP;
END
$$;
