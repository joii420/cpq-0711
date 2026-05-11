-- =================================================================
-- PostgreSQL views export from cpq_db (public schema)
-- Generated at: 2026-05-11T05:55:39.200Z
-- Total: 38 views + 0 materialized views
-- =================================================================


-- ----------------------------------------------------------------
-- VIEW: v_c_consumable_prod_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_consumable_prod_merged CASCADE;
CREATE VIEW public.v_c_consumable_prod_merged AS
SELECT hf_part_no,
    process_no,
    process_name,
    unit_price,
    currency,
    unit,
    ref_calc_version,
    notes
   FROM costing_part_process_cost
  WHERE cost_type::text = 'CONSUMABLE'::text AND is_active = true AND COALESCE(process_name, ''::character varying)::text !~~ '%包装%'::text;
COMMENT ON VIEW public.v_c_consumable_prod_merged IS 'V142 核价 5.0: 生产耗材 sheet (CONSUMABLE 中 process_name 不含"包装")';

-- ----------------------------------------------------------------
-- VIEW: v_c_depreciation_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_depreciation_merged CASCADE;
CREATE VIEW public.v_c_depreciation_merged AS
SELECT hf_part_no,
    process_no,
    process_name,
    unit_price,
    currency,
    unit,
    ref_calc_version,
    notes
   FROM costing_part_process_cost
  WHERE cost_type::text = 'DEPRECIATION'::text AND is_active = true;
COMMENT ON VIEW public.v_c_depreciation_merged IS 'V142 核价 5.0: 设备折旧成本 sheet';

-- ----------------------------------------------------------------
-- VIEW: v_c_energy_aux_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_energy_aux_merged CASCADE;
CREATE VIEW public.v_c_energy_aux_merged AS
SELECT hf_part_no,
    process_no,
    process_name,
    unit_price,
    currency,
    unit,
    ref_calc_version,
    notes
   FROM costing_part_process_cost
  WHERE cost_type::text = 'ENERGY_SHARED'::text AND is_active = true;
COMMENT ON VIEW public.v_c_energy_aux_merged IS 'V142 核价 5.0: 辅助设备能耗成本 sheet';

-- ----------------------------------------------------------------
-- VIEW: v_c_energy_prod_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_energy_prod_merged CASCADE;
CREATE VIEW public.v_c_energy_prod_merged AS
SELECT hf_part_no,
    process_no,
    process_name,
    unit_price,
    currency,
    unit,
    ref_calc_version,
    notes
   FROM costing_part_process_cost
  WHERE cost_type::text = 'ENERGY_DEDICATED'::text AND is_active = true;
COMMENT ON VIEW public.v_c_energy_prod_merged IS 'V142 核价 5.0: 生产设备能耗成本 sheet';

-- ----------------------------------------------------------------
-- VIEW: v_c_finished_fixed_fee_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_finished_fixed_fee_merged CASCADE;
CREATE VIEW public.v_c_finished_fixed_fee_merged AS
SELECT hf_part_no,
    customer_id,
    seq_no,
    dim_element_name,
    fee_value,
    currency,
    price_unit
   FROM mat_fee
  WHERE fee_type::text = 'FINISHED_FIXED'::text AND is_current = true AND status::text = 'ACTIVE'::text;
COMMENT ON VIEW public.v_c_finished_fixed_fee_merged IS 'V142 核价 5.0: 成品其他固定费用 sheet — mat_fee[FINISHED_FIXED] (核价 import 路径 TODO)';

-- ----------------------------------------------------------------
-- VIEW: v_c_finished_other_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_finished_other_merged CASCADE;
CREATE VIEW public.v_c_finished_other_merged AS
SELECT hf_part_no,
    customer_id,
    seq_no,
    dim_element_name,
    (fee_ratio * 100::numeric)::numeric(10,4) AS fee_ratio,
    currency,
    price_unit
   FROM mat_fee
  WHERE fee_type::text = 'FINISHED_OTHER'::text AND is_current = true AND status::text = 'ACTIVE'::text;
COMMENT ON VIEW public.v_c_finished_other_merged IS 'V142 核价 5.0: 成品其他比例费用 sheet — mat_fee[FINISHED_OTHER], fee_ratio ×100';

-- ----------------------------------------------------------------
-- VIEW: v_c_finished_proc_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_finished_proc_merged CASCADE;
CREATE VIEW public.v_c_finished_proc_merged AS
SELECT hf_part_no,
    process_no,
    process_name,
    unit_price,
    currency,
    unit,
    ref_calc_version,
    notes
   FROM costing_part_process_cost
  WHERE cost_type::text = 'SEMI_FINISHED_PROC'::text AND is_active = true;
COMMENT ON VIEW public.v_c_finished_proc_merged IS 'V142 核价 5.0: 成品加工费&组装费 sheet';

-- ----------------------------------------------------------------
-- VIEW: v_c_incoming_fixed_fee_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_incoming_fixed_fee_merged CASCADE;
CREATE VIEW public.v_c_incoming_fixed_fee_merged AS
SELECT hf_part_no,
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
  WHERE fee_type::text = 'INCOMING_FIXED'::text AND is_current = true AND status::text = 'ACTIVE'::text;
COMMENT ON VIEW public.v_c_incoming_fixed_fee_merged IS 'V142 核价 5.0: 来料其他固定费用 sheet — mat_fee[INCOMING_FIXED] (核价 import 路径 TODO)';

-- ----------------------------------------------------------------
-- VIEW: v_c_incoming_other_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_incoming_other_merged CASCADE;
CREATE VIEW public.v_c_incoming_other_merged AS
SELECT hf_part_no,
    customer_id,
    seq_no,
    dim_input_material_no,
    dim_sub_seq_no,
    dim_element_name,
    (fee_ratio * 100::numeric)::numeric(10,4) AS fee_ratio,
    currency,
    price_unit
   FROM mat_fee
  WHERE fee_type::text = 'INCOMING_OTHER'::text AND is_current = true AND status::text = 'ACTIVE'::text;
COMMENT ON VIEW public.v_c_incoming_other_merged IS 'V142 核价 5.0: 来料其他费用(比例) sheet — mat_fee[INCOMING_OTHER], fee_ratio ×100';

