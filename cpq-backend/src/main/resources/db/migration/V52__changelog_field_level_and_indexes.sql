-- ============================================================
-- V52: basic_data_change_log 字段级重构 + 三客户表唯一索引补全
-- Ref: docs/superpowers/specs/2026-04-26-cpq-design-v5.1.md §3.5 §4.7
--      Phase 3 #12+#13 VersionedWriter + 字段级 change_log
-- ============================================================

-- ============== basic_data_change_log 新增字段级行列 ==============
-- V44 聚合 schema (change_type / field_changes JSONB / version_before/after) 保留为 nullable，标记 deprecated
-- v5 新增字段级行级字段（每字段一行模式）

ALTER TABLE basic_data_change_log
    ADD COLUMN IF NOT EXISTS field_name VARCHAR(64),
    ADD COLUMN IF NOT EXISTS old_value TEXT,
    ADD COLUMN IF NOT EXISTS new_value TEXT,
    ADD COLUMN IF NOT EXISTS customer_id UUID,
    ADD COLUMN IF NOT EXISTS hf_part_no VARCHAR(64),
    ADD COLUMN IF NOT EXISTS importance VARCHAR(16),
    ADD COLUMN IF NOT EXISTS affects_calculation BOOLEAN,
    ADD COLUMN IF NOT EXISTS change_source VARCHAR(32),
    ADD COLUMN IF NOT EXISTS note TEXT;

-- V44 的 change_type 有 NOT NULL 约束，但新写入只用 change_source；
-- 为了兼容新行（change_type 无意义），先放宽为 nullable
ALTER TABLE basic_data_change_log
    ALTER COLUMN change_type DROP NOT NULL;

-- change_source CHECK（idempotent via DO block）
DO $$ BEGIN
  ALTER TABLE basic_data_change_log
      ADD CONSTRAINT chk_bdcl_source
      CHECK (change_source IS NULL OR change_source IN ('V5_IMPORT','MANUAL_EDIT','SYSTEM_INIT','SYNC'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- importance CHECK（idempotent via DO block）
DO $$ BEGIN
  ALTER TABLE basic_data_change_log
      ADD CONSTRAINT chk_bdcl_importance
      CHECK (importance IS NULL OR importance IN ('CRITICAL','IMPORTANT','NORMAL'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 索引（UI-7 主查询路径）
CREATE INDEX IF NOT EXISTS idx_bdcl_cust_field
    ON basic_data_change_log(customer_id, hf_part_no, table_name, field_name, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_bdcl_source
    ON basic_data_change_log(change_source, changed_at DESC);

-- 注释：legacy 字段保留兼容
COMMENT ON COLUMN basic_data_change_log.field_changes IS 'DEPRECATED v5.1: 历史聚合存储；新写入用 field_name/old_value/new_value 字段级行';
COMMENT ON COLUMN basic_data_change_log.change_type IS 'DEPRECATED v5.1: 已迁移到 change_source；新行写入时此字段为 NULL';

-- ============== mat_fee 当前版本唯一索引（V44 未建）==============
-- 业务键：customer_id + hf_part_no + fee_type（三元组唯一确定当前版本）
-- 注意：V44 中 mat_fee 的现有索引是 idx_mat_fee_cust_type / idx_mat_fee_current，无唯一约束
CREATE UNIQUE INDEX IF NOT EXISTS uq_mat_fee_current
    ON mat_fee(customer_id, hf_part_no, fee_type)
    WHERE is_current = true;

-- ============== plating_fee 当前版本唯一索引（V44 未建）==============
-- 业务键：customer_id + hf_part_no + plating_plan_code + plan_version（四元组唯一确定当前版本）
CREATE UNIQUE INDEX IF NOT EXISTS uq_plating_fee_current
    ON plating_fee(customer_id, hf_part_no, plating_plan_code, plan_version)
    WHERE is_current = true;

-- ============== mat_process 当前版本唯一索引（V44 已建 uq_mat_process_current，无需重复）==============
-- V44: CREATE UNIQUE INDEX uq_mat_process_current ON mat_process(customer_id, hf_part_no, seq_no, sub_seq_no) WHERE is_current = true;
-- 已存在，V52 不重建。
