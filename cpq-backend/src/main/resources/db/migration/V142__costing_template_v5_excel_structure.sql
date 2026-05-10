-- ============================================================
-- V142: 核价标准模板 v5.0 — 按 Excel 5.0 版每 sheet 一个组件
-- ============================================================
-- 来源: data/template/核价系统功能基础数据功能结构所需字段（5.0版）.xlsx
--
-- 设计原则:
--   1. 5.0 版 25 个 sheet 中 4 个全局参考(元素核价/材料核价/汇率/版本) + 1 个汇总 = 5 个不做料号 tab
--      → 剩余 20 个料号级 sheet 各自做 1 个组件 tab
--   2. 物理表全部复用现有 costing_part_*(V44/V76/V125) — 不新建表, 用 cost_type/fee_type 谓词分流
--   3. 每个组件用一个 v_c_*_merged 视图作为 data_driver_path, 视图统一处理:
--        - 百分比列 ×100 (V133 模式)
--        - 全局表(电镀方案) LEFT JOIN by 业务键 (V141 模式) 兼容 ImplicitJoinRewriter
--        - cost_type/fee_type 鉴别下推到视图 WHERE
--   4. 组件 code 前缀 COMP-V5-* (与 V98 的 COMP-V4-* 并存)
--   5. 模板状态 DRAFT, 由用户在 UI 审核后手工 publish — 不擅自激活
--   6. 全部 INSERT/UPDATE 幂等 (ON CONFLICT / IF NOT EXISTS / NOT EXISTS 子查询)
--
-- 5.0 版 sheet → 组件 → 视图 → 底层表 总表:
--   01 宏丰-客户料号对应关系  COMP-V5-PART-MAPPING       v_c_part_mapping_merged       mat_customer_part_mapping
--   02 来料BOM               COMP-V5-RAW-BOM            v_c_raw_bom_merged             costing_part_material_bom
--   03 来料与元素BOM         COMP-V5-RAW-ELEMENT-BOM    v_c_raw_element_bom_merged     costing_part_element_bom × material_bom (LJ by input_material_no)
--   04 人工成本(单价)         COMP-V5-LABOR-COST         v_c_labor_cost_merged          costing_part_process_cost [LABOR]
--   05 设备折旧成本           COMP-V5-DEPRECIATION       v_c_depreciation_merged        costing_part_process_cost [DEPRECIATION]
--   06 生产设备能耗成本        COMP-V5-ENERGY-PROD        v_c_energy_prod_merged         costing_part_process_cost [ENERGY_DEDICATED]
--   07 辅助设备能耗成本        COMP-V5-ENERGY-AUX         v_c_energy_aux_merged          costing_part_process_cost [ENERGY_SHARED]
--   08 模具工装成本           COMP-V5-TOOLING            v_c_tooling_merged             costing_part_tooling_cost
--   09 生产耗材               COMP-V5-CONSUMABLE-PROD    v_c_consumable_prod_merged     costing_part_process_cost [CONSUMABLE] (TODO 拆分)
--   10 包装材料               COMP-V5-PACKAGING          v_c_packaging_merged           costing_part_process_cost [CONSUMABLE] (process_name 含"包装")
--   11 来料加工费             COMP-V5-INCOMING-PROC      v_c_incoming_proc_merged       costing_part_process_cost [MATERIAL_PROC]
--   12 来料其他费用(比例)     COMP-V5-INCOMING-OTHER     v_c_incoming_other_merged      mat_fee [INCOMING_OTHER]  (×100)
--   13 来料其他固定费用       COMP-V5-INCOMING-FIXED-FEE v_c_incoming_fixed_fee_merged  mat_fee [INCOMING_FIXED]
--   14 成品加工费&组装费      COMP-V5-FINISHED-PROC      v_c_finished_proc_merged       costing_part_process_cost [SEMI_FINISHED_PROC]
--   15 成品其他比例费用       COMP-V5-FINISHED-OTHER     v_c_finished_other_merged      mat_fee [FINISHED_OTHER]  (×100)
--   16 成品其他固定费用       COMP-V5-FINISHED-FIXED-FEE v_c_finished_fixed_fee_merged  mat_fee [FINISHED_FIXED]
--   17 电镀方案               COMP-V5-PLATING-SCHEME     v_c_plating_scheme_merged      costing_part_plating_fee × costing_part_plating (LJ by plan)
--   18 电镀成本               COMP-V5-PLATING-COST       v_c_plating_cost_merged        costing_part_plating_fee
--   19 其他外加工成本         COMP-V5-OUTSOURCE          v_c_outsource_merged           costing_part_process_cost [POST_PROC]
--   20 单重                  COMP-V5-WEIGHT             v_c_weight_merged              costing_part_weight
--
-- 注意:
--   - 组件 12/15 的百分比列 ×100 (mat_fee.fee_ratio 存小数 0.008 → 显示 0.8)
--   - 组件 13/16 的固定费用 sheet 当前数据可能为空 (mat_fee 主要从报价侧 import); 视图建好等 import
--   - 组件 17 是全局电镀方案表, 必须 LEFT JOIN, 否则 ImplicitJoinRewriter 加 hf_part_no 谓词会过滤掉
--   - V139 mat_fee.fee_type CHECK 已含 INCOMING_FIXED/FINISHED_FIXED, 无需扩
-- ============================================================