-- ----------------------------------------------------------------
-- VIEW: v_c_incoming_proc_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_incoming_proc_merged CASCADE;
CREATE VIEW public.v_c_incoming_proc_merged AS
SELECT hf_part_no,
    process_no,
    process_name,
    unit_price,
    currency,
    unit,
    ref_calc_version,
    notes
   FROM costing_part_process_cost
  WHERE cost_type::text = 'MATERIAL_PROC'::text AND is_active = true;
COMMENT ON VIEW public.v_c_incoming_proc_merged IS 'V142 核价 5.0: 来料加工费 sheet';

-- ----------------------------------------------------------------
-- VIEW: v_c_labor_cost_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_labor_cost_merged CASCADE;
CREATE VIEW public.v_c_labor_cost_merged AS
SELECT hf_part_no,
    process_no,
    process_name,
    unit_price,
    currency,
    unit,
    ref_calc_version,
    notes
   FROM costing_part_process_cost
  WHERE cost_type::text = 'LABOR'::text AND is_active = true;
COMMENT ON VIEW public.v_c_labor_cost_merged IS 'V142 核价 5.0: 人工成本(单价) sheet';

-- ----------------------------------------------------------------
-- VIEW: v_c_outsource_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_outsource_merged CASCADE;
CREATE VIEW public.v_c_outsource_merged AS
SELECT hf_part_no,
    process_no,
    process_name,
    unit_price,
    currency,
    unit,
    ref_calc_version,
    notes
   FROM costing_part_process_cost
  WHERE cost_type::text = 'POST_PROC'::text AND is_active = true;
COMMENT ON VIEW public.v_c_outsource_merged IS 'V142 核价 5.0: 其他外加工成本 sheet';

-- ----------------------------------------------------------------
-- VIEW: v_c_packaging_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_packaging_merged CASCADE;
CREATE VIEW public.v_c_packaging_merged AS
SELECT hf_part_no,
    process_no,
    process_name,
    unit_price,
    currency,
    unit,
    ref_calc_version,
    notes
   FROM costing_part_process_cost
  WHERE cost_type::text = 'CONSUMABLE'::text AND is_active = true AND COALESCE(process_name, ''::character varying)::text ~~ '%包装%'::text;
COMMENT ON VIEW public.v_c_packaging_merged IS 'V142 核价 5.0: 包装材料 sheet (CONSUMABLE 中 process_name 含"包装"关键字; 无数据时为空)';

-- ----------------------------------------------------------------
-- VIEW: v_c_part_mapping_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_part_mapping_merged CASCADE;
CREATE VIEW public.v_c_part_mapping_merged AS
SELECT hf_part_no,
    customer_id,
    customer_part_name,
    customer_product_no,
    customer_drawing_no,
    payment_method,
    base_currency,
    quote_currency
   FROM mat_customer_part_mapping m;
COMMENT ON VIEW public.v_c_part_mapping_merged IS 'V142 核价 5.0: 客户料号对应关系 sheet';

-- ----------------------------------------------------------------
-- VIEW: v_c_plating_cost_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_plating_cost_merged CASCADE;
CREATE VIEW public.v_c_plating_cost_merged AS
SELECT hf_part_no,
    plating_plan_code AS plan_code,
    plan_version,
    plating_process_fee,
    plating_material_fee,
    currency,
    price_unit,
    (defect_rate * 100::numeric)::numeric(10,4) AS defect_rate
   FROM costing_part_plating_fee
  WHERE is_active = true;
COMMENT ON VIEW public.v_c_plating_cost_merged IS 'V142 核价 5.0: 电镀成本 sheet (defect_rate ×100)';

-- ----------------------------------------------------------------
-- VIEW: v_c_plating_scheme_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_plating_scheme_merged CASCADE;
CREATE VIEW public.v_c_plating_scheme_merged AS
SELECT f.hf_part_no,
    f.plating_plan_code AS plan_code,
    f.plan_version,
    cpp.seq_no,
    cpp.element_attr AS plating_element,
    cpp.plating_area_cm2 AS plating_area,
    cpp.layer_thickness_um AS coating_thickness,
    cpp.requirement AS plating_requirement
   FROM costing_part_plating_fee f
     LEFT JOIN costing_part_plating cpp ON cpp.plating_no::text = f.plating_plan_code::text AND cpp.version_number::text = f.plan_version::text AND cpp.is_active = true
  WHERE f.is_active = true;
COMMENT ON VIEW public.v_c_plating_scheme_merged IS 'V142 核价 5.0: 电镀方案 sheet (LEFT JOIN by plan_code+version, V141 模式)';

-- ----------------------------------------------------------------
-- VIEW: v_c_raw_bom_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_raw_bom_merged CASCADE;
CREATE VIEW public.v_c_raw_bom_merged AS
SELECT hf_part_no,
    seq_no,
    input_material_no,
    process_no,
    process_name,
    input_qty,
    input_unit,
    output_qty,
    output_unit,
    (output_loss_rate * 100::numeric)::numeric(10,4) AS output_loss_rate,
    fixed_loss_qty,
    (loss_rate * 100::numeric)::numeric(10,4) AS loss_rate
   FROM costing_part_material_bom b
  WHERE is_active = true;
COMMENT ON VIEW public.v_c_raw_bom_merged IS 'V142 核价 5.0: 来料BOM sheet (loss_rate/output_loss_rate ×100)';

