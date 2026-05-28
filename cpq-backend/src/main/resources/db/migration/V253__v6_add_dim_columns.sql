-- ============================================================
-- V253: v1.2 改造前置 — 给 V6 表加扩展维度列
--
-- 改动清单（全部 NULL-allowed，向后兼容）：
--   fee_config   ← 3 列：dim_input_material_no / dim_sub_seq_no / dim_element_name
--   plating_scheme ← 1 列：hf_part_no
--
-- 注意：unit_price.defect_rate 已在 V220 中定义，本脚本不重复 ALTER。
--
-- import 链路（BasicDataImportServiceV5 PR）再 backfill 这些字段。
-- ============================================================

DO $$ BEGIN RAISE NOTICE 'V253: 开始 V6 扩展维度列迁移'; END $$;

-- ── fee_config：来料/成品费用组件维度键 ──────────────────────────────────
ALTER TABLE fee_config
    ADD COLUMN IF NOT EXISTS dim_input_material_no VARCHAR(20),
    ADD COLUMN IF NOT EXISTS dim_sub_seq_no        INTEGER,
    ADD COLUMN IF NOT EXISTS dim_element_name      VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_fee_config_dim_material
    ON fee_config(material_no, dim_input_material_no) WHERE dim_input_material_no IS NOT NULL;

COMMENT ON COLUMN fee_config.dim_input_material_no IS
    'v1.2 来料维度（v12_incoming_other/v12_incoming_fixed_fee 组件用），import PR backfill';
COMMENT ON COLUMN fee_config.dim_sub_seq_no IS
    'v1.2 来料子项序号维度，import PR backfill';
COMMENT ON COLUMN fee_config.dim_element_name IS
    'v1.2 费用元素名称维度（v12_finished_other/v12_finished_fixed_fee 组件用），import PR backfill';

-- ── plating_scheme：料号绑定维度 ────────────────────────────────────────
ALTER TABLE plating_scheme
    ADD COLUMN IF NOT EXISTS hf_part_no VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_plating_scheme_hf_part_no
    ON plating_scheme(hf_part_no) WHERE hf_part_no IS NOT NULL;

COMMENT ON COLUMN plating_scheme.hf_part_no IS
    'v1.2 料号绑定维度（v12_plating_scheme 组件用），import PR backfill；'
    '数据未 backfill 前电镀方案 Tab 显示空（预期行为，同 V141/V142 时情况）';

DO $$ BEGIN RAISE NOTICE 'V253: V6 扩展维度列迁移完成'; END $$;