-- ════════════════════════════════════════════════════════════════════════════
-- A. 创建 20 个 v_c_*_merged 视图
-- ════════════════════════════════════════════════════════════════════════════

-- ── A.01 客户料号对应关系 ─────────────────────────────────────────
DROP VIEW IF EXISTS v_c_part_mapping_merged CASCADE;
CREATE VIEW v_c_part_mapping_merged AS
SELECT
    m.hf_part_no,
    m.customer_id,
    m.customer_part_name,
    m.customer_product_no,
    m.customer_drawing_no,
    m.payment_method,
    m.base_currency,
    m.quote_currency
FROM mat_customer_part_mapping m;
COMMENT ON VIEW v_c_part_mapping_merged IS 'V142 核价 5.0: 客户料号对应关系 sheet';

-- ── A.02 来料 BOM ─────────────────────────────────────────────────
DROP VIEW IF EXISTS v_c_raw_bom_merged CASCADE;
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
    CAST(b.output_loss_rate * 100 AS NUMERIC(10,4)) AS output_loss_rate, -- ×100 显示%
    b.fixed_loss_qty,
    CAST(b.loss_rate         * 100 AS NUMERIC(10,4)) AS loss_rate
FROM costing_part_material_bom b
WHERE b.is_active = true;
COMMENT ON VIEW v_c_raw_bom_merged IS 'V142 核价 5.0: 来料BOM sheet (loss_rate/output_loss_rate ×100)';

-- ── A.03 来料与元素 BOM (元素 BOM × 材料 BOM 关联展开) ─────────────
-- 元素 BOM 主键是 input_material_no (不是 hf_part_no), 必须 JOIN 材料 BOM 才能加 hf_part_no.
-- ImplicitJoinRewriter 看到 hf_part_no 列就会注入当前料号谓词, 视图自然过滤.
DROP VIEW IF EXISTS v_c_raw_element_bom_merged CASCADE;
CREATE VIEW v_c_raw_element_bom_merged AS
SELECT
    mb.hf_part_no,                                     -- 来自材料 BOM (供注入)
    mb.seq_no       AS material_seq_no,
    eb.input_material_no,
    eb.seq_no       AS element_seq_no,
    eb.element_code,
    CAST(eb.composition_pct * 100 AS NUMERIC(10,4)) AS composition_pct,
    CAST(eb.loss_rate        * 100 AS NUMERIC(10,4)) AS loss_rate
FROM costing_part_element_bom eb
JOIN costing_part_material_bom mb
  ON mb.input_material_no = eb.input_material_no
 AND mb.is_active = true
WHERE eb.is_active = true;
COMMENT ON VIEW v_c_raw_element_bom_merged IS
    'V142 核价 5.0: 来料与元素BOM (元素BOM × 材料BOM by input_material_no, composition_pct/loss_rate ×100)';

-- ── A.04~A.07 工序级单价四态 (LABOR/DEPRECIATION/ENERGY_DEDICATED/ENERGY_SHARED) ─
DROP VIEW IF EXISTS v_c_labor_cost_merged CASCADE;
CREATE VIEW v_c_labor_cost_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit, ref_calc_version, notes
FROM costing_part_process_cost
WHERE cost_type = 'LABOR' AND is_active = true;
COMMENT ON VIEW v_c_labor_cost_merged IS 'V142 核价 5.0: 人工成本(单价) sheet';

DROP VIEW IF EXISTS v_c_depreciation_merged CASCADE;
CREATE VIEW v_c_depreciation_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit, ref_calc_version, notes
FROM costing_part_process_cost
WHERE cost_type = 'DEPRECIATION' AND is_active = true;
COMMENT ON VIEW v_c_depreciation_merged IS 'V142 核价 5.0: 设备折旧成本 sheet';

DROP VIEW IF EXISTS v_c_energy_prod_merged CASCADE;
CREATE VIEW v_c_energy_prod_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit, ref_calc_version, notes
FROM costing_part_process_cost
WHERE cost_type = 'ENERGY_DEDICATED' AND is_active = true;
COMMENT ON VIEW v_c_energy_prod_merged IS 'V142 核价 5.0: 生产设备能耗成本 sheet';

