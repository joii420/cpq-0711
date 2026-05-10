-- V111: v_costing_summary_full 去掉 COALESCE(_, 0), 让无数据料号的字段自然 NULL 传递
--
-- 起因: 报价单 QT-20260507-1347 的 3 个料号中, 只有 3100080003 有真实 BOM/工序/单重数据,
--       另两个料号 (3120012574/3120012575) 在 costing_summary 表有 PUBLISHED 行但实际数据缺失.
--       V95 视图用 COALESCE(_, 0) 把缺失字段填 0, 导致 Excel 视图显示 0 而非空白, 用户误以为"有数据".
--
-- 修复: SELECT 阶段不再 COALESCE 缺失字段, NULL 自然传递. 前端 path token 求值返 null →
--       VARIABLE 列显示空白 → 配合前端 LinkedExcelView 识别全 NULL 行整行清空.
--
-- 不破坏: 公式列 evaluateFormula 把 NaN 当 0, 但前端会在 row 生成时检测全 NULL 跳过整行公式渲染.
-- 单位说明保持 V110 一致.

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
    -- ★ V111: 去掉 COALESCE(_, 0); 无数据料号字段 NULL 传递, 前端能识别空白
    ma.pure_material_cost                                              AS pure_material_cost,
    ma.recycle_cost                                                    AS recycle_cost,
    ma.material_loss_cost                                              AS material_loss_cost,
    pc.incoming_process_fee                                            AS incoming_process_fee,
    ma.pure_material_cost * io.incoming_other_total_ratio              AS incoming_other_fee,
    pc.process_fee_base * (1 + COALESCE(
        (SELECT bom_loss_rate FROM bom_priced WHERE hf_part_no = a.hf_part_no LIMIT 1), 0
    ))                                                                  AS process_fee_total,
    pld.plating_process_fee                                            AS plating_process_fee,
    pld.plating_material_fee                                           AS plating_material_fee,
    pld.plating_defect_rate                                            AS plating_defect_rate,
    -- 外加工占位 0; 留给以后 fee_type='OUTSOURCE' 接入. NULL 不合适因这是已知"无", 不是"未配置"
    CAST(0 AS numeric)                                                 AS outsource_fee_total,
    w.weight_g_per_pcs                                                 AS unit_weight_g,
    e.exchange_rate_to_usd                                             AS exchange_rate_to_usd,
    fr.mgmt_fee_ratio                                                  AS mgmt_fee_ratio,
    fr.finance_fee_ratio                                               AS finance_fee_ratio,
    fr.profit_ratio                                                    AS profit_ratio,
    fr.tax_ratio                                                       AS tax_ratio
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
    'V111: 去掉 COALESCE(_, 0), 无数据料号的成本字段 NULL 传递; 前端识别整行 NULL 显示空白';
