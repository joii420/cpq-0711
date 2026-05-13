-- ============================================================
-- V161: 给所有 v_c_*_merged 视图暴露 part_version 列 (核价 5.0 系列)
-- ============================================================
-- 根因 (与 V160 同构):
--   V142 创建的 20 个核价合并视图 (v_c_*_merged), SELECT 投影
--   均未包含 part_version 列. 当 V153 给底表 costing_part_* /
--   mat_fee 加上 part_version 后, 视图列结构不变, 导致
--   ImplicitJoinRewriter 不注入 AND part_version=N 谓词
--   → 在核价单 tab 上同样会出现多版本叠加 Bug.
--
--   各视图 is_active=true / is_current=true / status='ACTIVE' 兜底过滤
--   只在同版本"上一次写入被新写入覆盖"时起作用; 跨版本场景
--   (V6 BUMP 后, 旧版本行 is_active 仍 true 因为它没被显式置 false)
--   则无保护, 必然叠加.
--
-- 修复范围: 19 个视图 (v_c_part_mapping_merged 底表为 mat_customer_part_mapping
--   非版本化, 不动)
--
--   含 JOIN 的两个视图额外加 part_version 对齐, 防跨版本污染:
--     - v_c_raw_element_bom_merged: eb.part_version = mb.part_version
--     - v_c_plating_scheme_merged : cpp.part_version = f.part_version
--
-- DDL 后必须 touch 一个 java 文件强制 Quarkus 重启 (CLAUDE.md 规定).
-- ============================================================

-- ── DROP CASCADE 19 视图 (顺序无关) ────────────────────────────
DROP VIEW IF EXISTS v_c_raw_bom_merged           CASCADE;
DROP VIEW IF EXISTS v_c_raw_element_bom_merged   CASCADE;
DROP VIEW IF EXISTS v_c_labor_cost_merged        CASCADE;
DROP VIEW IF EXISTS v_c_depreciation_merged      CASCADE;
DROP VIEW IF EXISTS v_c_energy_prod_merged       CASCADE;
DROP VIEW IF EXISTS v_c_energy_aux_merged        CASCADE;
DROP VIEW IF EXISTS v_c_tooling_merged           CASCADE;
DROP VIEW IF EXISTS v_c_consumable_prod_merged   CASCADE;
DROP VIEW IF EXISTS v_c_packaging_merged         CASCADE;
DROP VIEW IF EXISTS v_c_incoming_proc_merged     CASCADE;
DROP VIEW IF EXISTS v_c_incoming_other_merged    CASCADE;
DROP VIEW IF EXISTS v_c_incoming_fixed_fee_merged CASCADE;
DROP VIEW IF EXISTS v_c_finished_proc_merged     CASCADE;
DROP VIEW IF EXISTS v_c_finished_other_merged    CASCADE;
DROP VIEW IF EXISTS v_c_finished_fixed_fee_merged CASCADE;
DROP VIEW IF EXISTS v_c_plating_scheme_merged    CASCADE;
DROP VIEW IF EXISTS v_c_plating_cost_merged      CASCADE;
DROP VIEW IF EXISTS v_c_outsource_merged         CASCADE;
DROP VIEW IF EXISTS v_c_weight_merged            CASCADE;

-- ════════════════════════════════════════════════════════════════
-- A.02 来料 BOM
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_raw_bom_merged AS
SELECT
    b.hf_part_no,
    b.seq_no,
    b.input_material_no,
    b.process_no,
    b.process_name,
    b.input_qty,
    b.input_unit,
    b.output_qty,
    b.output_unit,
    CAST(b.output_loss_rate * 100 AS NUMERIC(10,4)) AS output_loss_rate,
    b.fixed_loss_qty,
    CAST(b.loss_rate         * 100 AS NUMERIC(10,4)) AS loss_rate,
    b.part_version                                                            -- V161
FROM costing_part_material_bom b
WHERE b.is_active = true;
COMMENT ON VIEW v_c_raw_bom_merged IS
    'V142+V161: 来料BOM sheet — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.03 来料与元素 BOM (eb × mb JOIN by input_material_no + part_version 对齐)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_raw_element_bom_merged AS
SELECT
    mb.hf_part_no,
    mb.seq_no       AS material_seq_no,
    eb.input_material_no,
    eb.seq_no       AS element_seq_no,
    eb.element_code,
    CAST(eb.composition_pct * 100 AS NUMERIC(10,4)) AS composition_pct,
    CAST(eb.loss_rate        * 100 AS NUMERIC(10,4)) AS loss_rate,
    eb.part_version                                                            -- V161
FROM costing_part_element_bom eb
JOIN costing_part_material_bom mb
  ON mb.input_material_no = eb.input_material_no
 AND mb.part_version      = eb.part_version                                    -- V161 同版本对齐
 AND mb.is_active = true