DROP VIEW IF EXISTS v_c_energy_aux_merged CASCADE;
CREATE VIEW v_c_energy_aux_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit, ref_calc_version, notes
FROM costing_part_process_cost
WHERE cost_type = 'ENERGY_SHARED' AND is_active = true;
COMMENT ON VIEW v_c_energy_aux_merged IS 'V142 核价 5.0: 辅助设备能耗成本 sheet';

-- ── A.08 模具工装成本 ─────────────────────────────────────────────
DROP VIEW IF EXISTS v_c_tooling_merged CASCADE;
CREATE VIEW v_c_tooling_merged AS
SELECT
    hf_part_no, process_no, process_name, seq_no,
    tooling_no, tooling_unit_cost, process_count, cycle_count,
    unit_price, currency, unit, notes
FROM costing_part_tooling_cost
WHERE is_active = true;
COMMENT ON VIEW v_c_tooling_merged IS 'V142 核价 5.0: 模具工装成本 sheet';

-- ── A.09/A.10 生产耗材 / 包装材料 (5.0 拆 2 sheet, 物理表 cost_type='CONSUMABLE' 一类) ──
-- TODO: 后续如需精分, 在 cost_type 上加 'CONSUMABLE_PACKAGING' 或在 process_name 用约定值过滤.
--       现状用 process_name 含"包装"关键字拆分; 无数据时两 tab 都为空.
DROP VIEW IF EXISTS v_c_consumable_prod_merged CASCADE;
CREATE VIEW v_c_consumable_prod_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit, ref_calc_version, notes
FROM costing_part_process_cost
WHERE cost_type = 'CONSUMABLE' AND is_active = true
  AND COALESCE(process_name, '') NOT LIKE '%包装%';
COMMENT ON VIEW v_c_consumable_prod_merged IS
    'V142 核价 5.0: 生产耗材 sheet (CONSUMABLE 中 process_name 不含"包装")';

DROP VIEW IF EXISTS v_c_packaging_merged CASCADE;
CREATE VIEW v_c_packaging_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit, ref_calc_version, notes
FROM costing_part_process_cost
WHERE cost_type = 'CONSUMABLE' AND is_active = true
  AND COALESCE(process_name, '') LIKE '%包装%';
COMMENT ON VIEW v_c_packaging_merged IS
    'V142 核价 5.0: 包装材料 sheet (CONSUMABLE 中 process_name 含"包装"关键字; 无数据时为空)';

-- ── A.11 来料加工费 ──────────────────────────────────────────────
DROP VIEW IF EXISTS v_c_incoming_proc_merged CASCADE;
CREATE VIEW v_c_incoming_proc_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit, ref_calc_version, notes
FROM costing_part_process_cost
WHERE cost_type = 'MATERIAL_PROC' AND is_active = true;
COMMENT ON VIEW v_c_incoming_proc_merged IS 'V142 核价 5.0: 来料加工费 sheet';

-- ── A.12 来料其他费用(比例) — mat_fee[INCOMING_OTHER], fee_ratio ×100 ─
DROP VIEW IF EXISTS v_c_incoming_other_merged CASCADE;
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
    price_unit
FROM mat_fee
WHERE fee_type = 'INCOMING_OTHER' AND is_current = true AND status = 'ACTIVE';
COMMENT ON VIEW v_c_incoming_other_merged IS
    'V142 核价 5.0: 来料其他费用(比例) sheet — mat_fee[INCOMING_OTHER], fee_ratio ×100';

-- ── A.13 来料其他固定费用 — mat_fee[INCOMING_FIXED] (绝对值 fee_value) ─
-- 注: mat_fee.fee_type CHECK 已含 INCOMING_FIXED (V44 起). 当前 import 主要从报价侧写,
-- 如核价想用相同表, 后续 import 路径需扩或 seed.
DROP VIEW IF EXISTS v_c_incoming_fixed_fee_merged CASCADE;
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
    fixed_rise_value
FROM mat_fee
WHERE fee_type = 'INCOMING_FIXED' AND is_current = true AND status = 'ACTIVE';
COMMENT ON VIEW v_c_incoming_fixed_fee_merged IS
    'V142 核价 5.0: 来料其他固定费用 sheet — mat_fee[INCOMING_FIXED] (核价 import 路径 TODO)';

