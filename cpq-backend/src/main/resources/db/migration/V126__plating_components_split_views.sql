-- V126: 电镀组件 + 视图按双侧分流 — 报价组件指 mat_plating_*, 核价组件指 costing_part_plating*
--
-- 配套 V125: V125 建好了 mat_plating_plan / mat_plating_fee / costing_part_plating_fee
--           且历史数据已双写迁移到位。本迁移把:
--   1. 视图 v_part_plating_scheme — 改为读 costing_part_plating_fee × costing_part_plating (核价侧)
--   2. 视图 v_q_part_plating_scheme — 新建,读 mat_plating_fee × mat_plating_plan (报价侧)
--   3. COMP-V4-PLATING-SCHEME / COMP-V4-PLATING-COST — fields 路径切到核价侧表/视图
--   4. COMP-Q-PLATING-SCHEME  / COMP-Q-PLATING-COST  — fields 路径切到报价侧表/视图
--   5. 重建持有这些组件的 PUBLISHED 模板的 components_snapshot
--
-- 注意: 字段名/列名 (项次/方案编号/版本/电镀加工费 等) 保持不变,只换底层物理表/视图.

-- ════════════════════════════════════════════════════════════════════════════
-- A. 视图 — 报价侧新建, 核价侧重建
-- ════════════════════════════════════════════════════════════════════════════

-- A.1 报价侧 — 新建 v_q_part_plating_scheme = mat_plating_fee × mat_plating_plan
CREATE OR REPLACE VIEW v_q_part_plating_scheme AS
SELECT
    pf.hf_part_no,
    pf.customer_id,
    pp.plan_code,
    pp.version,
    pp.seq_no,
    pp.plating_element,
    pp.plating_area,
    pp.coating_thickness,
    pp.plating_requirement
FROM mat_plating_fee pf
JOIN mat_plating_plan pp
  ON pf.plating_plan_code = pp.plan_code
 AND pf.plan_version       = pp.version
WHERE pf.is_current = true;

COMMENT ON VIEW v_q_part_plating_scheme IS
    'V126: 报价侧 — mat_plating_fee × mat_plating_plan, 让 COMP-Q-PLATING-SCHEME 通过 partNo 取多行';

-- A.2 核价侧 — 重建 v_part_plating_scheme = costing_part_plating_fee × costing_part_plating
--    (V102 原始版本是 plating_fee × plating_plan, 现切到核价侧表)
DROP VIEW IF EXISTS v_part_plating_scheme;
CREATE VIEW v_part_plating_scheme AS
SELECT
    pf.hf_part_no,
    NULL::UUID AS customer_id,         -- 核价侧不带 customer 维度, 暴露列以兼容 ImplicitJoinRewriter
    cpp.plating_no    AS plan_code,
    cpp.version_number AS version,
    cpp.seq_no,
    cpp.element_attr  AS plating_element,
    cpp.plating_area_cm2  AS plating_area,
    cpp.layer_thickness_um AS coating_thickness,
    cpp.requirement   AS plating_requirement
FROM costing_part_plating_fee pf
JOIN costing_part_plating cpp
  ON pf.plating_plan_code = cpp.plating_no
 AND pf.plan_version       = cpp.version_number
WHERE pf.is_active = true;

COMMENT ON VIEW v_part_plating_scheme IS
    'V126: 核价侧 — costing_part_plating_fee × costing_part_plating, 让 COMP-V4-PLATING-SCHEME 通过 partNo 取多行';

-- ════════════════════════════════════════════════════════════════════════════
-- B. 报价侧组件 (COMP-Q-*) — 字段切到报价侧表/视图
-- ════════════════════════════════════════════════════════════════════════════

