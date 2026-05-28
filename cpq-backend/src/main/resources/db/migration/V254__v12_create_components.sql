-- ============================================================
-- V254: v1.2 组件创建 — 复制 20 个 v1.1 组件（code 加 -V12 后缀）
--
-- 决策 B（架构师 §5）：复制新组件，data_driver_path 和 fields 中 basic_data_path
--   全部替换 v_c_*_merged → $v12_* 形态。
--
-- 幂等：WHERE NOT EXISTS (SELECT 1 FROM component WHERE code = src.code || '-V12')
-- 目标 codes: COMP-V5-PART-MAPPING-V12 ... COMP-V5-WEIGHT-V12 (20 个)
-- ============================================================

DO $$ BEGIN RAISE NOTICE 'V254: 开始创建 v1.2 组件（-V12 后缀）'; END $$;

INSERT INTO component (
    id, directory_id, name, code, column_count, fields, formulas,
    status, component_type, data_driver_path, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    src.directory_id,
    src.name || ' (v1.2)',
    src.code || '-V12',
    src.column_count,
    -- fields: 替换所有 basic_data_path 从 v_c_*_merged.xxx → $v12_*.xxx
    REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
    REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
        src.fields::text,
        '"v_c_part_mapping_merged.',         '"$v12_part_mapping.'),
        '"v_c_raw_bom_merged.',              '"$v12_raw_bom.'),
        '"v_c_raw_element_bom_merged.',      '"$v12_raw_element_bom.'),
        '"v_c_labor_cost_merged.',           '"$v12_labor_cost.'),
        '"v_c_depreciation_merged.',         '"$v12_depreciation_cost.'),
        '"v_c_energy_prod_merged.',          '"$v12_energy_prod_cost.'),
        '"v_c_energy_aux_merged.',           '"$v12_energy_aux_cost.'),
        '"v_c_tooling_merged.',              '"$v12_tooling_cost.'),
        '"v_c_consumable_prod_merged.',      '"$v12_consumable_prod.'),
        '"v_c_packaging_merged.',            '"$v12_packaging.'),
        '"v_c_incoming_proc_merged.',        '"$v12_incoming_proc.'),
        '"v_c_incoming_other_merged.',       '"$v12_incoming_other.'),
        '"v_c_incoming_fixed_fee_merged.',   '"$v12_incoming_fixed_fee.'),
        '"v_c_finished_proc_merged.',        '"$v12_finished_proc.'),
        '"v_c_finished_other_merged.',       '"$v12_finished_other.'),
        '"v_c_finished_fixed_fee_merged.',   '"$v12_finished_fixed_fee.'),
        '"v_c_plating_scheme_merged.',       '"$v12_plating_scheme.'),
        '"v_c_plating_cost_merged.',         '"$v12_plating_cost.'),
        '"v_c_outsource_merged.',            '"$v12_outsource_cost.'),
        '"v_c_weight_merged.',               '"$v12_weight.')::jsonb,
    src.formulas,
    'ACTIVE',
    COALESCE(src.component_type, 'NORMAL'),
    -- data_driver_path: v_c_xx_merged → $v12_xx
    CASE src.code
        WHEN 'COMP-V5-PART-MAPPING'        THEN '$v12_part_mapping'
        WHEN 'COMP-V5-RAW-BOM'             THEN '$v12_raw_bom'
        WHEN 'COMP-V5-RAW-ELEMENT-BOM'     THEN '$v12_raw_element_bom'
        WHEN 'COMP-V5-LABOR-COST'          THEN '$v12_labor_cost'
        WHEN 'COMP-V5-DEPRECIATION'        THEN '$v12_depreciation_cost'
        WHEN 'COMP-V5-ENERGY-PROD'         THEN '$v12_energy_prod_cost'
        WHEN 'COMP-V5-ENERGY-AUX'          THEN '$v12_energy_aux_cost'
        WHEN 'COMP-V5-TOOLING'             THEN '$v12_tooling_cost'
        WHEN 'COMP-V5-CONSUMABLE-PROD'     THEN '$v12_consumable_prod'
        WHEN 'COMP-V5-PACKAGING'           THEN '$v12_packaging'
        WHEN 'COMP-V5-INCOMING-PROC'       THEN '$v12_incoming_proc'
        WHEN 'COMP-V5-INCOMING-OTHER'      THEN '$v12_incoming_other'
        WHEN 'COMP-V5-INCOMING-FIXED-FEE'  THEN '$v12_incoming_fixed_fee'
        WHEN 'COMP-V5-FINISHED-PROC'       THEN '$v12_finished_proc'
        WHEN 'COMP-V5-FINISHED-OTHER'      THEN '$v12_finished_other'
        WHEN 'COMP-V5-FINISHED-FIXED-FEE'  THEN '$v12_finished_fixed_fee'
        WHEN 'COMP-V5-PLATING-SCHEME'      THEN '$v12_plating_scheme'
        WHEN 'COMP-V5-PLATING-COST'        THEN '$v12_plating_cost'
        WHEN 'COMP-V5-OUTSOURCE'           THEN '$v12_outsource_cost'
        WHEN 'COMP-V5-WEIGHT'              THEN '$v12_weight'
        ELSE src.data_driver_path
    END,
    NOW(),
    NOW()
FROM component src
WHERE src.code LIKE 'COMP-V5-%'
  AND src.code NOT LIKE '%-V12'
  AND NOT EXISTS (
      SELECT 1 FROM component dup WHERE dup.code = src.code || '-V12'
  );

DO $$
DECLARE v_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt FROM component WHERE code LIKE 'COMP-V5-%-V12';
    RAISE NOTICE 'V254: v1.2 组件创建完成，当前 -V12 组件总数=%', v_cnt;
END $$;