-- ----------------------------------------------------------------
-- VIEW: v_c_raw_bom_priced
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_raw_bom_priced CASCADE;
CREATE VIEW public.v_c_raw_bom_priced AS
SELECT m.hf_part_no,
    m.seq_no,
    m.input_material_no,
    m.process_no,
    m.process_name,
    m.input_qty,
    m.output_qty,
    COALESCE(m.loss_rate, 0::numeric) AS loss_rate,
    COALESCE(m.fixed_loss_qty, 0::numeric) AS fixed_loss_qty,
    eb.element_code,
    COALESCE(eb.composition_pct, 0::numeric) / 100.0 AS elem_pct_decimal,
    COALESCE(eb.loss_rate, 0::numeric) / 100.0 AS elem_loss_rate,
    COALESCE(cep.costing_price, 0::numeric) AS elem_price,
    COALESCE(cep.discount_rate, 0::numeric) / 100.0 AS elem_discount,
    COALESCE(cmp.costing_price, 0::numeric) AS mat_price,
    COALESCE(cmp.discount_rate, 0::numeric) / 100.0 AS mat_discount,
        CASE
            WHEN eb.element_code IS NULL THEN COALESCE(cmp.costing_price, 0::numeric)
            ELSE COALESCE(cep.costing_price, 0::numeric) * COALESCE(eb.composition_pct, 0::numeric) / 100.0
        END AS unit_price,
        CASE
            WHEN eb.element_code IS NULL THEN COALESCE(cmp.costing_price, 0::numeric) * COALESCE(cmp.discount_rate, 0::numeric) / 100.0
            ELSE COALESCE(cep.costing_price, 0::numeric) * COALESCE(eb.composition_pct, 0::numeric) / 100.0 * COALESCE(cep.discount_rate, 0::numeric) / 100.0
        END AS unit_price_recycle,
        CASE
            WHEN m.input_qty >= 0::numeric THEN 'NORMAL'::text
            ELSE 'RECYCLE'::text
        END AS bom_kind
   FROM costing_part_material_bom m
     LEFT JOIN costing_part_element_bom eb ON eb.input_material_no::text = m.input_material_no::text AND COALESCE(eb.is_active, true) = true
     LEFT JOIN v_costing_element_price cep ON cep.element_code::text = eb.element_code::text
     LEFT JOIN v_costing_material_price cmp ON cmp.material_no::text = m.input_material_no::text
  WHERE COALESCE(m.is_active, true) = true;
COMMENT ON VIEW public.v_c_raw_bom_priced IS 'V148 修复: 来料BOM × 元素BOM × 元素价 × 材料价 四表合并视图。字段单位与 V111 bom_expanded 完全一致: loss_rate(小数,底层表原值), elem_pct_decimal=composition_pct/100, elem_loss_rate=loss_rate/100, elem_discount=discount_rate/100, mat_discount=discount_rate/100, unit_price=elem_price*(composition_pct/100) or mat_price. 专供 SUM_OVER 模板公式 (纯材料成本/回收成本/材料损耗成本) 使用。';

-- ----------------------------------------------------------------
-- VIEW: v_c_raw_element_bom_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_raw_element_bom_merged CASCADE;
CREATE VIEW public.v_c_raw_element_bom_merged AS
SELECT mb.hf_part_no,
    mb.seq_no AS material_seq_no,
    eb.input_material_no,
    eb.seq_no AS element_seq_no,
    eb.element_code,
    (eb.composition_pct * 100::numeric)::numeric(10,4) AS composition_pct,
    (eb.loss_rate * 100::numeric)::numeric(10,4) AS loss_rate
   FROM costing_part_element_bom eb
     JOIN costing_part_material_bom mb ON mb.input_material_no::text = eb.input_material_no::text AND mb.is_active = true
  WHERE eb.is_active = true;
COMMENT ON VIEW public.v_c_raw_element_bom_merged IS 'V142 核价 5.0: 来料与元素BOM (元素BOM × 材料BOM by input_material_no, composition_pct/loss_rate ×100)';

-- ----------------------------------------------------------------
-- VIEW: v_c_summary_agg
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_summary_agg CASCADE;
CREATE VIEW public.v_c_summary_agg AS
WITH base_parts AS (
         SELECT DISTINCT costing_part_weight.hf_part_no
           FROM costing_part_weight
          WHERE COALESCE(costing_part_weight.is_active, true) = true
        UNION
         SELECT DISTINCT costing_part_process_cost.hf_part_no
           FROM costing_part_process_cost
          WHERE COALESCE(costing_part_process_cost.is_active, true) = true
        UNION
         SELECT DISTINCT mat_fee.hf_part_no
           FROM mat_fee
          WHERE COALESCE(mat_fee.is_current, true) = true AND COALESCE(mat_fee.status, 'ACTIVE'::character varying)::text = 'ACTIVE'::text
        ), pkg_agg AS (
         SELECT costing_part_process_cost.hf_part_no,
            sum(costing_part_process_cost.unit_price) AS packaging_fee
           FROM costing_part_process_cost
          WHERE costing_part_process_cost.cost_type::text = 'CONSUMABLE'::text AND COALESCE(costing_part_process_cost.process_name, ''::character varying)::text ~~ '%包装%'::text AND COALESCE(costing_part_process_cost.is_active, true) = true
          GROUP BY costing_part_process_cost.hf_part_no
        ), ifix_agg AS (
         SELECT mat_fee.hf_part_no,
            sum(mat_fee.fee_value) AS incoming_fixed_fee
           FROM mat_fee
          WHERE mat_fee.fee_type::text = 'INCOMING_FIXED'::text AND COALESCE(mat_fee.is_current, true) = true AND COALESCE(mat_fee.status, 'ACTIVE'::character varying)::text = 'ACTIVE'::text
          GROUP BY mat_fee.hf_part_no
        ), outsource_agg AS (
         SELECT costing_part_process_cost.hf_part_no,
            sum(costing_part_process_cost.unit_price) AS outsource_fee
           FROM costing_part_process_cost
          WHERE costing_part_process_cost.cost_type::text = 'POST_PROC'::text AND COALESCE(costing_part_process_cost.is_active, true) = true
          GROUP BY costing_part_process_cost.hf_part_no
        ), ffix_agg AS (
         SELECT mat_fee.hf_part_no,
            sum(
                CASE
                    WHEN mat_fee.dim_element_name::text ~~ '%运费%'::text THEN mat_fee.fee_value
                    ELSE 0::numeric
                END) AS freight_fee,
            sum(
                CASE
                    WHEN mat_fee.dim_element_name::text ~~ '%清关%'::text THEN mat_fee.fee_value
                    ELSE 0::numeric
                END) AS customs_fee
           FROM mat_fee
          WHERE mat_fee.fee_type::text = 'FINISHED_FIXED'::text AND COALESCE(mat_fee.is_current, true) = true AND COALESCE(mat_fee.status, 'ACTIVE'::character varying)::text = 'ACTIVE'::text
          GROUP BY mat_fee.hf_part_no
        )
 SELECT p.hf_part_no,
    COALESCE(pkg.packaging_fee, 0::numeric) AS packaging_fee,
    COALESCE(ifix.incoming_fixed_fee, 0::numeric) AS incoming_fixed_fee,
    COALESCE(out_.outsource_fee, 0::numeric) AS outsource_fee,
    COALESCE(ff.freight_fee, 0::numeric) AS freight_fee,
    COALESCE(ff.customs_fee, 0::numeric) AS customs_fee,
    'CNY'::character varying(10) AS currency_label,
    'KG'::character varying(10) AS weight_unit_label
   FROM base_parts p
     LEFT JOIN pkg_agg pkg ON pkg.hf_part_no::text = p.hf_part_no::text
     LEFT JOIN ifix_agg ifix ON ifix.hf_part_no::text = p.hf_part_no::text
     LEFT JOIN outsource_agg out_ ON out_.hf_part_no::text = p.hf_part_no::text
     LEFT JOIN ffix_agg ff ON ff.hf_part_no::text = p.hf_part_no::text;
