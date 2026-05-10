-- V95: 扩展 v_costing_summary_full 视图, 暴露所有 v4 Excel 公式所需的中间值
--
-- 目的: 把 v4 核价 Excel「汇总」行的 9 个红色公式单元格 (材料成本/材料损耗/加工费/电镀/
--       其他外加工/管理/财务/利润/税费/总成本) 在 Excel 视图模板里都标成 FORMULA。
--
-- 架构: 视图 SQL 做 ∑ 聚合 (一次查询读完所有 BOM/工序/电镀/费用/价格/比例) →
--       模板 FORMULA 列做 scalar 简单算术 (=[X]+[Y]-[Z]) → 透明可读, admin 改基础数据立即生效。
--
-- 不动现有 compute() Java 服务: 它继续写 MATERIAL_COST/PROCESS_FEE 等 7 个 metric
-- 到 costing_summary_result 表 (向后兼容); 新增中间值由本视图直接 SQL 计算, 不依赖 metric。
--
-- ============================================================
-- 视图字段总览 (新增 16 个字段, 标 ★)
-- ============================================================
--   summary_id / summary_no / hf_part_no / line_seq / status / is_published_label
--   quote_currency / weight_unit / element_version_number / material_version_number / exchange_version_number
--   material_cost (PIVOT)              -- 已有, 后端 compute()
--   processing_cost (PIVOT)            -- 已有, 后端 compute()
--   ★ pure_material_cost              -- 行 78: ∑[非边角×(1+不良率)×(元素含量×元素价 OR 材料价)]
--   ★ recycle_cost                    -- 行 81: ∑[边角×(元素含量×元素价 OR 材料价)×回收折扣]
--   ★ material_loss_cost              -- 行 83: ∑[BOM×来料损耗率×价格] + ∑[固定损耗×价格]
--   ★ incoming_process_fee            -- 行 79: ∑(MATERIAL_PROC unit_price)
--   ★ incoming_other_fee              -- 行 80: 纯材料 × ∑(INCOMING_OTHER 比例)
--   ★ process_fee_total               -- 行 86: ∑(非材料加工类 工序成本 × (1+不良率))
--   ★ plating_process_fee             -- plating_fee 直读
--   ★ plating_material_fee            -- plating_fee 直读
--   ★ plating_defect_rate             -- plating_fee 直读 (decimal, 如 0.005 = 0.5%)
--   ★ outsource_fee_total             -- ∑(OUTSOURCE 类费用), 当前无对应 fee_type 占位 0
--   ★ unit_weight_g                   -- costing_part_weight.weight_g_per_pcs
--   ★ exchange_rate_to_usd            -- v_costing_exchange_rate[CNY→USD].costing_rate
--   ★ mgmt_fee_ratio                  -- mat_fee[FINISHED_OTHER, '管理费'].fee_ratio
--   ★ finance_fee_ratio               -- mat_fee[FINISHED_OTHER, '财务费'].fee_ratio
--   ★ profit_ratio                    -- mat_fee[FINISHED_OTHER, '利润'].fee_ratio
--   ★ tax_ratio                       -- mat_fee[FINISHED_OTHER, '税费'].fee_ratio
--
-- 重建视图 (DROP+CREATE 因为字段定义大变)

DROP VIEW IF EXISTS v_costing_summary_full CASCADE;

