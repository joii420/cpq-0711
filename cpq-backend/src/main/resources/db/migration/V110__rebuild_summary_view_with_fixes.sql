-- V110: 重建 v_costing_summary_full 视图 + 修复 V95 遗留的计算 bug
--
-- ═══════════════════════════════════════════════════════════════════
-- 起因: V109 中 `DROP VIEW v_costing_element_price CASCADE` 把所有依赖此视图的
--       下游视图连带删了, 包括 v_costing_summary_full. 「核价Excel视图模板（完整公式版）」
--       的 22 个 VARIABLE 列全部引用此视图 → 模板瘫痪.
-- ═══════════════════════════════════════════════════════════════════
--
-- 同时审计 V95 视图发现 2 个计算 bug, 一并修:
--
--   Bug A (严重) — plating_defect_rate 二次除法
--     plating_fee.defect_rate 已是小数 (0.005 = 0.5%) — V101 注释明确
--     V95 视图: AVG(defect_rate)/100.0 = 0.005/100 = 0.00005 (错放小 100 倍)
--     公式 (1+E_DEFECT) ≈ 1.00005, 不良率几乎归零, 电镀成本 ≈ (加工+材料)
--     修: 去掉 /100.0
--
--   Bug B (致命) — fee_ratios 匹配错误
--     V95 视图 hardcode dim_element_name = '管理费'/'财务费'/'利润'/'税费'
--     实际数据 dim_element_name = '材料管理费'/'财务管理费'/'回收费' (来自 mat_fee 导入)
--     结果: mgmt/finance/profit/tax 4 个比例字段全部 NULL → COALESCE → 0
--          列 H/J/L/N 都返 0, 列 I/K/M/O 算出来都是 0, 商业加价完全失效
--     修: 用 LIKE 匹配中文关键字, 用 SUM 处理同名重复行
--
-- 单位说明 (本次保留 V95 原语义):
--   costing_part_element_bom.composition_pct: 百分比 (20 = 20%) → 视图 /100
--   costing_part_element_bom.loss_rate:        百分比 (2  = 2%)  → 视图 /100
--   costing_part_material_bom.loss_rate:       存储惯例待定 (现有数据全 0); V95 /100, 暂保留
--   plating_fee.defect_rate:                   小数 (0.005 = 0.5%) → ★本次去掉 /100
--   mat_fee.fee_ratio:                         小数 (0.05 = 5%) → 视图直读不转换
--
-- 注意: 此 SQL 是 IDEMPOTENT (DROP IF EXISTS + CREATE), 可重复执行.

DROP VIEW IF EXISTS v_costing_summary_full CASCADE;