COMMENT ON VIEW public.v_c_summary_agg IS 'V144: 每 hf_part_no 聚合一行，提供 packaging_fee / incoming_fixed_fee / outsource_fee / freight_fee / customs_fee / currency_label(CNY) / weight_unit_label(KG)';

-- ----------------------------------------------------------------
-- VIEW: v_c_tooling_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_tooling_merged CASCADE;
CREATE VIEW public.v_c_tooling_merged AS
SELECT hf_part_no,
    process_no,
    process_name,
    seq_no,
    tooling_no,
    tooling_unit_cost,
    process_count,
    cycle_count,
    unit_price,
    currency,
    unit,
    notes
   FROM costing_part_tooling_cost
  WHERE is_active = true;
COMMENT ON VIEW public.v_c_tooling_merged IS 'V142 核价 5.0: 模具工装成本 sheet';

-- ----------------------------------------------------------------
-- VIEW: v_c_weight_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_c_weight_merged CASCADE;
CREATE VIEW public.v_c_weight_merged AS
SELECT hf_part_no,
    weight_g_per_pcs,
    notes
   FROM costing_part_weight
  WHERE is_active = true;
COMMENT ON VIEW public.v_c_weight_merged IS 'V142 核价 5.0: 单重 sheet';

-- ----------------------------------------------------------------
-- VIEW: v_costing_element_price
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_costing_element_price CASCADE;
CREATE VIEW public.v_costing_element_price AS
SELECT e.id,
    e.element_code,
    e.costing_price,
    e.market_ref_price,
    e.source_url,
    e.source_name,
    e.source_rule,
    e.currency,
    e.unit,
    e.discount_rate,
    e.sort_order,
    e.element_code AS element_name
   FROM costing_element_price e
     JOIN costing_price_version v ON v.id = e.version_id
  WHERE v.version_kind::text = 'ELEMENT'::text AND v.status::text = 'PUBLISHED'::text AND v.is_default = true;

-- ----------------------------------------------------------------
-- VIEW: v_costing_exchange_rate
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_costing_exchange_rate CASCADE;
CREATE VIEW public.v_costing_exchange_rate AS
SELECT r.id,
    r.from_currency,
    r.to_currency,
    r.costing_rate,
    r.market_rate,
    r.rate_rule,
    r.source_url,
    r.sort_order
   FROM costing_exchange_rate r
     JOIN costing_price_version v ON v.id = r.version_id
  WHERE v.version_kind::text = 'EXCHANGE'::text AND v.status::text = 'PUBLISHED'::text AND v.is_default = true;
COMMENT ON VIEW public.v_costing_exchange_rate IS '核价汇率 — 自动锁定到默认 PUBLISHED 版本';

-- ----------------------------------------------------------------
-- VIEW: v_costing_material_price
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_costing_material_price CASCADE;
CREATE VIEW public.v_costing_material_price AS
SELECT m.id,
    m.material_no,
    m.brand_name,
    m.spec,
    m.dimension,
    m.costing_price,
    m.market_ref_price,
    m.source_url,
    m.source_name,
    m.source_rule,
    m.currency,
    m.unit,
    m.discount_rate,
    m.sort_order,
    m.material_no AS input_material_no
   FROM costing_material_price m
     JOIN costing_price_version v ON v.id = m.version_id
  WHERE v.version_kind::text = 'MATERIAL'::text AND v.status::text = 'PUBLISHED'::text AND v.is_default = true;