WHERE eb.is_active = true;
COMMENT ON VIEW v_c_raw_element_bom_merged IS
    'V142+V161: 来料与元素BOM — JOIN 加 part_version 对齐, 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.04 LABOR
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_labor_cost_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit,
       ref_calc_version, notes, part_version                                   -- V161
FROM costing_part_process_cost
WHERE cost_type = 'LABOR' AND is_active = true;
COMMENT ON VIEW v_c_labor_cost_merged IS 'V142+V161: 人工成本 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.05 DEPRECIATION
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_depreciation_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit,
       ref_calc_version, notes, part_version                                   -- V161
FROM costing_part_process_cost
WHERE cost_type = 'DEPRECIATION' AND is_active = true;
COMMENT ON VIEW v_c_depreciation_merged IS 'V142+V161: 设备折旧成本 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.06 ENERGY_DEDICATED
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_energy_prod_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit,
       ref_calc_version, notes, part_version                                   -- V161
FROM costing_part_process_cost
WHERE cost_type = 'ENERGY_DEDICATED' AND is_active = true;
COMMENT ON VIEW v_c_energy_prod_merged IS 'V142+V161: 生产设备能耗 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.07 ENERGY_SHARED
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_energy_aux_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit,
       ref_calc_version, notes, part_version                                   -- V161
FROM costing_part_process_cost
WHERE cost_type = 'ENERGY_SHARED' AND is_active = true;
COMMENT ON VIEW v_c_energy_aux_merged IS 'V142+V161: 辅助设备能耗 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.08 模具工装
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_tooling_merged AS
SELECT
    hf_part_no, process_no, process_name, seq_no,
    tooling_no, tooling_unit_cost, process_count, cycle_count,
    unit_price, currency, unit, notes,
    part_version                                                               -- V161
FROM costing_part_tooling_cost
WHERE is_active = true;
COMMENT ON VIEW v_c_tooling_merged IS 'V142+V161: 模具工装成本 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.09 生产耗材 (CONSUMABLE 非包装)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_consumable_prod_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit,
       ref_calc_version, notes, part_version                                   -- V161
FROM costing_part_process_cost
WHERE cost_type = 'CONSUMABLE' AND is_active = true
  AND COALESCE(process_name, '') NOT LIKE '%包装%';
COMMENT ON VIEW v_c_consumable_prod_merged IS
    'V142+V161: 生产耗材 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.10 包装材料 (CONSUMABLE 含"包装")
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_packaging_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit,
       ref_calc_version, notes, part_version                                   -- V161
FROM costing_part_process_cost
WHERE cost_type = 'CONSUMABLE' AND is_active = true
  AND COALESCE(process_name, '') LIKE '%包装%';
COMMENT ON VIEW v_c_packaging_merged IS
    'V142+V161: 包装材料 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.11 来料加工费 (MATERIAL_PROC)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_incoming_proc_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit,
       ref_calc_version, notes, part_version                                   -- V161
FROM costing_part_process_cost
WHERE cost_type = 'MATERIAL_PROC' AND is_active = true;
COMMENT ON VIEW v_c_incoming_proc_merged IS 'V142+V161: 来料加工费 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.12 来料其他费用(比例) mat_fee[INCOMING_OTHER]
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_incoming_other_merged AS
SELECT
    hf_part_no,
    customer_id,
    seq_no,
    dim_input_material_no,
    dim_sub_seq_no,
    dim_element_name,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS fee_ratio,
    currency,
    price_unit,
    part_version                                                               -- V161
FROM mat_fee
WHERE fee_type = 'INCOMING_OTHER' AND is_current = true AND status = 'ACTIVE';
COMMENT ON VIEW v_c_incoming_other_merged IS
    'V142+V161: 来料其他费用(比例) — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.13 来料其他固定费用 mat_fee[INCOMING_FIXED]
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_incoming_fixed_fee_merged AS
SELECT
    hf_part_no,
    customer_id,
    seq_no,
    dim_input_material_no,
    dim_sub_seq_no,
    dim_element_name,
    fee_value,
    currency,
    price_unit,
    price_floating,
    settlement_rise_ratio,
    fixed_rise_value,
    part_version                                                               -- V161
FROM mat_fee
WHERE fee_type = 'INCOMING_FIXED' AND is_current = true AND status = 'ACTIVE';
COMMENT ON VIEW v_c_incoming_fixed_fee_merged IS
    'V142+V161: 来料其他固定费用 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.14 成品加工费&组装费 (SEMI_FINISHED_PROC)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_finished_proc_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit,
       ref_calc_version, notes, part_version                                   -- V161