-- ── A.14 成品加工费&组装费 — process_cost[SEMI_FINISHED_PROC] ─
DROP VIEW IF EXISTS v_c_finished_proc_merged CASCADE;
CREATE VIEW v_c_finished_proc_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit, ref_calc_version, notes
FROM costing_part_process_cost
WHERE cost_type = 'SEMI_FINISHED_PROC' AND is_active = true;
COMMENT ON VIEW v_c_finished_proc_merged IS 'V142 核价 5.0: 成品加工费&组装费 sheet';

-- ── A.15 成品其他比例费用 — mat_fee[FINISHED_OTHER], fee_ratio ×100 ─
DROP VIEW IF EXISTS v_c_finished_other_merged CASCADE;
CREATE VIEW v_c_finished_other_merged AS
SELECT
    hf_part_no,
    customer_id,
    seq_no,
    dim_element_name,
    CAST(fee_ratio * 100 AS NUMERIC(10,4)) AS fee_ratio,
    currency,
    price_unit
FROM mat_fee
WHERE fee_type = 'FINISHED_OTHER' AND is_current = true AND status = 'ACTIVE';
COMMENT ON VIEW v_c_finished_other_merged IS
    'V142 核价 5.0: 成品其他比例费用 sheet — mat_fee[FINISHED_OTHER], fee_ratio ×100';

-- ── A.16 成品其他固定费用 — mat_fee[FINISHED_FIXED] (绝对值) ─
DROP VIEW IF EXISTS v_c_finished_fixed_fee_merged CASCADE;
CREATE VIEW v_c_finished_fixed_fee_merged AS
SELECT
    hf_part_no,
    customer_id,
    seq_no,
    dim_element_name,
    fee_value,
    currency,
    price_unit
FROM mat_fee
WHERE fee_type = 'FINISHED_FIXED' AND is_current = true AND status = 'ACTIVE';
COMMENT ON VIEW v_c_finished_fixed_fee_merged IS
    'V142 核价 5.0: 成品其他固定费用 sheet — mat_fee[FINISHED_FIXED] (核价 import 路径 TODO)';

-- ── A.17 电镀方案 — 全局表 LEFT JOIN by (plan_code+version), V141 模式 ─
-- 必须以 costing_part_plating_fee (带 hf_part_no) 为主表; 否则 ImplicitJoinRewriter
-- 加的 hf_part_no 谓词会让 costing_part_plating(全局)被过滤为空.
DROP VIEW IF EXISTS v_c_plating_scheme_merged CASCADE;
CREATE VIEW v_c_plating_scheme_merged AS
SELECT
    f.hf_part_no,
    f.plating_plan_code   AS plan_code,
    f.plan_version,
    cpp.seq_no,
    cpp.element_attr      AS plating_element,
    cpp.plating_area_cm2  AS plating_area,
    cpp.layer_thickness_um AS coating_thickness,
    cpp.requirement       AS plating_requirement
FROM costing_part_plating_fee f
LEFT JOIN costing_part_plating cpp
       ON cpp.plating_no       = f.plating_plan_code
      AND cpp.version_number    = f.plan_version
      AND cpp.is_active = true
WHERE f.is_active = true;
COMMENT ON VIEW v_c_plating_scheme_merged IS
    'V142 核价 5.0: 电镀方案 sheet (LEFT JOIN by plan_code+version, V141 模式)';

-- ── A.18 电镀成本 ────────────────────────────────────────────────
DROP VIEW IF EXISTS v_c_plating_cost_merged CASCADE;
CREATE VIEW v_c_plating_cost_merged AS
SELECT
    hf_part_no,
    plating_plan_code AS plan_code,
    plan_version,
    plating_process_fee,
    plating_material_fee,
    currency,
    price_unit,
    CAST(defect_rate * 100 AS NUMERIC(10,4)) AS defect_rate -- ×100
FROM costing_part_plating_fee
WHERE is_active = true;
COMMENT ON VIEW v_c_plating_cost_merged IS
    'V142 核价 5.0: 电镀成本 sheet (defect_rate ×100)';

-- ── A.19 其他外加工成本 — process_cost[POST_PROC] ─
DROP VIEW IF EXISTS v_c_outsource_merged CASCADE;
CREATE VIEW v_c_outsource_merged AS
SELECT hf_part_no, process_no, process_name, unit_price, currency, unit, ref_calc_version, notes
FROM costing_part_process_cost
WHERE cost_type = 'POST_PROC' AND is_active = true;
COMMENT ON VIEW v_c_outsource_merged IS 'V142 核价 5.0: 其他外加工成本 sheet';

-- ── A.20 单重 ────────────────────────────────────────────────────
DROP VIEW IF EXISTS v_c_weight_merged CASCADE;
CREATE VIEW v_c_weight_merged AS
SELECT hf_part_no, weight_g_per_pcs, notes
FROM costing_part_weight
WHERE is_active = true;
COMMENT ON VIEW v_c_weight_merged IS 'V142 核价 5.0: 单重 sheet';

