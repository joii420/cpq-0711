-- V135: 修复 v_q_element_merged 中 composition_pct ×100 误伤
--
-- 根因：
--   mat_bom.composition_pct 列存储"百分比整数形式"（75 = 75%）。
--   BasicDataImportServiceV5.java fillMatBomRow 用 toDecimal()（非 toDecimalPercent）写入，
--   line 551-552 注释明确"composition_pct 保留 100 制"。
--   V133 把 v_q_element_merged 中 composition_pct 也做了 ×100，
--   导致 75 → 7500，前端显示 7500/2500/3000/7000。
--
-- 修复方案：
--   DROP CASCADE + 重建 v_q_element_merged，composition_pct 改回直接 SELECT（不 ×100）。
--   其他百分比列（loss_rate / recycle_pct）仍保持 V133 的 ×100 逻辑（存的是 0.03 形式）。
--
-- 视图列语义确认：
--   ÷100后存（×100才能正确显示）: fee_ratio, loss_rate, recycle_pct
--   整数百分比直存（不能再 ×100）: composition_pct
--
-- CLAUDE.md 规定: DROP CASCADE 后 Quarkus 必须重启（touch 一个 java 文件触发 dev reload）。

-- ============================================================
-- DROP CASCADE（移除旧视图）
-- ============================================================
DROP VIEW IF EXISTS v_q_element_merged CASCADE;

-- ============================================================
-- 重建 v_q_element_merged（composition_pct 去掉 ×100）
-- ============================================================
CREATE VIEW v_q_element_merged AS

-- 来源 1: 元素 BOM（composition_pct 直接SELECT；loss_rate ×100）
SELECT
    'BOM'::VARCHAR               AS source_type,
    hf_part_no,
    input_material_no,
    input_material_name,
    seq_no,
    element_name,
    composition_pct,                                           -- 整数百分比直存，不 ×100
    CAST(loss_rate * 100 AS NUMERIC(10,4)) AS loss_rate,       -- ÷100后存，×100恢复显示
    gross_qty,
    gross_unit,
    net_qty,
    net_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct
FROM mat_bom
WHERE bom_type = 'ELEMENT'

UNION ALL

-- 来源 2: 元素回收折扣（fee_ratio -> recycle_pct ×100）
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
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS recycle_pct      -- ÷100后存，×100恢复显示
FROM mat_fee
WHERE fee_type = 'ELEMENT_RECYCLE'
  AND is_current = true;

COMMENT ON VIEW v_q_element_merged IS
    'V128+V133+V135: 元素合并视图 -- composition_pct 整数百分比直存不×100；loss_rate/recycle_pct 已×100显示';

-- ============================================================
-- 完成通知
-- ============================================================
DO $$
BEGIN
    RAISE NOTICE 'V135: composition_pct 误伤已修复';
    RAISE NOTICE 'V135: 提醒 — DROP CASCADE 后请 touch 一个 java 文件触发 Quarkus dev 重启，清空视图列缓存';
END $$;