CREATE VIEW v_costing_summary_full AS
WITH
bom_expanded AS (
    SELECT
        m.hf_part_no,
        m.input_qty                                  AS bom_input_qty,
        NULLIF(m.output_qty, 0)                      AS bom_output_qty,
        COALESCE(m.loss_rate, 0)/100.0               AS bom_loss_rate,
        COALESCE(m.fixed_loss_qty, 0)                AS bom_fixed_loss_qty,
        eb.element_code,
        COALESCE(eb.composition_pct, 0)/100.0        AS elem_pct,
        COALESCE(eb.loss_rate, 0)/100.0              AS elem_loss_rate,
        COALESCE(cep.costing_price, 0)               AS elem_price,
        COALESCE(cep.discount_rate, 0)/100.0         AS elem_discount,
        COALESCE(cmp.costing_price, 0)               AS mat_price,
        COALESCE(cmp.discount_rate, 0)/100.0         AS mat_discount,
        CASE WHEN m.input_qty >= 0 THEN 'NORMAL' ELSE 'RECYCLE' END AS bom_kind
    FROM costing_part_material_bom m
    LEFT JOIN costing_part_element_bom eb
        ON eb.input_material_no = m.input_material_no
       AND COALESCE(eb.is_active, true) = true
    LEFT JOIN v_costing_element_price cep ON cep.element_code = eb.element_code
    LEFT JOIN v_costing_material_price cmp ON cmp.material_no = m.input_material_no
    WHERE COALESCE(m.is_active, true) = true
),
bom_priced AS (
    SELECT
        hf_part_no,
        bom_input_qty, bom_output_qty, bom_loss_rate, bom_fixed_loss_qty,
        elem_loss_rate, bom_kind,
        elem_price * elem_pct + mat_price * (CASE WHEN element_code IS NULL THEN 1 ELSE 0 END) AS unit_price,
        elem_price * elem_pct * elem_discount
            + mat_price * mat_discount * (CASE WHEN element_code IS NULL THEN 1 ELSE 0 END)    AS unit_price_recycle
    FROM bom_expanded
),
material_aggs AS (
    SELECT
        hf_part_no,
        SUM(CASE WHEN bom_kind = 'NORMAL' THEN
                ABS(bom_input_qty) / NULLIF(bom_output_qty, 0)
                * (1 + bom_loss_rate)
                * unit_price
            ELSE 0 END) AS pure_material_cost,
        SUM(CASE WHEN bom_kind = 'RECYCLE' THEN
                ABS(bom_input_qty) / NULLIF(bom_output_qty, 0)
                * unit_price_recycle
            ELSE 0 END) AS recycle_cost,
        SUM(CASE WHEN bom_kind = 'NORMAL' THEN
                ABS(bom_input_qty) / NULLIF(bom_output_qty, 0)
                * (1 + bom_loss_rate) * elem_loss_rate * unit_price
                + bom_fixed_loss_qty * unit_price
            ELSE 0 END) AS material_loss_cost
    FROM bom_priced
    GROUP BY hf_part_no
),
process_costs AS (
    SELECT
        pc.hf_part_no,
        SUM(CASE WHEN pc.cost_type = 'MATERIAL_PROC' THEN pc.unit_price ELSE 0 END) AS incoming_process_fee,
        SUM(CASE WHEN pc.cost_type IN
                 ('LABOR','DEPRECIATION','ENERGY_DEDICATED','ENERGY_SHARED','CONSUMABLE','SEMI_FINISHED_PROC','POST_PROC')
                 THEN pc.unit_price ELSE 0 END) AS process_fee_base
    FROM costing_part_process_cost pc
    WHERE COALESCE(pc.is_active, true) = true
    GROUP BY pc.hf_part_no
),
plating_data AS (
    SELECT
        pf.hf_part_no,
        SUM(COALESCE(pf.plating_process_fee, 0))   AS plating_process_fee,
        SUM(COALESCE(pf.plating_material_fee, 0))  AS plating_material_fee,
        -- ★ V110-A 修复: defect_rate 已是小数 (0.005=0.5%), 不再二次 /100
        AVG(COALESCE(pf.defect_rate, 0))           AS plating_defect_rate
    FROM plating_fee pf
    WHERE COALESCE(pf.is_current, true) = true
    GROUP BY pf.hf_part_no
),
weight_data AS (
    SELECT hf_part_no, weight_g_per_pcs
    FROM costing_part_weight
    WHERE COALESCE(is_active, true) = true
),
exchange_data AS (
    SELECT costing_rate AS exchange_rate_to_usd
    FROM v_costing_exchange_rate
    WHERE from_currency = 'CNY' AND to_currency = 'USD'
    LIMIT 1
),
-- ★ V110-B 修复: dim_element_name 实际是 "材料管理费"/"财务管理费"/"利润" 等 (导入数据决定),
--    用 LIKE 模糊匹配关键字; 用 SUM 处理同名重复行 (V95 用 MAX 会丢一些)
fee_ratios AS (
    SELECT
        f.hf_part_no,
        SUM(CASE WHEN f.dim_element_name LIKE '%管理%'   THEN COALESCE(f.fee_ratio, 0) ELSE 0 END) AS mgmt_fee_ratio,
        SUM(CASE WHEN f.dim_element_name LIKE '%财务%'   THEN COALESCE(f.fee_ratio, 0) ELSE 0 END) AS finance_fee_ratio,
        SUM(CASE WHEN f.dim_element_name LIKE '%利润%'   THEN COALESCE(f.fee_ratio, 0) ELSE 0 END) AS profit_ratio,
        SUM(CASE WHEN f.dim_element_name LIKE '%税%'    THEN COALESCE(f.fee_ratio, 0) ELSE 0 END) AS tax_ratio
    FROM mat_fee f
    WHERE f.fee_type = 'FINISHED_OTHER' AND COALESCE(f.is_current, true) = true
    GROUP BY f.hf_part_no
),
incoming_other AS (
    SELECT
        f.hf_part_no,
        SUM(COALESCE(f.fee_ratio, 0)) AS incoming_other_total_ratio
    FROM mat_fee f
    WHERE f.fee_type = 'INCOMING_OTHER' AND COALESCE(f.is_current, true) = true
    GROUP BY f.hf_part_no
),
agg_old AS (
    SELECT
        s.id AS summary_id,
        s.summary_no,
        s.hf_part_no,
        s.status,
        s.quote_currency,
        s.element_version_id,
        s.material_version_id,
        s.exchange_version_id,
        MAX(CASE WHEN r.metric_code = 'MATERIAL_COST' THEN r.value END) AS metric_material_cost,
        MAX(CASE WHEN r.metric_code = 'PROCESS_FEE'   THEN r.value END) AS metric_processing_cost
    FROM costing_summary s
    LEFT JOIN costing_summary_result r ON r.summary_id = s.id
    GROUP BY s.id, s.summary_no, s.hf_part_no, s.status, s.quote_currency,
             s.element_version_id, s.material_version_id, s.exchange_version_id
)
SELECT
    a.summary_id,
    a.summary_no,
    a.hf_part_no,
    (ROW_NUMBER() OVER (PARTITION BY a.hf_part_no ORDER BY a.summary_no))::int AS line_seq,
    a.status,
    CASE a.status WHEN 'PUBLISHED' THEN '是' ELSE '否' END AS is_published_label,
    a.quote_currency,
    'KG'::varchar(10) AS weight_unit,
    ev.version_number AS element_version_number,
    mv.version_number AS material_version_number,
    xv.version_number AS exchange_version_number,
    a.metric_material_cost     AS material_cost,
    a.metric_processing_cost   AS processing_cost,
    COALESCE(ma.pure_material_cost,    0) AS pure_material_cost,
    COALESCE(ma.recycle_cost,          0) AS recycle_cost,
    COALESCE(ma.material_loss_cost,    0) AS material_loss_cost,
    COALESCE(pc.incoming_process_fee,  0) AS incoming_process_fee,
    COALESCE(ma.pure_material_cost * io.incoming_other_total_ratio, 0) AS incoming_other_fee,
    COALESCE(pc.process_fee_base * (1 + COALESCE(
        (SELECT bom_loss_rate FROM bom_priced WHERE hf_part_no = a.hf_part_no LIMIT 1), 0
    )), 0) AS process_fee_total,
    COALESCE(pld.plating_process_fee,  0) AS plating_process_fee,
    COALESCE(pld.plating_material_fee, 0) AS plating_material_fee,
    COALESCE(pld.plating_defect_rate,  0) AS plating_defect_rate,
    CAST(0 AS numeric)                    AS outsource_fee_total,
    COALESCE(w.weight_g_per_pcs,       0) AS unit_weight_g,
    COALESCE(e.exchange_rate_to_usd,   0) AS exchange_rate_to_usd,
    COALESCE(fr.mgmt_fee_ratio,        0) AS mgmt_fee_ratio,
    COALESCE(fr.finance_fee_ratio,     0) AS finance_fee_ratio,
    COALESCE(fr.profit_ratio,          0) AS profit_ratio,
    COALESCE(fr.tax_ratio,             0) AS tax_ratio
FROM agg_old a
LEFT JOIN material_aggs ma   ON ma.hf_part_no = a.hf_part_no
LEFT JOIN process_costs pc   ON pc.hf_part_no = a.hf_part_no
LEFT JOIN plating_data  pld  ON pld.hf_part_no = a.hf_part_no
LEFT JOIN weight_data   w    ON w.hf_part_no = a.hf_part_no
LEFT JOIN exchange_data e    ON true
LEFT JOIN fee_ratios    fr   ON fr.hf_part_no = a.hf_part_no
LEFT JOIN incoming_other io  ON io.hf_part_no = a.hf_part_no
LEFT JOIN costing_price_version ev ON ev.id = a.element_version_id
LEFT JOIN costing_price_version mv ON mv.id = a.material_version_id
LEFT JOIN costing_price_version xv ON xv.id = a.exchange_version_id;

COMMENT ON VIEW v_costing_summary_full IS
    'V110: 重建版 (V109 CASCADE 误删后); 修复 plating_defect_rate 二次除法 + fee_ratios LIKE 匹配 + SUM 重复行';
