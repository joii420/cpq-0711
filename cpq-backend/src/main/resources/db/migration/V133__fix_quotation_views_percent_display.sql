-- V133: 修复 V128 报价视图百分比显示
-- 背景: mat_bom.loss_rate / defect_rate / composition_pct / recycle_pct 以及
--       mat_fee.fee_ratio / settlement_rise_ratio / reject_rate 在导入时
--       toDecimalPercent 把 Excel "3" -> 0.03 写入 DB。
--       V128 视图直接 SELECT 这些列，前端「(%)」列显示 0.03 而非 3。
--
-- 实施方式: 先 DROP CASCADE（保证列类型可以变更），再 CREATE VIEW。
--   注意: CREATE OR REPLACE VIEW 不允许改变现有列的数据类型，故必须 DROP。
--   CAST 为 NUMERIC(10,4) 保持视图列类型与原 V128 一致，BNF path 不变。
--
-- 受影响视图:
--   v_q_incoming_merged -- loss_rate, defect_rate (mat_bom); fee_ratio, settlement_rise_ratio (mat_fee); recycle_pct (MATERIAL_RECYCLE)
--   v_q_element_merged  -- composition_pct, loss_rate (mat_bom); recycle_pct (ELEMENT_RECYCLE fee_ratio)
--   v_q_finished_merged -- fee_ratio (mat_fee)
--   v_q_plating_merged  -- defect_rate (plating_fee)
--
-- 不动的列: fee_value, unit_price, freight, gross_qty, net_qty, quantity 等非百分比数值字段。
-- CLAUDE.md 规定: DROP CASCADE 后 Quarkus 必须重启（视图列结构变更）。

-- ============================================================
-- DROP CASCADE（无后续迁移依赖这 4 个视图）
-- ============================================================
DROP VIEW IF EXISTS v_q_incoming_merged CASCADE;
DROP VIEW IF EXISTS v_q_element_merged CASCADE;
DROP VIEW IF EXISTS v_q_finished_merged CASCADE;
DROP VIEW IF EXISTS v_q_plating_merged CASCADE;

-- ============================================================
-- 1. v_q_incoming_merged -- 来料（4 来源 UNION ALL）
-- ============================================================
CREATE VIEW v_q_incoming_merged AS
-- 来源 1: 来料 BOM（loss_rate / defect_rate x100）
SELECT
    'BOM'::VARCHAR               AS source_type,
    hf_part_no,
    seq_no,
    input_material_no,
    input_material_name,
    output_material_type,
    gross_qty,
    net_qty,
    gross_unit                   AS weight_unit,
    CAST(loss_rate   * 100 AS NUMERIC(10,4)) AS loss_rate,
    CAST(defect_rate * 100 AS NUMERIC(10,4)) AS defect_rate,
    NULL::INT                    AS sub_seq_no,
    NULL::VARCHAR(128)           AS element_name,
    NULL::DECIMAL(18,4)          AS fee_value,
    NULL::DECIMAL(10,4)          AS fee_ratio,
    NULL::VARCHAR(8)             AS currency,
    NULL::VARCHAR(16)            AS price_unit,
    NULL::BOOLEAN                AS price_floating,
    NULL::DECIMAL(10,4)          AS settlement_rise_ratio,
    NULL::DECIMAL(18,4)          AS fixed_rise_value,
    NULL::VARCHAR(8)             AS rise_currency,
    NULL::VARCHAR(16)            AS rise_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct
FROM mat_bom
WHERE bom_type = 'INCOMING'

UNION ALL

-- 来源 2: 来料固定加工费（fee_ratio / settlement_rise_ratio x100）
SELECT
    'INCOMING_FIXED'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    NULL::VARCHAR(64)            AS output_material_type,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS weight_unit,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(10,4)          AS defect_rate,
    NULL::INT                    AS sub_seq_no,
    NULL::VARCHAR(128)           AS element_name,
    fee_value,
    CAST(fee_ratio              * 100 AS NUMERIC(10,4)) AS fee_ratio,
    currency,
    price_unit,
    price_floating,
    CAST(settlement_rise_ratio  * 100 AS NUMERIC(10,4)) AS settlement_rise_ratio,
    fixed_rise_value,
    rise_currency,
    rise_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct
FROM mat_fee
WHERE fee_type = 'INCOMING_FIXED'
  AND is_current = true

UNION ALL

-- 来源 3: 来料其他费用（fee_ratio x100）
SELECT
    'INCOMING_OTHER'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    NULL::VARCHAR(64)            AS output_material_type,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS weight_unit,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(10,4)          AS defect_rate,
    dim_sub_seq_no               AS sub_seq_no,
    dim_element_name             AS element_name,
    fee_value,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS fee_ratio,
    currency,
    price_unit,
    NULL::BOOLEAN                AS price_floating,
    NULL::DECIMAL(10,4)          AS settlement_rise_ratio,
    NULL::DECIMAL(18,4)          AS fixed_rise_value,
    NULL::VARCHAR(8)             AS rise_currency,
    NULL::VARCHAR(16)            AS rise_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct
FROM mat_fee
WHERE fee_type = 'INCOMING_OTHER'
  AND is_current = true

UNION ALL