-- ----------------------------------------------------------------
-- VIEW: v_costing_summary_full
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_costing_summary_full CASCADE;
CREATE VIEW public.v_costing_summary_full AS
WITH bom_expanded AS (
         SELECT m.hf_part_no,
            m.input_qty AS bom_input_qty,
            NULLIF(m.output_qty, 0::numeric) AS bom_output_qty,
            COALESCE(m.loss_rate, 0::numeric) / 100.0 AS bom_loss_rate,
            COALESCE(m.fixed_loss_qty, 0::numeric) AS bom_fixed_loss_qty,
            eb.element_code,
            COALESCE(eb.composition_pct, 0::numeric) / 100.0 AS elem_pct,
            COALESCE(eb.loss_rate, 0::numeric) / 100.0 AS elem_loss_rate,
            COALESCE(cep.costing_price, 0::numeric) AS elem_price,
            COALESCE(cep.discount_rate, 0::numeric) / 100.0 AS elem_discount,
            COALESCE(cmp.costing_price, 0::numeric) AS mat_price,
            COALESCE(cmp.discount_rate, 0::numeric) / 100.0 AS mat_discount,
                CASE
                    WHEN m.input_qty >= 0::numeric THEN 'NORMAL'::text
                    ELSE 'RECYCLE'::text
                END AS bom_kind
           FROM costing_part_material_bom m
             LEFT JOIN costing_part_element_bom eb ON eb.input_material_no::text = m.input_material_no::text AND COALESCE(eb.is_active, true) = true
             LEFT JOIN v_costing_element_price cep ON cep.element_code::text = eb.element_code::text
             LEFT JOIN v_costing_material_price cmp ON cmp.material_no::text = m.input_material_no::text
          WHERE COALESCE(m.is_active, true) = true
        ), bom_priced AS (
         SELECT bom_expanded.hf_part_no,
            bom_expanded.bom_input_qty,
            bom_expanded.bom_output_qty,
            bom_expanded.bom_loss_rate,
            bom_expanded.bom_fixed_loss_qty,
            bom_expanded.elem_loss_rate,
            bom_expanded.bom_kind,
            bom_expanded.elem_price * bom_expanded.elem_pct + bom_expanded.mat_price *
                CASE
                    WHEN bom_expanded.element_code IS NULL THEN 1
                    ELSE 0
                END::numeric AS unit_price,
            bom_expanded.elem_price * bom_expanded.elem_pct * bom_expanded.elem_discount + bom_expanded.mat_price * bom_expanded.mat_discount *
                CASE
                    WHEN bom_expanded.element_code IS NULL THEN 1
                    ELSE 0
                END::numeric AS unit_price_recycle
           FROM bom_expanded
        ), material_aggs AS (
         SELECT bom_priced.hf_part_no,
            sum(
                CASE
                    WHEN bom_priced.bom_kind = 'NORMAL'::text THEN abs(bom_priced.bom_input_qty) / NULLIF(bom_priced.bom_output_qty, 0::numeric) * (1::numeric + bom_priced.bom_loss_rate) * bom_priced.unit_price
                    ELSE 0::numeric
                END) AS pure_material_cost,
            sum(
                CASE
                    WHEN bom_priced.bom_kind = 'RECYCLE'::text THEN abs(bom_priced.bom_input_qty) / NULLIF(bom_priced.bom_output_qty, 0::numeric) * bom_priced.unit_price_recycle
                    ELSE 0::numeric
                END) AS recycle_cost,
            sum(
                CASE
                    WHEN bom_priced.bom_kind = 'NORMAL'::text THEN abs(bom_priced.bom_input_qty) / NULLIF(bom_priced.bom_output_qty, 0::numeric) * (1::numeric + bom_priced.bom_loss_rate) * bom_priced.elem_loss_rate * bom_priced.unit_price + bom_priced.bom_fixed_loss_qty * bom_priced.unit_price
                    ELSE 0::numeric
                END) AS material_loss_cost
           FROM bom_priced
          GROUP BY bom_priced.hf_part_no
        ), process_costs AS (
         SELECT pc_1.hf_part_no,
            sum(
                CASE
                    WHEN pc_1.cost_type::text = 'MATERIAL_PROC'::text THEN pc_1.unit_price
                    ELSE 0::numeric
                END) AS incoming_process_fee,
            sum(
                CASE
                    WHEN pc_1.cost_type::text = ANY (ARRAY['LABOR'::character varying::text, 'DEPRECIATION'::character varying::text, 'ENERGY_DEDICATED'::character varying::text, 'ENERGY_SHARED'::character varying::text, 'CONSUMABLE'::character varying::text, 'SEMI_FINISHED_PROC'::character varying::text, 'POST_PROC'::character varying::text]) THEN pc_1.unit_price
                    ELSE 0::numeric
                END) AS process_fee_base
           FROM costing_part_process_cost pc_1
          WHERE COALESCE(pc_1.is_active, true) = true
          GROUP BY pc_1.hf_part_no
        ), plating_data AS (
         SELECT pf.hf_part_no,
            sum(COALESCE(pf.plating_process_fee, 0::numeric)) AS plating_process_fee,
            sum(COALESCE(pf.plating_material_fee, 0::numeric)) AS plating_material_fee,
            avg(COALESCE(pf.defect_rate, 0::numeric)) AS plating_defect_rate
           FROM plating_fee pf
          WHERE COALESCE(pf.is_current, true) = true
          GROUP BY pf.hf_part_no
        ), weight_data AS (
         SELECT costing_part_weight.hf_part_no,
            costing_part_weight.weight_g_per_pcs
           FROM costing_part_weight
          WHERE COALESCE(costing_part_weight.is_active, true) = true
        ), exchange_data AS (
         SELECT v_costing_exchange_rate.costing_rate AS exchange_rate_to_usd
           FROM v_costing_exchange_rate
          WHERE v_costing_exchange_rate.from_currency::text = 'CNY'::text AND v_costing_exchange_rate.to_currency::text = 'USD'::text
         LIMIT 1
        ), fee_ratios AS (
         SELECT f.hf_part_no,
            sum(
                CASE
                    WHEN f.dim_element_name::text ~~ '%管理%'::text THEN COALESCE(f.fee_ratio, 0::numeric)
                    ELSE 0::numeric
                END) AS mgmt_fee_ratio,
            sum(
                CASE
                    WHEN f.dim_element_name::text ~~ '%财务%'::text THEN COALESCE(f.fee_ratio, 0::numeric)
                    ELSE 0::numeric
                END) AS finance_fee_ratio,
            sum(
                CASE
                    WHEN f.dim_element_name::text ~~ '%利润%'::text THEN COALESCE(f.fee_ratio, 0::numeric)
                    ELSE 0::numeric
                END) AS profit_ratio,
            sum(
                CASE
                    WHEN f.dim_element_name::text ~~ '%税%'::text THEN COALESCE(f.fee_ratio, 0::numeric)
                    ELSE 0::numeric
                END) AS tax_ratio
           FROM mat_fee f
          WHERE f.fee_type::text = 'FINISHED_OTHER'::text AND COALESCE(f.is_current, true) = true
          GROUP BY f.hf_part_no
        ), incoming_other AS (
         SELECT f.hf_part_no,
            sum(COALESCE(f.fee_ratio, 0::numeric)) AS incoming_other_total_ratio
           FROM mat_fee f
          WHERE f.fee_type::text = 'INCOMING_OTHER'::text AND COALESCE(f.is_current, true) = true
          GROUP BY f.hf_part_no
        ), agg_old AS (
         SELECT s.id AS summary_id,
            s.summary_no,
            s.hf_part_no,
            s.status,
            s.quote_currency,
            s.element_version_id,
            s.material_version_id,
            s.exchange_version_id,
            max(
                CASE
                    WHEN r.metric_code::text = 'MATERIAL_COST'::text THEN r.value
                    ELSE NULL::numeric
                END) AS metric_material_cost,
            max(
                CASE
                    WHEN r.metric_code::text = 'PROCESS_FEE'::text THEN r.value
                    ELSE NULL::numeric
                END) AS metric_processing_cost
           FROM costing_summary s
             LEFT JOIN costing_summary_result r ON r.summary_id = s.id
          GROUP BY s.id, s.summary_no, s.hf_part_no, s.status, s.quote_currency, s.element_version_id, s.material_version_id, s.exchange_version_id
        )
 SELECT a.summary_id,
    a.summary_no,
    a.hf_part_no,
    row_number() OVER (PARTITION BY a.hf_part_no ORDER BY a.summary_no)::integer AS line_seq,
    a.status,
        CASE a.status
            WHEN 'PUBLISHED'::text THEN '是'::text
            ELSE '否'::text
        END AS is_published_label,
    a.quote_currency,
    'KG'::character varying(10) AS weight_unit,
    ev.version_number AS element_version_number,
    mv.version_number AS material_version_number,
    xv.version_number AS exchange_version_number,
    a.metric_material_cost AS material_cost,
    a.metric_processing_cost AS processing_cost,
    ma.pure_material_cost,
    ma.recycle_cost,
    ma.material_loss_cost,
    pc.incoming_process_fee,
    ma.pure_material_cost * io.incoming_other_total_ratio AS incoming_other_fee,
    pc.process_fee_base * (1::numeric + COALESCE(( SELECT bom_priced.bom_loss_rate
           FROM bom_priced
          WHERE bom_priced.hf_part_no::text = a.hf_part_no::text
         LIMIT 1), 0::numeric)) AS process_fee_total,
    pld.plating_process_fee,
    pld.plating_material_fee,
    pld.plating_defect_rate,
    0::numeric AS outsource_fee_total,
    w.weight_g_per_pcs AS unit_weight_g,
    e.exchange_rate_to_usd,
    fr.mgmt_fee_ratio,
    fr.finance_fee_ratio,
    fr.profit_ratio,
    fr.tax_ratio
   FROM agg_old a
     LEFT JOIN material_aggs ma ON ma.hf_part_no::text = a.hf_part_no::text
     LEFT JOIN process_costs pc ON pc.hf_part_no::text = a.hf_part_no::text
     LEFT JOIN plating_data pld ON pld.hf_part_no::text = a.hf_part_no::text
     LEFT JOIN weight_data w ON w.hf_part_no::text = a.hf_part_no::text
     LEFT JOIN exchange_data e ON true
     LEFT JOIN fee_ratios fr ON fr.hf_part_no::text = a.hf_part_no::text
     LEFT JOIN incoming_other io ON io.hf_part_no::text = a.hf_part_no::text
     LEFT JOIN costing_price_version ev ON ev.id = a.element_version_id
     LEFT JOIN costing_price_version mv ON mv.id = a.material_version_id
     LEFT JOIN costing_price_version xv ON xv.id = a.exchange_version_id;