-- B.1 COMP-Q-PLATING-SCHEME → v_q_part_plating_scheme
UPDATE component
   SET data_driver_path = 'v_q_part_plating_scheme',
       fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_plating_scheme.seq_no"},
        {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_plating_scheme.plan_code"},
        {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_plating_scheme.version"},
        {"name":"电镀元素","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_plating_scheme.plating_element"},
        {"name":"电镀面积(cm²)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_plating_scheme.plating_area"},
        {"name":"镀层厚度(μm)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_plating_scheme.coating_thickness"},
        {"name":"密度(g/cm³)","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false,"notes":"V126: 元素物理常数, 手填"},
        {"name":"单重(g/pcs)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_part.unit_weight","notes":"V126: 报价侧单重 mat_part.unit_weight"},
        {"name":"元素单价(CNY/KG)","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_costing_element_price.costing_price","global_variable_code":"ELEM_PRICE"},
        {"name":"各元素电镀重量(KG)","field_type":"FORMULA","content":"","is_amount":false,"is_subtotal":false},
        {"name":"行电镀材料费","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true}
    ]$JSON$::jsonb,
    formulas = $JSON$[
        {"name":"各元素电镀重量(KG)","result_type":"VALUE","expression":[
            {"type":"field","label":"电镀面积(cm²)","value":"电镀面积(cm²)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"镀层厚度(μm)","value":"镀层厚度(μm)"},
            {"type":"operator","label":"÷","value":"/"},
            {"type":"number","label":"10000","value":"10000"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"密度(g/cm³)","value":"密度(g/cm³)"},
            {"type":"operator","label":"÷","value":"/"},
            {"type":"number","label":"1000","value":"1000"}
        ]},
        {"name":"行电镀材料费","result_type":"AMOUNT","expression":[
            {"type":"field","label":"各元素电镀重量(KG)","value":"各元素电镀重量(KG)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"元素单价(CNY/KG)","value":"元素单价(CNY/KG)"}
        ]}
    ]$JSON$::jsonb,
    column_count = 11,
    updated_at = now()
 WHERE code = 'COMP-Q-PLATING-SCHEME';

-- B.2 COMP-Q-PLATING-COST → mat_plating_fee
UPDATE component
   SET data_driver_path = NULL,   -- 单行 (一对 plan/version)
       fields = $JSON$[
        {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_plating_fee.plating_plan_code"},
        {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_plating_fee.plan_version"},
        {"name":"电镀加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"mat_plating_fee.plating_process_fee"},
        {"name":"电镀材料费","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":false,"notes":"V126: =∑(电镀方案·行电镀材料费)"},
        {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_plating_fee.defect_rate","notes":"DB 存小数 (0.005=0.5%)"},
        {"name":"电镀成本","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"=(电镀加工费+电镀材料费)×(1+不良率)"},
        {"name":"电镀成本(USD)","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":false,"notes":"=电镀成本×EXCHANGE_RATE[CNY:USD]"}
    ]$JSON$::jsonb,
    formulas = $JSON$[
        {"name":"电镀材料费","result_type":"AMOUNT","expression":[
            {"type":"component_subtotal","label":"电镀方案·行电镀材料费","value":"行电镀材料费","tab_name":"电镀方案","component_code":"COMP-Q-PLATING-SCHEME"}
        ]},
        {"name":"电镀成本","result_type":"AMOUNT","expression":[
            {"type":"bracket_open","label":"(","value":"("},
            {"type":"field","label":"电镀加工费","value":"电镀加工费"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"field","label":"电镀材料费","value":"电镀材料费"},
            {"type":"bracket_close","label":")","value":")"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"bracket_open","label":"(","value":"("},
            {"type":"number","label":"1","value":"1"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"field","label":"不良率(%)","value":"不良率(%)"},
            {"type":"bracket_close","label":")","value":")"}
        ]},
        {"name":"电镀成本(USD)","result_type":"AMOUNT","expression":[
            {"type":"field","label":"电镀成本","value":"电镀成本"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"global_variable","label":"汇率[CNY:USD]","code":"EXCHANGE_RATE","key_values":{"from_currency":"CNY","to_currency":"USD"},"path":"v_costing_exchange_rate[from_currency='CNY' AND to_currency='USD'].costing_rate"}
        ]}
    ]$JSON$::jsonb,
    column_count = 7,
    updated_at = now()
 WHERE code = 'COMP-Q-PLATING-COST';

-- ════════════════════════════════════════════════════════════════════════════
-- C. 核价侧组件 (COMP-V4-*) — 字段切到核价侧表/视图
-- ════════════════════════════════════════════════════════════════════════════

-- C.1 COMP-V4-PLATING-SCHEME → v_part_plating_scheme (V126 重建后指核价侧)
UPDATE component
   SET data_driver_path = 'v_part_plating_scheme',
       fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.seq_no"},
        {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.plan_code"},
        {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.version"},
        {"name":"电镀元素","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.plating_element"},
        {"name":"电镀面积(cm²)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.plating_area"},
        {"name":"镀层厚度(μm)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.coating_thickness"},
        {"name":"密度(g/cm³)","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false,"notes":"V126: 元素物理常数, 手填"},
        {"name":"单重(g/pcs)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_weight.weight_g_per_pcs","notes":"V126: 核价侧单重"},
        {"name":"元素单价(CNY/KG)","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_costing_element_price.costing_price","global_variable_code":"ELEM_PRICE"},
        {"name":"各元素电镀重量(KG)","field_type":"FORMULA","content":"","is_amount":false,"is_subtotal":false},
        {"name":"行电镀材料费","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true}
    ]$JSON$::jsonb,
    formulas = $JSON$[
        {"name":"各元素电镀重量(KG)","result_type":"VALUE","expression":[
            {"type":"field","label":"电镀面积(cm²)","value":"电镀面积(cm²)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"镀层厚度(μm)","value":"镀层厚度(μm)"},
            {"type":"operator","label":"÷","value":"/"},
            {"type":"number","label":"10000","value":"10000"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"密度(g/cm³)","value":"密度(g/cm³)"},
            {"type":"operator","label":"÷","value":"/"},
            {"type":"number","label":"1000","value":"1000"}
        ]},
        {"name":"行电镀材料费","result_type":"AMOUNT","expression":[
            {"type":"field","label":"各元素电镀重量(KG)","value":"各元素电镀重量(KG)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"元素单价(CNY/KG)","value":"元素单价(CNY/KG)"}
        ]}
    ]$JSON$::jsonb,
    column_count = 11,
    updated_at = now()
 WHERE code = 'COMP-V4-PLATING-SCHEME';

-- C.2 COMP-V4-PLATING-COST → costing_part_plating_fee
UPDATE component
   SET data_driver_path = NULL,
       fields = $JSON$[
        {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_plating_fee.plating_plan_code"},
        {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_plating_fee.plan_version"},
        {"name":"电镀加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"costing_part_plating_fee.plating_process_fee"},
        {"name":"电镀材料费","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":false,"notes":"V126: =∑(电镀方案·行电镀材料费)"},
        {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_plating_fee.defect_rate","notes":"DB 存小数 (0.005=0.5%)"},
        {"name":"电镀成本","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"=(电镀加工费+电镀材料费)×(1+不良率)"},
        {"name":"电镀成本(USD)","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":false,"notes":"=电镀成本×EXCHANGE_RATE[CNY:USD]"}
    ]$JSON$::jsonb,
    formulas = $JSON$[
        {"name":"电镀材料费","result_type":"AMOUNT","expression":[
            {"type":"component_subtotal","label":"电镀方案·行电镀材料费","value":"行电镀材料费","tab_name":"电镀方案","component_code":"COMP-V4-PLATING-SCHEME"}
        ]},
        {"name":"电镀成本","result_type":"AMOUNT","expression":[
            {"type":"bracket_open","label":"(","value":"("},
            {"type":"field","label":"电镀加工费","value":"电镀加工费"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"field","label":"电镀材料费","value":"电镀材料费"},
            {"type":"bracket_close","label":")","value":")"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"bracket_open","label":"(","value":"("},
            {"type":"number","label":"1","value":"1"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"field","label":"不良率(%)","value":"不良率(%)"},
            {"type":"bracket_close","label":")","value":")"}
        ]},
        {"name":"电镀成本(USD)","result_type":"AMOUNT","expression":[
            {"type":"field","label":"电镀成本","value":"电镀成本"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"global_variable","label":"汇率[CNY:USD]","code":"EXCHANGE_RATE","key_values":{"from_currency":"CNY","to_currency":"USD"},"path":"v_costing_exchange_rate[from_currency='CNY' AND to_currency='USD'].costing_rate"}
        ]}
    ]$JSON$::jsonb,
    column_count = 7,
    updated_at = now()
 WHERE code = 'COMP-V4-PLATING-COST';

-- ════════════════════════════════════════════════════════════════════════════
-- D. 重建持有这 4 个组件的 PUBLISHED 模板的 components_snapshot
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE v_tpl RECORD;
BEGIN
    FOR v_tpl IN
        SELECT DISTINCT t.id
          FROM template t
          JOIN template_component tc ON tc.template_id = t.id
          JOIN component c ON c.id = tc.component_id
         WHERE c.code IN ('COMP-Q-PLATING-SCHEME','COMP-Q-PLATING-COST',
                          'COMP-V4-PLATING-SCHEME','COMP-V4-PLATING-COST')
    LOOP
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
                 WHERE tc.template_id = v_tpl.id
            ),
            updated_at = now()
         WHERE id = v_tpl.id;
        RAISE NOTICE 'V126: 重建 % snapshot', v_tpl.id;
    END LOOP;
END $$;