FROM costing_part_process_cost
WHERE cost_type = 'SEMI_FINISHED_PROC' AND is_active = true;
COMMENT ON VIEW v_c_finished_proc_merged IS 'V142+V161: 成品加工费&组装费 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.15 成品其他比例费用 mat_fee[FINISHED_OTHER]
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_finished_other_merged AS
SELECT
    hf_part_no,
    customer_id,
    seq_no,
    dim_element_name,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS fee_ratio,
    currency,
    price_unit,
    part_version                                                               -- V161
FROM mat_fee
WHERE fee_type = 'FINISHED_OTHER' AND is_current = true AND status = 'ACTIVE';
COMMENT ON VIEW v_c_finished_other_merged IS
    'V142+V161: 成品其他比例费用 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.16 成品其他固定费用 mat_fee[FINISHED_FIXED]
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_finished_fixed_fee_merged AS
SELECT
    hf_part_no,
    customer_id,
    seq_no,
    dim_element_name,
    fee_value,
    currency,
    price_unit,
    part_version                                                               -- V161
FROM mat_fee
WHERE fee_type = 'FINISHED_FIXED' AND is_current = true AND status = 'ACTIVE';
COMMENT ON VIEW v_c_finished_fixed_fee_merged IS
    'V142+V161: 成品其他固定费用 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.17 电镀方案 (LEFT JOIN by plan_code + version + part_version)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_plating_scheme_merged AS
SELECT
    f.hf_part_no,
    f.plating_plan_code   AS plan_code,
    f.plan_version,
    cpp.seq_no,
    cpp.element_attr      AS plating_element,
    cpp.plating_area_cm2  AS plating_area,
    cpp.layer_thickness_um AS coating_thickness,
    cpp.requirement       AS plating_requirement,
    f.part_version                                                             -- V161
FROM costing_part_plating_fee f
LEFT JOIN costing_part_plating cpp
       ON cpp.plating_no       = f.plating_plan_code
      AND cpp.version_number    = f.plan_version
      AND cpp.part_version      = f.part_version                               -- V161 同版本对齐
      AND cpp.is_active = true
WHERE f.is_active = true;
COMMENT ON VIEW v_c_plating_scheme_merged IS
    'V142+V161: 电镀方案 — LEFT JOIN 加 part_version 对齐, 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.18 电镀成本
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_plating_cost_merged AS
SELECT
    hf_part_no,
    plating_plan_code AS plan_code,
    plan_version,
    plating_process_fee,
    plating_material_fee,
    currency,
    price_unit,
    CAST(defect_rate * 100 AS NUMERIC(10,4)) AS defect_rate,
    part_version                                                               -- V161
FROM costing_part_plating_fee
WHERE is_active = true;
COMMENT ON VIEW v_c_plating_cost_merged IS
    'V142+V161: 电镀成本 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.19 其他外加工 (POST_PROC)
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_outsource_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit,
       ref_calc_version, notes, part_version                                   -- V161
FROM costing_part_process_cost
WHERE cost_type = 'POST_PROC' AND is_active = true;
COMMENT ON VIEW v_c_outsource_merged IS 'V142+V161: 其他外加工成本 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- A.20 单重
-- ════════════════════════════════════════════════════════════════
CREATE VIEW v_c_weight_merged AS
SELECT hf_part_no, weight_g_per_pcs, notes, part_version                       -- V161
FROM costing_part_weight
WHERE is_active = true;
COMMENT ON VIEW v_c_weight_merged IS 'V142+V161: 单重 — 已暴露 part_version';

-- ════════════════════════════════════════════════════════════════
-- 完成自检通知
-- ════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_cnt_with_pv INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt_with_pv
    FROM information_schema.columns
    WHERE table_name IN (
        'v_c_raw_bom_merged','v_c_raw_element_bom_merged',
        'v_c_labor_cost_merged','v_c_depreciation_merged',
        'v_c_energy_prod_merged','v_c_energy_aux_merged',
        'v_c_tooling_merged','v_c_consumable_prod_merged','v_c_packaging_merged',
        'v_c_incoming_proc_merged','v_c_incoming_other_merged','v_c_incoming_fixed_fee_merged',
        'v_c_finished_proc_merged','v_c_finished_other_merged','v_c_finished_fixed_fee_merged',
        'v_c_plating_scheme_merged','v_c_plating_cost_merged',
        'v_c_outsource_merged','v_c_weight_merged')
      AND column_name = 'part_version';

    RAISE NOTICE 'V161 self-check: v_c_*_merged views with part_version = % / 19', v_cnt_with_pv;
    IF v_cnt_with_pv = 19 THEN
        RAISE NOTICE 'V161 OK: all 19 v_c views exposed part_version';
    ELSE
        RAISE WARNING 'V161 WARN: some views missed part_version, check log above';
    END IF;
    RAISE NOTICE 'V161: touch a java file to trigger Quarkus reload';
    RAISE NOTICE 'V161: clears ImplicitJoinRewriter.tableColumnsCache + CachedSqlCompiler';
END $$;