COMMENT ON VIEW public.v_costing_summary_full IS 'V111: 去掉 COALESCE(_, 0), 无数据料号的成本字段 NULL 传递; 前端识别整行 NULL 显示空白';

-- ----------------------------------------------------------------
-- VIEW: v_part_element_price
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_part_element_price CASCADE;
CREATE VIEW public.v_part_element_price AS
SELECT element_code AS element_name,
    element_code,
    costing_price,
    market_ref_price,
    currency,
    unit,
    discount_rate,
    sort_order
   FROM v_costing_element_price;
COMMENT ON VIEW public.v_part_element_price IS 'V109 起 deprecated, 字段路径已直接切到 v_costing_element_price (= ELEM_PRICE 全局变量); 保留作向后兼容';

-- ----------------------------------------------------------------
-- VIEW: v_part_material_price
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_part_material_price CASCADE;
CREATE VIEW public.v_part_material_price AS
SELECT material_no AS input_material_no,
    material_no,
    brand_name,
    spec,
    dimension,
    costing_price,
    market_ref_price,
    currency,
    unit,
    discount_rate,
    sort_order
   FROM v_costing_material_price;
COMMENT ON VIEW public.v_part_material_price IS 'V109 起 deprecated, 字段路径已直接切到 v_costing_material_price (= MAT_PRICE 全局变量); 保留作向后兼容';

-- ----------------------------------------------------------------
-- VIEW: v_part_plating_scheme
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_part_plating_scheme CASCADE;
CREATE VIEW public.v_part_plating_scheme AS
SELECT pf.hf_part_no,
    NULL::uuid AS customer_id,
    cpp.plating_no AS plan_code,
    cpp.version_number AS version,
    cpp.seq_no,
    cpp.element_attr AS plating_element,
    cpp.plating_area_cm2 AS plating_area,
    cpp.layer_thickness_um AS coating_thickness,
    cpp.requirement AS plating_requirement
   FROM costing_part_plating_fee pf
     JOIN costing_part_plating cpp ON pf.plating_plan_code::text = cpp.plating_no::text AND pf.plan_version::text = cpp.version_number::text
  WHERE pf.is_active = true;
COMMENT ON VIEW public.v_part_plating_scheme IS 'V126: 核价侧 — costing_part_plating_fee × costing_part_plating, 让 COMP-V4-PLATING-SCHEME 通过 partNo 取多行';

-- ----------------------------------------------------------------
-- VIEW: v_q_assembly_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_q_assembly_merged CASCADE;
CREATE VIEW public.v_q_assembly_merged AS
SELECT 'ASSEMBLY_PROCESS'::character varying AS source_type,
    hf_part_no,
    seq_no,
    dim_assembly_process AS assembly_process,
    fee_value,
    currency,
    price_unit,
    (reject_rate * 100::numeric)::numeric(10,4) AS reject_rate
   FROM mat_fee
  WHERE fee_type::text = 'ASSEMBLY_PROCESS'::text AND is_current = true;
COMMENT ON VIEW public.v_q_assembly_merged IS 'V128+V136: 组装加工合并视图 - reject_rate 已 x100 显示 (V133 漏修)';

-- ----------------------------------------------------------------
-- VIEW: v_q_component_fee_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_q_component_fee_merged CASCADE;
CREATE VIEW public.v_q_component_fee_merged AS
SELECT 'COMPONENT_OTHER'::character varying AS source_type,
    hf_part_no,
    seq_no,
    dim_assembly_process AS assembly_process,
    dim_sub_seq_no AS sub_seq_no,
    dim_input_material_no AS component_part_no,
    dim_input_material_name AS component_name,
    dim_element_name AS element_name,
    fee_value,
    currency,
    price_unit
   FROM mat_fee
  WHERE fee_type::text = 'COMPONENT_OTHER'::text AND is_current = true;

