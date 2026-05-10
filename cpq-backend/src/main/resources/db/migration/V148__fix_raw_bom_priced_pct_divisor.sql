-- ============================================================
-- V148: 修复 v_c_raw_bom_priced 视图的百分比字段单位
--
-- 问题: V147 创建的 v_c_raw_bom_priced 视图中:
--   - elem_pct_decimal = eb.composition_pct  (未除以100, DB存百分比如20.0表示20%)
--   - elem_loss_rate   = eb.loss_rate        (未除以100, DB存百分比如2.0表示2%)
--   - elem_discount    = cep.discount_rate   (未除以100, DB存百分比如85.0表示85%)
--   - mat_discount     = cmp.discount_rate   (未除以100)
--   - unit_price = elem_price * composition_pct (没除100, 结果偏大100倍)
--
-- 正确做法: 与 V111 bom_expanded 单位保持一致, 全部除以100得小数:
--   V111: COALESCE(eb.composition_pct, 0)/100.0 AS elem_pct
--   V111: COALESCE(eb.loss_rate, 0)/100.0       AS elem_loss_rate
--   V111: COALESCE(cep.discount_rate, 0)/100.0  AS elem_discount
--   V111: COALESCE(cmp.discount_rate, 0)/100.0  AS mat_discount
--   V111: unit_price = elem_price*elem_pct + mat_price*(element_code IS NULL)
--
-- 根因: V111 SQL 里 costing_part_material_bom.loss_rate 已是小数
--       (V142 视图里 ×100 是为显示%; 底层表存小数),
--       但 costing_part_element_bom.composition_pct / costing_*_price.discount_rate
--       在底层表存百分比整数 (20 = 20%), 需除以100才与V111对齐.
--
-- 额外修复: 加 @Transactional(REQUIRED) 到 evaluateFormula 解决 Component.list 无Session问题
--          → 见 TemplateFormulaService.java 的同步修改
-- ============================================================

DROP VIEW IF EXISTS v_c_raw_bom_priced;

CREATE VIEW v_c_raw_bom_priced AS
SELECT
    m.hf_part_no,
    m.seq_no,
    m.input_material_no,
    m.process_no,
    m.process_name,
    m.input_qty,
    m.output_qty,
    -- loss_rate: costing_part_material_bom 底层表存小数 (e.g. 0.003), 不需除100
    COALESCE(m.loss_rate, 0)              AS loss_rate,
    COALESCE(m.fixed_loss_qty, 0)         AS fixed_loss_qty,
    eb.element_code,
    -- composition_pct 底层表存百分比整数 (e.g. 20.0 = 20%), 除以100得小数
    COALESCE(eb.composition_pct, 0)/100.0 AS elem_pct_decimal,
    -- element_bom.loss_rate 底层表存百分比整数 (e.g. 2.0 = 2%), 除以100得小数
    COALESCE(eb.loss_rate, 0)/100.0       AS elem_loss_rate,
    COALESCE(cep.costing_price, 0)        AS elem_price,
    -- discount_rate 在价格表存百分比整数 (e.g. 85.0 = 85%), 除以100得小数
    COALESCE(cep.discount_rate, 0)/100.0  AS elem_discount,
    COALESCE(cmp.costing_price, 0)        AS mat_price,
    COALESCE(cmp.discount_rate, 0)/100.0  AS mat_discount,
    -- unit_price = V111 bom_priced.unit_price:
    --   elem_price * elem_pct + mat_price * (element_code IS NULL ? 1 : 0)
    (CASE WHEN eb.element_code IS NULL
          THEN COALESCE(cmp.costing_price, 0)
          ELSE COALESCE(cep.costing_price, 0) * COALESCE(eb.composition_pct, 0) / 100.0
     END) AS unit_price,
    -- unit_price_recycle = V111 bom_priced.unit_price_recycle:
    --   elem_price*elem_pct*elem_discount + mat_price*mat_discount*(element_code IS NULL)
    (CASE WHEN eb.element_code IS NULL
          THEN COALESCE(cmp.costing_price, 0) * COALESCE(cmp.discount_rate, 0) / 100.0
          ELSE COALESCE(cep.costing_price, 0) * COALESCE(eb.composition_pct, 0) / 100.0
               * COALESCE(cep.discount_rate, 0) / 100.0
     END) AS unit_price_recycle,
    -- bom_kind 供 SUM_OVER WHERE 谓词过滤
    (CASE WHEN m.input_qty >= 0 THEN 'NORMAL' ELSE 'RECYCLE' END) AS bom_kind
FROM costing_part_material_bom m
LEFT JOIN costing_part_element_bom eb
    ON eb.input_material_no = m.input_material_no
   AND COALESCE(eb.is_active, true) = true
LEFT JOIN v_costing_element_price cep
    ON cep.element_code = eb.element_code
LEFT JOIN v_costing_material_price cmp
    ON cmp.material_no = m.input_material_no
WHERE COALESCE(m.is_active, true) = true;

COMMENT ON VIEW v_c_raw_bom_priced IS
    'V148 修复: 来料BOM × 元素BOM × 元素价 × 材料价 四表合并视图。'
    '字段单位与 V111 bom_expanded 完全一致: '
    'loss_rate(小数,底层表原值), '
    'elem_pct_decimal=composition_pct/100, '
    'elem_loss_rate=loss_rate/100, '
    'elem_discount=discount_rate/100, '
    'mat_discount=discount_rate/100, '
    'unit_price=elem_price*(composition_pct/100) or mat_price. '
    '专供 SUM_OVER 模板公式 (纯材料成本/回收成本/材料损耗成本) 使用。';

DO $$
DECLARE
    v_exists BOOLEAN;
BEGIN
    SELECT EXISTS(SELECT 1 FROM pg_views WHERE viewname = 'v_c_raw_bom_priced') INTO v_exists;
    IF v_exists THEN
        RAISE NOTICE 'V148 OK: v_c_raw_bom_priced 视图已重建，百分比字段 /100 已修正';
        RAISE NOTICE 'V148 验证: 期望 elem_pct_decimal = composition_pct/100 (如20.0→0.20)';
        RAISE NOTICE 'V148 验证: 期望 unit_price = elem_price * 0.20 (而非 elem_price * 20)';
    ELSE
        RAISE WARNING 'V148 FAIL: v_c_raw_bom_priced 重建失败';
    END IF;
END $$;