-- 来源 4: 来料回收折扣（fee_ratio -> recycle_pct x100）
SELECT
    'MATERIAL_RECYCLE'::VARCHAR  AS source_type,
    hf_part_no,
    seq_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    NULL::VARCHAR(64)            AS output_material_type,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS weight_unit,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(10,4)          AS defect_rate,
    NULL::INT                    AS sub_seq_no,
    NULL::VARCHAR(128)           AS element_name,
    NULL::DECIMAL(18,4)          AS fee_value,
    NULL::DECIMAL(10,4)          AS fee_ratio,
    NULL::VARCHAR(8)             AS currency,
    NULL::VARCHAR(16)            AS price_unit,
    NULL::BOOLEAN                AS price_floating,
    NULL::DECIMAL(10,4)          AS settlement_rise_ratio,
    NULL::DECIMAL(18,4)          AS fixed_rise_value,
    NULL::VARCHAR(8)             AS rise_currency,
    NULL::VARCHAR(16)            AS rise_unit,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS recycle_pct
FROM mat_fee
WHERE fee_type = 'MATERIAL_RECYCLE'
  AND is_current = true;

COMMENT ON VIEW v_q_incoming_merged IS
    'V128+V133: 来料合并视图 -- 百分比列已 x100 显示（loss_rate/defect_rate/fee_ratio/settlement_rise_ratio/recycle_pct）';

-- ============================================================
-- 2. v_q_element_merged -- 元素（2 来源 UNION ALL）
-- ============================================================
CREATE VIEW v_q_element_merged AS
-- 来源 1: 元素 BOM（composition_pct / loss_rate x100）
SELECT
    'BOM'::VARCHAR               AS source_type,
    hf_part_no,
    input_material_no,
    input_material_name,
    seq_no,
    element_name,
    CAST(composition_pct * 100 AS NUMERIC(10,4)) AS composition_pct,
    CAST(loss_rate       * 100 AS NUMERIC(10,4)) AS loss_rate,
    gross_qty,
    gross_unit,
    net_qty,
    net_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct
FROM mat_bom
WHERE bom_type = 'ELEMENT'

UNION ALL

-- 来源 2: 元素回收折扣（fee_ratio -> recycle_pct x100）
SELECT
    'ELEMENT_RECYCLE'::VARCHAR   AS source_type,
    hf_part_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    seq_no,
    dim_element_name             AS element_name,
    NULL::DECIMAL(10,4)          AS composition_pct,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::VARCHAR(16)            AS gross_unit,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS net_unit,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS recycle_pct
FROM mat_fee
WHERE fee_type = 'ELEMENT_RECYCLE'
  AND is_current = true;

COMMENT ON VIEW v_q_element_merged IS
    'V128+V133: 元素合并视图 -- 百分比列已 x100 显示（composition_pct/loss_rate/recycle_pct）';

-- ============================================================
-- 3. v_q_finished_merged -- 成品（2 来源 UNION ALL）
-- ============================================================
CREATE VIEW v_q_finished_merged AS
-- 来源 1: 成品固定加工费（fee_ratio x100）
SELECT
    'FINISHED_FIXED'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    NULL::VARCHAR(128)           AS element_name,
    fee_value,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS fee_ratio,
    currency,
    price_unit
FROM mat_fee
WHERE fee_type = 'FINISHED_FIXED'
  AND is_current = true

UNION ALL

-- 来源 2: 成品其他费用（fee_ratio x100）
SELECT
    'FINISHED_OTHER'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    dim_element_name             AS element_name,
    fee_value,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS fee_ratio,
    currency,
    price_unit
FROM mat_fee
WHERE fee_type = 'FINISHED_OTHER'
  AND is_current = true;

COMMENT ON VIEW v_q_finished_merged IS
    'V128+V133: 成品合并视图 -- 百分比列已 x100 显示（fee_ratio）';

-- ============================================================
-- 4. v_q_plating_merged -- 电镀（2 来源 UNION ALL）
-- ============================================================
CREATE VIEW v_q_plating_merged AS
-- 来源 1: 电镀方案（全局，hf_part_no 为 NULL，无百分比列需修改）
SELECT
    'PLAN'::VARCHAR              AS source_type,
    NULL::VARCHAR(64)            AS hf_part_no,
    plan_code,
    version                      AS plan_version,
    seq_no,
    plating_element,
    plating_area,
    coating_thickness,
    plating_requirement,
    NULL::DECIMAL(18,4)          AS plating_process_fee,
    NULL::DECIMAL(18,4)          AS plating_material_fee,
    NULL::VARCHAR(8)             AS currency,
    NULL::VARCHAR(16)            AS price_unit,
    NULL::DECIMAL(10,4)          AS defect_rate
FROM plating_plan

UNION ALL

-- 来源 2: 电镀费用（defect_rate x100）
SELECT
    'FEE'::VARCHAR               AS source_type,
    hf_part_no,
    plating_plan_code            AS plan_code,
    plan_version,
    NULL::INT                    AS seq_no,
    NULL::VARCHAR(64)            AS plating_element,
    NULL::DECIMAL(18,4)          AS plating_area,
    NULL::DECIMAL(10,4)          AS coating_thickness,
    NULL::VARCHAR(256)           AS plating_requirement,
    plating_process_fee,
    plating_material_fee,
    currency,
    price_unit,
    CAST(defect_rate * 100 AS NUMERIC(10,4)) AS defect_rate
FROM plating_fee
WHERE is_current = true;

COMMENT ON VIEW v_q_plating_merged IS
    'V128+V133: 电镀合并视图 -- 百分比列已 x100 显示（defect_rate）';

-- ============================================================
-- 完成通知
-- ============================================================
DO $$
BEGIN
    RAISE NOTICE 'V133: 4 视图百分比修复完成 (v_q_incoming_merged / v_q_element_merged / v_q_finished_merged / v_q_plating_merged)';
END $$;