-- ----------------------------------------------------------------
-- VIEW: v_q_component_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_q_component_merged CASCADE;
CREATE VIEW public.v_q_component_merged AS
SELECT 'COMPONENT_BOM'::character varying AS source_type,
    hf_part_no,
    seq_no,
    process_code,
    assembly_process,
    sub_seq_no,
    component_part_no,
    component_name,
    supplier_code,
    supplier_name,
    quantity,
    quantity_unit,
    unit_price,
    freight,
    currency,
    price_unit
   FROM mat_process
  WHERE is_current = true;
COMMENT ON VIEW public.v_q_component_merged IS 'V128: 组成件合并视图 — mat_process[is_current=true]';

-- ----------------------------------------------------------------
-- VIEW: v_q_element_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_q_element_merged CASCADE;
CREATE VIEW public.v_q_element_merged AS
SELECT 'BOM'::character varying AS source_type,
    mat_bom.hf_part_no,
    mat_bom.input_material_no,
    mat_bom.input_material_name,
    mat_bom.seq_no,
    mat_bom.element_name,
    mat_bom.composition_pct,
    (mat_bom.loss_rate * 100::numeric)::numeric(10,4) AS loss_rate,
    mat_bom.gross_qty,
    mat_bom.gross_unit,
    mat_bom.net_qty,
    mat_bom.net_unit,
    NULL::numeric(10,4) AS recycle_pct
   FROM mat_bom
  WHERE mat_bom.bom_type::text = 'ELEMENT'::text
UNION ALL
 SELECT 'ELEMENT_RECYCLE'::character varying AS source_type,
    mat_fee.hf_part_no,
    mat_fee.dim_input_material_no AS input_material_no,
    mat_fee.dim_input_material_name AS input_material_name,
    mat_fee.seq_no,
    mat_fee.dim_element_name AS element_name,
    NULL::numeric(10,4) AS composition_pct,
    NULL::numeric(10,4) AS loss_rate,
    NULL::numeric(18,4) AS gross_qty,
    NULL::character varying(16) AS gross_unit,
    NULL::numeric(18,4) AS net_qty,
    NULL::character varying(16) AS net_unit,
    (mat_fee.fee_ratio * 100::numeric)::numeric(10,4) AS recycle_pct
   FROM mat_fee
  WHERE mat_fee.fee_type::text = 'ELEMENT_RECYCLE'::text AND mat_fee.is_current = true;
COMMENT ON VIEW public.v_q_element_merged IS 'V128+V133+V135: 元素合并视图 -- composition_pct 整数百分比直存不×100；loss_rate/recycle_pct 已×100显示';

-- ----------------------------------------------------------------
-- VIEW: v_q_finished_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_q_finished_merged CASCADE;
CREATE VIEW public.v_q_finished_merged AS
SELECT 'FINISHED_FIXED'::character varying AS source_type,
    mat_fee.hf_part_no,
    mat_fee.seq_no,
    NULL::character varying(128) AS element_name,
    mat_fee.fee_value,
    (mat_fee.fee_ratio * 100::numeric)::numeric(10,4) AS fee_ratio,
    mat_fee.currency,
    mat_fee.price_unit
   FROM mat_fee
  WHERE mat_fee.fee_type::text = 'FINISHED_FIXED'::text AND mat_fee.is_current = true
UNION ALL
 SELECT 'FINISHED_OTHER'::character varying AS source_type,
    mat_fee.hf_part_no,
    mat_fee.seq_no,
    mat_fee.dim_element_name AS element_name,
    mat_fee.fee_value,
    (mat_fee.fee_ratio * 100::numeric)::numeric(10,4) AS fee_ratio,
    mat_fee.currency,
    mat_fee.price_unit
   FROM mat_fee
  WHERE mat_fee.fee_type::text = 'FINISHED_OTHER'::text AND mat_fee.is_current = true;
COMMENT ON VIEW public.v_q_finished_merged IS 'V128+V133: 成品合并视图 -- 百分比列已 x100 显示（fee_ratio）';

-- ----------------------------------------------------------------
-- VIEW: v_q_incoming_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_q_incoming_merged CASCADE;
CREATE VIEW public.v_q_incoming_merged AS
SELECT 'BOM'::character varying AS source_type,
    mat_bom.hf_part_no,
    mat_bom.seq_no,
    mat_bom.input_material_no,
    mat_bom.input_material_name,
    mat_bom.output_material_type,
    mat_bom.gross_qty,
    mat_bom.net_qty,
    mat_bom.gross_unit AS weight_unit,
    (mat_bom.loss_rate * 100::numeric)::numeric(10,4) AS loss_rate,
    (mat_bom.defect_rate * 100::numeric)::numeric(10,4) AS defect_rate,
    NULL::integer AS sub_seq_no,
    NULL::character varying(128) AS element_name,
    NULL::numeric(18,4) AS fee_value,
    NULL::numeric(10,4) AS fee_ratio,
    NULL::character varying(8) AS currency,
    NULL::character varying(16) AS price_unit,
    NULL::boolean AS price_floating,
    NULL::numeric(10,4) AS settlement_rise_ratio,
    NULL::numeric(18,4) AS fixed_rise_value,
    NULL::character varying(8) AS rise_currency,
    NULL::character varying(16) AS rise_unit,
    NULL::numeric(10,4) AS recycle_pct
   FROM mat_bom
  WHERE mat_bom.bom_type::text = 'INCOMING'::text
UNION ALL
 SELECT 'INCOMING_FIXED'::character varying AS source_type,
    mat_fee.hf_part_no,
    mat_fee.seq_no,
    mat_fee.dim_input_material_no AS input_material_no,
    mat_fee.dim_input_material_name AS input_material_name,
    NULL::character varying(64) AS output_material_type,
    NULL::numeric(18,4) AS gross_qty,
    NULL::numeric(18,4) AS net_qty,
    NULL::character varying(16) AS weight_unit,
    NULL::numeric(10,4) AS loss_rate,
    NULL::numeric(10,4) AS defect_rate,
    NULL::integer AS sub_seq_no,
    NULL::character varying(128) AS element_name,
    mat_fee.fee_value,
    (mat_fee.fee_ratio * 100::numeric)::numeric(10,4) AS fee_ratio,
    mat_fee.currency,
    mat_fee.price_unit,
    mat_fee.price_floating,
    (mat_fee.settlement_rise_ratio * 100::numeric)::numeric(10,4) AS settlement_rise_ratio,
    mat_fee.fixed_rise_value,
    mat_fee.rise_currency,
    mat_fee.rise_unit,
    NULL::numeric(10,4) AS recycle_pct
   FROM mat_fee
  WHERE mat_fee.fee_type::text = 'INCOMING_FIXED'::text AND mat_fee.is_current = true
