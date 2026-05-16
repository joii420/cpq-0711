-- V179: 创建西门子一类料号成本汇总视图 + basic_data_config 注册
-- 业务口径（与罗克韦尔 v_q_rockwell_costs 对齐）：
--   银点 = mat_process.assembly_process = '焊接'
--   非银点 = 其余 assembly_process（车/铣/磨/铆 等）下的 ELEMENT BOM
--   外购件成本 = 焊接组件的 (unit_price + freight) × quantity + COMPONENT_OTHER fee × Σquantity
--   组装工序加工费 = mat_fee[ASSEMBLY_PROCESS]
--   电镀加工费 = v_c_plating_cost_merged.plating_process_fee（电镀料费另算）
-- 占位常量（业务后续补真实参数源）：
--   外购件管理费比例 = 0.05
--   材料管理费比例 = 0.08（西门子专属占位）
--   利润比例 = 0.10（西门子专属占位）
--   回收折扣 = 0.95

DROP VIEW IF EXISTS v_q_siemens_class1_costs CASCADE;

CREATE VIEW v_q_siemens_class1_costs AS
WITH
non_silver_bom AS (
  SELECT m.part_no AS hf_part_no,
    SUM(e.gross_qty * COALESCE(e.composition_pct, 100) / 100.0 * ep.costing_price)                                       AS pure_cost,
    SUM(e.gross_qty * COALESCE(e.composition_pct, 100) / 100.0 * COALESCE(e.loss_rate, 0) * ep.costing_price)            AS loss_cost,
    SUM(GREATEST(e.gross_qty - COALESCE(e.net_qty, 0), 0) * COALESCE(e.composition_pct, 100) / 100.0 * ep.costing_price) AS recycle_base
  FROM mat_part m
  JOIN mat_process c ON c.hf_part_no = m.part_no AND c.is_current = true AND c.assembly_process <> '焊接'
  JOIN mat_bom e ON e.bom_type = 'ELEMENT' AND e.hf_part_no = m.part_no AND e.input_material_no = c.component_part_no
  JOIN v_costing_element_price ep ON ep.element_name = e.element_name
  GROUP BY m.part_no
),
silver_bom AS (
  SELECT m.part_no AS hf_part_no,
    SUM(COALESCE(e.net_qty, 0) * COALESCE(e.composition_pct, 100) / 100.0 * ep.costing_price)                            AS pure_cost,
    SUM(COALESCE(e.net_qty, 0) * COALESCE(e.composition_pct, 100) / 100.0 * COALESCE(e.loss_rate, 0) * ep.costing_price) AS loss_cost
  FROM mat_part m
  JOIN mat_process c ON c.hf_part_no = m.part_no AND c.is_current = true AND c.assembly_process = '焊接'
  JOIN mat_bom e ON e.bom_type = 'ELEMENT' AND e.hf_part_no = m.part_no AND e.input_material_no = c.component_part_no
  JOIN v_costing_element_price ep ON ep.element_name = e.element_name
  GROUP BY m.part_no
),
purchase_comp AS (
  SELECT m.part_no AS hf_part_no,
    SUM((COALESCE(c.unit_price, 0) + COALESCE(c.freight, 0)) * COALESCE(c.quantity, 0)) AS price_freight_sum,
    SUM(COALESCE(c.quantity, 0))                                                        AS qty_sum
  FROM mat_part m
  JOIN mat_process c ON c.hf_part_no = m.part_no AND c.is_current = true AND c.assembly_process = '焊接'
  GROUP BY m.part_no
),
fee_by_type AS (
  SELECT hf_part_no,
    SUM(CASE WHEN fee_type = 'INCOMING_FIXED'   THEN fee_value ELSE 0 END) AS incoming_fixed_fee,
    SUM(CASE WHEN fee_type = 'INCOMING_OTHER'   THEN fee_value ELSE 0 END) AS incoming_other_fee,
    SUM(CASE WHEN fee_type = 'COMPONENT_OTHER'  THEN fee_value ELSE 0 END) AS component_other_fee,
    SUM(CASE WHEN fee_type = 'ASSEMBLY_PROCESS' THEN fee_value ELSE 0 END) AS assembly_proc_fee
  FROM mat_fee
  WHERE is_current = true
  GROUP BY hf_part_no
),
plating AS (
  SELECT hf_part_no,
    SUM(COALESCE(plating_process_fee, 0))  AS plating_process_fee_sum,
    SUM(COALESCE(plating_material_fee, 0)) AS plating_material_fee_sum
  FROM v_c_plating_cost_merged
  GROUP BY hf_part_no
),
metrics AS (
  SELECT
    m.part_no AS hf_part_no,
    -- 中间量
    COALESCE(ns.pure_cost, 0)                                                  AS non_silver_pure,
    COALESCE(s.pure_cost, 0)                                                   AS silver_pure,
    COALESCE(pc.price_freight_sum, 0)
      + COALESCE(pc.qty_sum, 0) * COALESCE(fbt.component_other_fee, 0)         AS purchase_cost,
    COALESCE(fbt.incoming_fixed_fee, 0)                                        AS incoming_fixed_fee,
    COALESCE(fbt.incoming_other_fee, 0)                                        AS incoming_other_fee,
    COALESCE(fbt.assembly_proc_fee, 0)                                         AS process_fee_assembly,
    COALESCE(ns.loss_cost, 0)                                                  AS non_silver_loss,
    COALESCE(s.loss_cost, 0)                                                   AS silver_loss,
    COALESCE(ns.recycle_base, 0)                                               AS recycle_base,
    COALESCE(pl.plating_process_fee_sum, 0)                                    AS plating_process_fee,
    COALESCE(pl.plating_material_fee_sum, 0)                                   AS plating_material_fee
  FROM mat_part m
  LEFT JOIN non_silver_bom  ns  ON ns.hf_part_no  = m.part_no
  LEFT JOIN silver_bom      s   ON s.hf_part_no   = m.part_no
  LEFT JOIN purchase_comp   pc  ON pc.hf_part_no  = m.part_no
  LEFT JOIN fee_by_type     fbt ON fbt.hf_part_no = m.part_no
  LEFT JOIN plating         pl  ON pl.hf_part_no  = m.part_no
)
SELECT
  hf_part_no,
  -- 1. 纯材料成本
  (non_silver_pure + silver_pure + purchase_cost)                                                              AS pure_material_cost,
  -- 2. 来料加工费 = 非银点冲压(INCOMING_FIXED) + 银点铆钉(INCOMING_OTHER)
  (incoming_fixed_fee + incoming_other_fee)                                                                    AS incoming_proc_fee,
  -- 3. 回收成本 = (毛重-净重) × 含量 × 元素单价 × 回收折扣 0.95
  (recycle_base * 0.95)                                                                                        AS recycle_cost,
  -- 4. 材料成本 = 纯材料 + 来料加工 - 回收
  (non_silver_pure + silver_pure + purchase_cost + incoming_fixed_fee + incoming_other_fee - recycle_base * 0.95) AS material_cost,
  -- 5. 材料损耗成本
  (non_silver_loss + silver_loss)                                                                              AS material_loss_cost,
  -- 6. 加工费 = 组装工序加工费
  process_fee_assembly                                                                                         AS process_fee,
  -- 7. 电镀加工费（西门子"电镀成本"项）
  plating_process_fee                                                                                          AS plating_process_fee,
  -- 8. 电镀料费（中间量，不参与 total）
  plating_material_fee                                                                                         AS plating_material_fee,
  -- 9. 外购件成本（中间量，用于管理费/利润排除）
  purchase_cost                                                                                                AS purchase_cost,
  -- 10. 管理费 = (材料成本 - 外购件 + 损耗 + 加工 + 电镀加工费) × 0.08 + 外购件 × 0.05
  ((non_silver_pure + silver_pure + incoming_fixed_fee + incoming_other_fee - recycle_base * 0.95
    + non_silver_loss + silver_loss + process_fee_assembly + plating_process_fee) * 0.08
   + purchase_cost * 0.05)                                                                                     AS mgmt_fee,
  -- 11. 利润 = (材料成本 - 外购件 + 损耗 + 加工 + 电镀加工费) × 0.10
  ((non_silver_pure + silver_pure + incoming_fixed_fee + incoming_other_fee - recycle_base * 0.95
    + non_silver_loss + silver_loss + process_fee_assembly + plating_process_fee) * 0.10)                      AS profit,
  -- 12. 总成本 = 材料成本 + 损耗 + 加工 + 管理费 + 利润 + 电镀加工费
  ((non_silver_pure + silver_pure + purchase_cost + incoming_fixed_fee + incoming_other_fee - recycle_base * 0.95)
   + (non_silver_loss + silver_loss)
   + process_fee_assembly
   + ((non_silver_pure + silver_pure + incoming_fixed_fee + incoming_other_fee - recycle_base * 0.95
       + non_silver_loss + silver_loss + process_fee_assembly + plating_process_fee) * 0.08
      + purchase_cost * 0.05)
   + ((non_silver_pure + silver_pure + incoming_fixed_fee + incoming_other_fee - recycle_base * 0.95
       + non_silver_loss + silver_loss + process_fee_assembly + plating_process_fee) * 0.10)
   + plating_process_fee)                                                                                      AS total_cost