CREATE OR REPLACE VIEW v_costing_summary_full AS
WITH
-- BOM 行级展开: 每条 material_bom × 其下的 element_bom (LEFT JOIN, element 不存在则单材料行保留)
bom_expanded AS (
    SELECT
        m.hf_part_no,
        m.input_qty                                  AS bom_input_qty,
        NULLIF(m.output_qty, 0)                      AS bom_output_qty,
        COALESCE(m.loss_rate, 0)/100.0               AS bom_loss_rate,         -- 不良率(%)→小数
        COALESCE(m.fixed_loss_qty, 0)                AS bom_fixed_loss_qty,
        eb.element_code,
        COALESCE(eb.composition_pct, 0)/100.0        AS elem_pct,              -- 元素含量(%)→小数
        COALESCE(eb.loss_rate, 0)/100.0              AS elem_loss_rate,
        COALESCE(cep.costing_price, 0)               AS elem_price,            -- 元素价(CNY/KG)
        COALESCE(cep.discount_rate, 0)/100.0         AS elem_discount,
        COALESCE(cmp.costing_price, 0)               AS mat_price,             -- 材料价(CNY/KG)
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
-- 把每条 BOM 行算单价基础: 元素 mode 用 (含量×元素价); 材料 mode 用 材料价
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
-- 按料号聚合: 纯材料 / 回收 / 材料损耗
material_aggs AS (
    SELECT
        hf_part_no,
        -- 纯材料成本: 非边角行 × (1+不良率) × 单价基础 × (输入量 / 底数)
        SUM(CASE WHEN bom_kind = 'NORMAL' THEN
                ABS(bom_input_qty) / NULLIF(bom_output_qty, 0)
                * (1 + bom_loss_rate)
                * unit_price
            ELSE 0 END) AS pure_material_cost,
        -- 回收成本: 边角行 × 单价 × 回收折扣
        SUM(CASE WHEN bom_kind = 'RECYCLE' THEN
                ABS(bom_input_qty) / NULLIF(bom_output_qty, 0)
                * unit_price_recycle
            ELSE 0 END) AS recycle_cost,
        -- 材料损耗成本:
        --   = ∑[非边角×(1+不良率)×来料损耗率×单价] + ∑[固定损耗×单价]
        SUM(CASE WHEN bom_kind = 'NORMAL' THEN
                ABS(bom_input_qty) / NULLIF(bom_output_qty, 0)
                * (1 + bom_loss_rate) * elem_loss_rate * unit_price
                + bom_fixed_loss_qty * unit_price
            ELSE 0 END) AS material_loss_cost
    FROM bom_priced
    GROUP BY hf_part_no
),
-- 工序成本聚合 (按 cost_type 分组)
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
-- 电镀费用 (plating_fee 已经是 customer-level versioned, 取 is_current=true)
plating_data AS (
    SELECT
        pf.hf_part_no,
        SUM(COALESCE(pf.plating_process_fee, 0))   AS plating_process_fee,
        SUM(COALESCE(pf.plating_material_fee, 0))  AS plating_material_fee,
        AVG(COALESCE(pf.defect_rate, 0))/100.0     AS plating_defect_rate
    FROM plating_fee pf
    WHERE COALESCE(pf.is_current, true) = true
    GROUP BY pf.hf_part_no
),
-- 单重
weight_data AS (
    SELECT hf_part_no, weight_g_per_pcs
    FROM costing_part_weight
    WHERE COALESCE(is_active, true) = true
),
-- 默认汇率 CNY→USD (单一值)
exchange_data AS (
    SELECT costing_rate AS exchange_rate_to_usd
    FROM v_costing_exchange_rate
    WHERE from_currency = 'CNY' AND to_currency = 'USD'
    LIMIT 1
),
-- 加价比例 (mat_fee FINISHED_OTHER 4 项)
fee_ratios AS (
    SELECT
        f.hf_part_no,
        MAX(CASE WHEN f.dim_element_name = '管理费' THEN f.fee_ratio END) AS mgmt_fee_ratio,
        MAX(CASE WHEN f.dim_element_name = '财务费' THEN f.fee_ratio END) AS finance_fee_ratio,
        MAX(CASE WHEN f.dim_element_name = '利润' THEN f.fee_ratio END) AS profit_ratio,
        MAX(CASE WHEN f.dim_element_name = '税费' THEN f.fee_ratio END) AS tax_ratio
    FROM mat_fee f
    WHERE f.fee_type = 'FINISHED_OTHER' AND COALESCE(f.is_current, true) = true
    GROUP BY f.hf_part_no
),
-- 来料其他费用 (mat_fee INCOMING_OTHER, 用比例总和×纯材料近似)
incoming_other AS (
    SELECT
        f.hf_part_no,
        SUM(COALESCE(f.fee_ratio, 0)) AS incoming_other_total_ratio
    FROM mat_fee f
    WHERE f.fee_type = 'INCOMING_OTHER' AND COALESCE(f.is_current, true) = true
    GROUP BY f.hf_part_no
),
-- 关联 PIVOT 老 metric (向后兼容)
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
    -- ── 现有列 (向后兼容 V80) ──
    a.metric_material_cost     AS material_cost,
    a.metric_processing_cost   AS processing_cost,
    -- ── ★ 新增中间值 (V95) ──
    COALESCE(ma.pure_material_cost,    0) AS pure_material_cost,
    COALESCE(ma.recycle_cost,          0) AS recycle_cost,
    COALESCE(ma.material_loss_cost,    0) AS material_loss_cost,
    COALESCE(pc.incoming_process_fee,  0) AS incoming_process_fee,
    COALESCE(ma.pure_material_cost * io.incoming_other_total_ratio, 0) AS incoming_other_fee,
    -- 加工费 = ∑(各工序 × (1+ 主料号不良率)); 这里不良率用第一条 BOM 行的(简化)
    COALESCE(pc.process_fee_base * (1 + COALESCE(
        (SELECT bom_loss_rate FROM bom_priced WHERE hf_part_no = a.hf_part_no LIMIT 1), 0
    )), 0) AS process_fee_total,
    COALESCE(pld.plating_process_fee,  0) AS plating_process_fee,
    COALESCE(pld.plating_material_fee, 0) AS plating_material_fee,
    COALESCE(pld.plating_defect_rate,  0) AS plating_defect_rate,
    -- 其他外加工成本: 当前无对应 fee_type, 占位 0; 以后可扩展为 mat_fee[fee_type='OUTSOURCE'] 总和
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
    'V95: 核价汇总宽视图。SQL 直接 ∑ 聚合 BOM/工序/电镀/费用/价格 等所有中间值, 供 Excel 视图模板的 FORMULA 列做 scalar 组合。每料号 × summary 一行。';