UNION ALL
 SELECT 'INCOMING_OTHER'::character varying AS source_type,
    mat_fee.hf_part_no,
    mat_fee.seq_no,
    mat_fee.dim_input_material_no AS input_material_no,
    mat_fee.dim_input_material_name AS input_material_name,
    NULL::character varying(64) AS output_material_type,
    NULL::numeric(18,4) AS gross_qty,
    NULL::numeric(18,4) AS net_qty,
    NULL::character varying(16) AS weight_unit,
    NULL::numeric(10,4) AS loss_rate,
    NULL::numeric(10,4) AS defect_rate,
    mat_fee.dim_sub_seq_no AS sub_seq_no,
    mat_fee.dim_element_name AS element_name,
    mat_fee.fee_value,
    (mat_fee.fee_ratio * 100::numeric)::numeric(10,4) AS fee_ratio,
    mat_fee.currency,
    mat_fee.price_unit,
    NULL::boolean AS price_floating,
    NULL::numeric(10,4) AS settlement_rise_ratio,
    NULL::numeric(18,4) AS fixed_rise_value,
    NULL::character varying(8) AS rise_currency,
    NULL::character varying(16) AS rise_unit,
    NULL::numeric(10,4) AS recycle_pct
   FROM mat_fee
  WHERE mat_fee.fee_type::text = 'INCOMING_OTHER'::text AND mat_fee.is_current = true
UNION ALL
 SELECT 'MATERIAL_RECYCLE'::character varying AS source_type,
    mat_fee.hf_part_no,
    mat_fee.seq_no,
    mat_fee.dim_input_material_no AS input_material_no,
    mat_fee.dim_input_material_name AS input_material_name,
    NULL::character varying(64) AS output_material_type,
    NULL::numeric(18,4) AS gross_qty,
    NULL::numeric(18,4) AS net_qty,
    NULL::character varying(16) AS weight_unit,
    NULL::numeric(10,4) AS loss_rate,
    NULL::numeric(10,4) AS defect_rate,
    NULL::integer AS sub_seq_no,
    NULL::character varying(128) AS element_name,
    NULL::numeric(18,4) AS fee_value,
    NULL::numeric(10,4) AS fee_ratio,
    NULL::character varying(8) AS currency,
    NULL::character varying(16) AS price_unit,
    NULL::boolean AS price_floating,
    NULL::numeric(10,4) AS settlement_rise_ratio,
    NULL::numeric(18,4) AS fixed_rise_value,
    NULL::character varying(8) AS rise_currency,
    NULL::character varying(16) AS rise_unit,
    (mat_fee.fee_ratio * 100::numeric)::numeric(10,4) AS recycle_pct
   FROM mat_fee
  WHERE mat_fee.fee_type::text = 'MATERIAL_RECYCLE'::text AND mat_fee.is_current = true;
COMMENT ON VIEW public.v_q_incoming_merged IS 'V128+V133: 来料合并视图 -- 百分比列已 x100 显示（loss_rate/defect_rate/fee_ratio/settlement_rise_ratio/recycle_pct）';

-- ----------------------------------------------------------------
-- VIEW: v_q_part_info_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_q_part_info_merged CASCADE;
CREATE VIEW public.v_q_part_info_merged AS
SELECT 'PART'::character varying AS source_type,
    m.hf_part_no,
    m.customer_part_name,
    m.customer_product_no,
    m.customer_drawing_no,
    m.payment_method,
    m.base_currency,
    m.quote_currency,
    er.rate AS exchange_rate,
    p.unit_weight,
    p.weight_unit
   FROM mat_customer_part_mapping m
     LEFT JOIN mat_part p ON p.part_no::text = m.hf_part_no::text
     LEFT JOIN exchange_rate er ON er.customer_id = m.customer_id AND er.from_currency::text = m.base_currency::text AND er.to_currency::text = m.quote_currency::text AND er.is_current = true;
COMMENT ON VIEW public.v_q_part_info_merged IS 'V128: 料件合并视图 — mat_customer_part_mapping LEFT JOIN mat_part + exchange_rate(当前汇率)';

-- ----------------------------------------------------------------
-- VIEW: v_q_part_plating_scheme
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_q_part_plating_scheme CASCADE;
CREATE VIEW public.v_q_part_plating_scheme AS
SELECT pf.hf_part_no,
    pf.customer_id,
    pp.plan_code,
    pp.version,
    pp.seq_no,
    pp.plating_element,
    pp.plating_area,
    pp.coating_thickness,
    pp.plating_requirement
   FROM mat_plating_fee pf
     JOIN mat_plating_plan pp ON pf.plating_plan_code::text = pp.plan_code::text AND pf.plan_version::text = pp.version::text
  WHERE pf.is_current = true;
COMMENT ON VIEW public.v_q_part_plating_scheme IS 'V126: 报价侧 — mat_plating_fee × mat_plating_plan, 让 COMP-Q-PLATING-SCHEME 通过 partNo 取多行';

-- ----------------------------------------------------------------
-- VIEW: v_q_plating_merged
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS public.v_q_plating_merged CASCADE;
CREATE VIEW public.v_q_plating_merged AS
SELECT 'FEE'::character varying AS source_type,
    f.hf_part_no,
    f.plating_plan_code AS plan_code,
    f.plan_version,
    p.seq_no,
    p.plating_element,
    p.plating_area,
    p.coating_thickness,
    p.plating_requirement,
    f.plating_process_fee,
    f.plating_material_fee,
    f.currency,
    f.price_unit,
    (f.defect_rate * 100::numeric)::numeric(10,4) AS defect_rate
   FROM mat_plating_fee f
     LEFT JOIN mat_plating_plan p ON p.plan_code::text = f.plating_plan_code::text AND p.version::text = f.plan_version::text
  WHERE f.is_current = true;
COMMENT ON VIEW public.v_q_plating_merged IS 'V128+V133+V137+V141: 电镀费用 LEFT JOIN 电镀方案 (by plan_code+version) - 让方案多元素行附加到 FEE 行展示, defect_rate ×100';