-- ════════════════════════════════════════════════════════════════════════════
-- B. 创建组件目录「核价模板组件V5-Excel结构」
-- ════════════════════════════════════════════════════════════════════════════
INSERT INTO component_directory (id, name, parent_id, sort_order, created_at)
SELECT 'd5e1f2a3-0005-4005-8005-000000000005'::uuid, '核价模板组件V5-Excel结构', NULL, 105, now()
WHERE NOT EXISTS (
    SELECT 1 FROM component_directory
    WHERE id = 'd5e1f2a3-0005-4005-8005-000000000005'::uuid
       OR name = '核价模板组件V5-Excel结构'
);

-- ════════════════════════════════════════════════════════════════════════════
-- C. 20 个 NORMAL 组件 (ON CONFLICT DO UPDATE 幂等)
--    所有组件: status=ACTIVE, component_type=NORMAL, fields 全用 BASIC_DATA 视图列引用
-- ════════════════════════════════════════════════════════════════════════════

-- ── C.01 客户料号对应关系 ─────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-客户料号对应关系', 'COMP-V5-PART-MAPPING', 7,
$JSON$[
  {"name":"客户料号名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_part_mapping_merged.customer_part_name"},
  {"name":"客户产品编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_part_mapping_merged.customer_product_no"},
  {"name":"客户图号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_part_mapping_merged.customer_drawing_no"},
  {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_part_mapping_merged.hf_part_no"},
  {"name":"付款方式","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_part_mapping_merged.payment_method"},
  {"name":"基础货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_part_mapping_merged.base_currency"},
  {"name":"报价货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_part_mapping_merged.quote_currency"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_part_mapping_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.02 来料 BOM ─────────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-来料BOM', 'COMP-V5-RAW-BOM', 9,
$JSON$[
  {"name":"序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_merged.seq_no"},
  {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_merged.input_material_no"},
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_merged.process_name"},
  {"name":"输入数量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_merged.input_qty"},
  {"name":"输入单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_merged.input_unit"},
  {"name":"产出数量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_merged.output_qty"},
  {"name":"产出损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_bom_merged.output_loss_rate"},
  {"name":"损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"v_c_raw_bom_merged.loss_rate"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_raw_bom_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.03 来料与元素 BOM ──────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-来料与元素BOM', 'COMP-V5-RAW-ELEMENT-BOM', 5,
$JSON$[
  {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_element_bom_merged.input_material_no"},
  {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_element_bom_merged.element_seq_no"},
  {"name":"元素代码","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_element_bom_merged.element_code"},
  {"name":"组成含量(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_raw_element_bom_merged.composition_pct"},
  {"name":"损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"v_c_raw_element_bom_merged.loss_rate"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_raw_element_bom_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.04 人工成本(单价) ──────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-人工成本(单价)', 'COMP-V5-LABOR-COST', 5,
$JSON$[
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_labor_cost_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_labor_cost_merged.process_name"},
  {"name":"人工标准单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_labor_cost_merged.unit_price"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_labor_cost_merged.currency"},
  {"name":"单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_labor_cost_merged.unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_labor_cost_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.05 设备折旧成本 ────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-设备折旧成本', 'COMP-V5-DEPRECIATION', 5,
$JSON$[
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_depreciation_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_depreciation_merged.process_name"},
  {"name":"折旧单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_depreciation_merged.unit_price"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_depreciation_merged.currency"},
  {"name":"单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_depreciation_merged.unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_depreciation_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.06 生产设备能耗成本 ──────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-生产设备能耗', 'COMP-V5-ENERGY-PROD', 5,
$JSON$[
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_energy_prod_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_energy_prod_merged.process_name"},
  {"name":"能耗单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_energy_prod_merged.unit_price"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_energy_prod_merged.currency"},
  {"name":"单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_energy_prod_merged.unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_energy_prod_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.07 辅助设备能耗成本 ──────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-辅助设备能耗', 'COMP-V5-ENERGY-AUX', 5,
$JSON$[
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_energy_aux_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_energy_aux_merged.process_name"},
  {"name":"能耗单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_energy_aux_merged.unit_price"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_energy_aux_merged.currency"},
  {"name":"单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_energy_aux_merged.unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_energy_aux_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.08 模具工装成本 ──────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-模具工装成本', 'COMP-V5-TOOLING', 9,
$JSON$[
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_tooling_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_tooling_merged.process_name"},
  {"name":"序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_tooling_merged.seq_no"},
  {"name":"模具编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_tooling_merged.tooling_no"},
  {"name":"单套成本","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_c_tooling_merged.tooling_unit_cost"},
  {"name":"工艺次数","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_tooling_merged.process_count"},
  {"name":"可循环次数","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_tooling_merged.cycle_count"},
  {"name":"模具单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_tooling_merged.unit_price"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_tooling_merged.currency"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_tooling_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.09 生产耗材 ──────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-生产耗材', 'COMP-V5-CONSUMABLE-PROD', 5,
$JSON$[
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_consumable_prod_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_consumable_prod_merged.process_name"},
  {"name":"耗材成本单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_consumable_prod_merged.unit_price"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_consumable_prod_merged.currency"},
  {"name":"单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_consumable_prod_merged.unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_consumable_prod_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.10 包装材料 ──────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-包装材料', 'COMP-V5-PACKAGING', 5,
$JSON$[
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_packaging_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_packaging_merged.process_name"},
  {"name":"包装材料单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_packaging_merged.unit_price"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_packaging_merged.currency"},
  {"name":"单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_packaging_merged.unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_packaging_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.11 来料加工费 ────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-来料加工费', 'COMP-V5-INCOMING-PROC', 5,
$JSON$[
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_proc_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_proc_merged.process_name"},
  {"name":"加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_incoming_proc_merged.unit_price"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_proc_merged.currency"},
  {"name":"单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_proc_merged.unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_incoming_proc_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.12 来料其他费用(比例) ────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-来料其他费用(比例)', 'COMP-V5-INCOMING-OTHER', 7,
$JSON$[
  {"name":"一级项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_other_merged.seq_no"},
  {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_other_merged.dim_input_material_no"},
  {"name":"二级项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_other_merged.dim_sub_seq_no"},
  {"name":"要素名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_other_merged.dim_element_name"},
  {"name":"比例(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"v_c_incoming_other_merged.fee_ratio"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_other_merged.currency"},
  {"name":"价格单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_other_merged.price_unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_incoming_other_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.13 来料其他固定费用 ──────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-来料其他固定费用', 'COMP-V5-INCOMING-FIXED-FEE', 8,
$JSON$[
  {"name":"一级项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_fixed_fee_merged.seq_no"},
  {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_fixed_fee_merged.dim_input_material_no"},
  {"name":"二级项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_fixed_fee_merged.dim_sub_seq_no"},
  {"name":"要素名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_fixed_fee_merged.dim_element_name"},
  {"name":"费用值","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_incoming_fixed_fee_merged.fee_value"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_fixed_fee_merged.currency"},
  {"name":"价格单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_fixed_fee_merged.price_unit"},
  {"name":"价格浮动","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_incoming_fixed_fee_merged.price_floating"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_incoming_fixed_fee_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.14 成品加工费&组装费 ─────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-成品加工费&组装费', 'COMP-V5-FINISHED-PROC', 5,
$JSON$[
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_proc_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_proc_merged.process_name"},
  {"name":"加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_finished_proc_merged.unit_price"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_proc_merged.currency"},
  {"name":"单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_proc_merged.unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_finished_proc_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.15 成品其他比例费用 ──────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-成品其他比例费用', 'COMP-V5-FINISHED-OTHER', 5,
$JSON$[
  {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_other_merged.seq_no"},
  {"name":"要素名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_other_merged.dim_element_name"},
  {"name":"比例(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"v_c_finished_other_merged.fee_ratio"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_other_merged.currency"},
  {"name":"价格单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_other_merged.price_unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_finished_other_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.16 成品其他固定费用 ──────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-成品其他固定费用', 'COMP-V5-FINISHED-FIXED-FEE', 5,
$JSON$[
  {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_fixed_fee_merged.seq_no"},
  {"name":"要素名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_fixed_fee_merged.dim_element_name"},
  {"name":"费用值","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_finished_fixed_fee_merged.fee_value"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_fixed_fee_merged.currency"},
  {"name":"价格单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_finished_fixed_fee_merged.price_unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_finished_fixed_fee_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.17 电镀方案 ──────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-电镀方案', 'COMP-V5-PLATING-SCHEME', 7,
$JSON$[
  {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_plating_scheme_merged.plan_code"},
  {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_plating_scheme_merged.plan_version"},
  {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_plating_scheme_merged.seq_no"},
  {"name":"电镀元素","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_plating_scheme_merged.plating_element"},
  {"name":"电镀面积(cm²)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_plating_scheme_merged.plating_area"},
  {"name":"镀层厚度(μm)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_plating_scheme_merged.coating_thickness"},
  {"name":"镀层要求","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"v_c_plating_scheme_merged.plating_requirement"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_plating_scheme_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.18 电镀成本 ──────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-电镀成本', 'COMP-V5-PLATING-COST', 7,
$JSON$[
  {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_plating_cost_merged.plan_code"},
  {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_plating_cost_merged.plan_version"},
  {"name":"电镀加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_c_plating_cost_merged.plating_process_fee"},
  {"name":"电镀材料费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_c_plating_cost_merged.plating_material_fee"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_plating_cost_merged.currency"},
  {"name":"价格单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_plating_cost_merged.price_unit"},
  {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"v_c_plating_cost_merged.defect_rate"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_plating_cost_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.19 其他外加工成本 ────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-其他外加工成本', 'COMP-V5-OUTSOURCE', 5,
$JSON$[
  {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_outsource_merged.process_no"},
  {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_outsource_merged.process_name"},
  {"name":"外加工费用","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"v_c_outsource_merged.unit_price"},
  {"name":"币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_outsource_merged.currency"},
  {"name":"单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_c_outsource_merged.unit"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_outsource_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ── C.20 单重 ──────────────────────────────────────────────────
INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,
                       component_type, data_driver_path, created_at, updated_at)
SELECT gen_random_uuid(), d.id, '核价V5-单重', 'COMP-V5-WEIGHT', 1,
$JSON$[
  {"name":"单重(g/pcs)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"v_c_weight_merged.weight_g_per_pcs"}
]$JSON$::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', 'v_c_weight_merged', now(), now()
FROM component_directory d WHERE d.name = '核价模板组件V5-Excel结构'
ON CONFLICT (code) DO UPDATE SET fields = EXCLUDED.fields, data_driver_path = EXCLUDED.data_driver_path,
                                  column_count = EXCLUDED.column_count, updated_at = now();

-- ════════════════════════════════════════════════════════════════════════════
-- D. 创建 COSTING 模板「核价标准模板-Excel基础结构 v5.0」(DRAFT) + 绑定 20 组件
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_template_id UUID;
    v_default_cat UUID := 'b9576df8-24bf-42b7-b5a7-58bda3a023d2';  -- 沿用 V98
    v_sort INT := 0;
    rec RECORD;
BEGIN
    -- 防重复
    IF EXISTS (SELECT 1 FROM template
                WHERE name = '核价标准模板-Excel基础结构 v5.0'
                  AND template_kind = 'COSTING') THEN
        SELECT id INTO v_template_id FROM template
         WHERE name = '核价标准模板-Excel基础结构 v5.0'
           AND template_kind = 'COSTING'
         LIMIT 1;
        RAISE NOTICE 'V142: 模板已存在 id=%, 重置 template_component 绑定', v_template_id;
        DELETE FROM template_component WHERE template_id = v_template_id;
    ELSE
        v_template_id := gen_random_uuid();
        INSERT INTO template (
            id, template_series_id, name, version, category, category_id, customer_id,
            description, usage_note, product_attributes, subtotal_formula,
            components_snapshot, status, template_kind, created_at, updated_at
        ) VALUES (
            v_template_id, gen_random_uuid(), '核价标准模板-Excel基础结构 v5.0', 'v5.0',
            NULL, v_default_cat, NULL,
            'V142 创建。基于核价系统 5.0 版 Excel 全部 25 个 sheet 中 20 个料号级 sheet, ' ||
            '每 sheet 配置 1 个组件 tab, 数据从核价物理表 (costing_part_*, mat_fee, ' ||
            'mat_customer_part_mapping) 通过 v_c_*_merged 视图按 hf_part_no 自动注入。' ||
            '4 个全局参考 sheet (元素核价/材料核价/汇率/版本) 不做料号 tab, 1 个汇总 sheet 由公式生成。',
            '核价员/销售在每个 tab 查看对应料号的核价数据 (只读); 总公式与基础数据维护见 V98 完整公式版模板。',
            '[]'::jsonb, '[]'::jsonb, NULL,
            'DRAFT', 'COSTING', now(), now()
        );
        RAISE NOTICE 'V142: 创建模板 id=%', v_template_id;
    END IF;

    -- 按 5.0 版 sheet 顺序绑定 20 个组件
    FOR rec IN SELECT * FROM (VALUES
        ('COMP-V5-PART-MAPPING',       '宏丰-客户料号对应关系'),
        ('COMP-V5-RAW-BOM',            '来料BOM'),
        ('COMP-V5-RAW-ELEMENT-BOM',    '来料与元素BOM'),
        ('COMP-V5-LABOR-COST',         '人工成本(单价)'),
        ('COMP-V5-DEPRECIATION',       '设备折旧成本'),
        ('COMP-V5-ENERGY-PROD',        '生产设备能耗成本'),
        ('COMP-V5-ENERGY-AUX',         '辅助设备能耗成本'),
        ('COMP-V5-TOOLING',            '模具工装成本'),
        ('COMP-V5-CONSUMABLE-PROD',    '生产耗材'),
        ('COMP-V5-PACKAGING',          '包装材料'),
        ('COMP-V5-INCOMING-PROC',      '来料加工费'),
        ('COMP-V5-INCOMING-OTHER',     '来料其他费用'),
        ('COMP-V5-INCOMING-FIXED-FEE', '来料其他固定费用'),
        ('COMP-V5-FINISHED-PROC',      '成品加工费&组装费'),
        ('COMP-V5-FINISHED-OTHER',     '成品其他比例费用'),
        ('COMP-V5-FINISHED-FIXED-FEE', '成品其他固定费用'),
        ('COMP-V5-PLATING-SCHEME',     '电镀方案'),
        ('COMP-V5-PLATING-COST',       '电镀成本'),
        ('COMP-V5-OUTSOURCE',          '其他外加工成本'),
        ('COMP-V5-WEIGHT',             '单重')
    ) AS t(code, tab)
    LOOP
        INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, created_at)
        SELECT gen_random_uuid(), v_template_id, c.id, rec.tab, v_sort, now()
        FROM component c
        WHERE c.code = rec.code
        LIMIT 1;
        v_sort := v_sort + 1;
    END LOOP;

    -- 重建 components_snapshot (DRAFT 状态也带, 用户切 PUBLISHED 时省一次重建)
    UPDATE template
       SET components_snapshot = (
            SELECT jsonb_agg(jsonb_build_object(
                'id', tc.id,
                'componentId', tc.component_id,
                'tabName', tc.tab_name,
                'sortOrder', tc.sort_order,
                'componentCode', c.code,
                'componentName', c.name,
                'componentType', c.component_type,
                'data_driver_path', c.data_driver_path,
                'fields', c.fields,
                'formulas', c.formulas
            ) ORDER BY tc.sort_order)
              FROM template_component tc
              JOIN component c ON c.id = tc.component_id
             WHERE tc.template_id = v_template_id
        ),
        updated_at = now()
     WHERE id = v_template_id;

    RAISE NOTICE 'V142: 模板「核价标准模板-Excel基础结构 v5.0」绑定完成, 共 % 个组件', v_sort;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- E. 验证报告
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_view_cnt    INT;
    v_comp_cnt    INT;
    v_tpl_cnt     INT;
    v_bind_cnt    INT;
    v_total_rows  BIGINT := 0;
    v_one_cnt     BIGINT;
    v_view RECORD;
BEGIN
    -- 视图数 (期望 20)
    SELECT COUNT(*) INTO v_view_cnt
      FROM information_schema.views
     WHERE table_schema = 'public' AND table_name LIKE 'v_c_%_merged';

    -- 组件数 (期望 20)
    SELECT COUNT(*) INTO v_comp_cnt FROM component WHERE code LIKE 'COMP-V5-%';

    -- 模板数 (期望 1)
    SELECT COUNT(*) INTO v_tpl_cnt FROM template
     WHERE name = '核价标准模板-Excel基础结构 v5.0' AND template_kind = 'COSTING';

    -- 绑定数 (期望 20)
    SELECT COUNT(*) INTO v_bind_cnt
      FROM template_component tc
      JOIN template t ON t.id = tc.template_id
     WHERE t.name = '核价标准模板-Excel基础结构 v5.0';

    -- 各视图行数累加
    FOR v_view IN
        SELECT table_name FROM information_schema.views
         WHERE table_schema = 'public' AND table_name LIKE 'v_c_%_merged'
         ORDER BY table_name
    LOOP
        EXECUTE format('SELECT COUNT(*) FROM %I', v_view.table_name) INTO v_one_cnt;
        v_total_rows := v_total_rows + v_one_cnt;
        RAISE NOTICE 'V142: 视图 % 行数=%', v_view.table_name, v_one_cnt;
    END LOOP;

    RAISE NOTICE '════════════════════════════════════════════════════════════';
    RAISE NOTICE 'V142 完成 ─────────────────────────────';
    RAISE NOTICE '  视图数 (v_c_*_merged) = % (期望 20)', v_view_cnt;
    RAISE NOTICE '  COMP-V5-* 组件数      = % (期望 20)', v_comp_cnt;
    RAISE NOTICE '  V5 模板数             = % (期望 1)',  v_tpl_cnt;
    RAISE NOTICE '  template_component 绑定数 = % (期望 20)', v_bind_cnt;
    RAISE NOTICE '  20 视图行数总和       = %', v_total_rows;
    RAISE NOTICE '  模板状态: DRAFT (用户审核后手工 publish)';
    RAISE NOTICE '════════════════════════════════════════════════════════════';
END $$;