FROM metrics;

COMMENT ON VIEW v_q_siemens_class1_costs IS '西门子第一类料号成本汇总（10 项 metric）；银点=焊接组件；外购件管理费 0.05/材料管理费 0.08/利润 0.10/回收折扣 0.95 为占位常量，业务参数化待 V2';

-- ====================================================================
-- basic_data_config 注册（让 PathPickerDrawer 能在 QUOTATION 模板里下拉）
-- ====================================================================

INSERT INTO basic_data_config (id, sheet_name, target_table, template_kind, sort_order, status, created_at, updated_at, sheet_index, header_row_index, data_start_row_index, join_columns)
VALUES (
  '8a1ce500-5181-4001-9001-100000000001',
  '西门子-一类料号成本汇总',
  'v_q_siemens_class1_costs',
  'QUOTATION',
  410,
  'ACTIVE',
  NOW(),
  NOW(),
  0,
  0,
  1,
  '[]'::jsonb
)
ON CONFLICT (id) DO UPDATE
SET sheet_name = EXCLUDED.sheet_name,
    target_table = EXCLUDED.target_table,
    template_kind = EXCLUDED.template_kind,
    sort_order = EXCLUDED.sort_order,
    status = EXCLUDED.status,
    updated_at = NOW();

-- basic_data_attribute: 11 列（hf_part_no 除外）
DELETE FROM basic_data_attribute WHERE config_id = '8a1ce500-5181-4001-9001-100000000001';

INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, created_at, updated_at, importance_level, affects_calculation, is_required) VALUES
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'A', '纯材料成本',       'pure_material_cost',   '纯材料成本',       'VALUE',  10, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'B', '来料加工费',       'incoming_proc_fee',    '来料加工费',       'VALUE',  20, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'C', '回收成本',         'recycle_cost',         '回收成本',         'VALUE',  30, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'D', '材料成本',         'material_cost',        '材料成本',         'VALUE',  40, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'E', '材料损耗成本',     'material_loss_cost',   '材料损耗成本',     'VALUE',  50, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'F', '加工费',           'process_fee',          '加工费',           'VALUE',  60, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'G', '电镀加工费',       'plating_process_fee',  '电镀加工费',       'VALUE',  70, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'H', '电镀料费',         'plating_material_fee', '电镀料费',         'VALUE',  80, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'I', '外购件成本',       'purchase_cost',        '外购件成本',       'VALUE',  90, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'J', '管理费',           'mgmt_fee',             '管理费',           'VALUE', 100, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'K', '利润',             'profit',               '利润',             'VALUE', 110, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false),
  (gen_random_uuid(), '8a1ce500-5181-4001-9001-100000000001', 'L', '总成本',           'total_cost',           '总成本',           'VALUE', 120, 'ACTIVE', NOW(), NOW(), 'NORMAL', true, false);

-- ====================================================================
-- comparison_tag: 注册西门子模板用到但 V114 没注册的 tag
-- ====================================================================

INSERT INTO comparison_tag (id, code, label, group_name, group_sort_order, tag_sort_order, is_builtin, status, created_at, updated_at)
VALUES
  (gen_random_uuid(), 'PURE_MATERIAL_COST', '纯材料成本', '成本明细', 10, 5,  false, 'ACTIVE', NOW(), NOW()),
  (gen_random_uuid(), 'INCOMING_PROC_FEE',  '来料加工费', '成本明细', 10, 6,  false, 'ACTIVE', NOW(), NOW()),
  (gen_random_uuid(), 'RECYCLE_COST',       '回收成本',   '成本明细', 10, 7,  false, 'ACTIVE', NOW(), NOW()),
  (gen_random_uuid(), 'PLATING_PROC_FEE',   '电镀加工费', '成本明细', 10, 12, false, 'ACTIVE', NOW(), NOW()),
  (gen_random_uuid(), 'MGMT_FEE',           '管理费',     '商务加价', 20, 21, false, 'ACTIVE', NOW(), NOW()),
  (gen_random_uuid(), 'PROFIT',             '利润',       '商务加价', 20, 22, false, 'ACTIVE', NOW(), NOW()),
  (gen_random_uuid(), 'TOTAL_COST',         '总成本',     '终价',     30, 31, false, 'ACTIVE', NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

-- 自检
DO $$
DECLARE
  v_cnt INTEGER;
BEGIN
  SELECT COUNT(*) INTO v_cnt FROM v_q_siemens_class1_costs;
  RAISE NOTICE 'v_q_siemens_class1_costs row count: %', v_cnt;
END $$;
