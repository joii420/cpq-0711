-- V103: 电镀成本完整公式链 (各元素电镀重量 → 行电镀材料费 → 电镀材料费 → 电镀成本)
--
-- 用户需求:
--   按"核价系统计算公式和取值（示例）.xlsx"的 A88/A89/A90 公式定义:
--     ① 各元素电镀重量(KG) = 电镀面积 × 镀层厚度 × 密度 × 单重 ÷ 100000
--     ② 行电镀材料费       = 各元素电镀重量 × 元素单价
--     ③ 电镀材料费         = ∑(行电镀材料费)               -- 跨电镀方案行汇总
--     ④ 电镀成本           = (电镀加工费 + 电镀材料费) × (1 + 不良率)
--   公式中的 面积/厚度/密度 来自电镀方案 (plating_plan), 按 plan_code+version 匹配。
--
-- 架构选型:
--   - 在 PLATING-SCHEME 组件做"行级"两个 FORMULA, 让用户在组件管理可视化看到公式
--   - PLATING-SCHEME.行电镀材料费 标记 is_subtotal=true → 前端 reduce(SUM) 跨行汇总
--     (frontend/.../ReadonlyProductCard.tsx:201 已有此机制)
--   - PLATING-COST.电镀材料费 改为 FORMULA, 用 component_subtotal token 引用 PLATING-SCHEME
--   - 注意: computeFormula 不支持 FORMULA→FORMULA 链式 (FORMULA 字段值不放进 fieldValues),
--          所以 行电镀材料费 公式需"展开内联", 不能直接 ref 各元素电镀重量
--   - 密度: 在 plating_plan 不存; 引入 element_density 常数表 (Cu/Ni/Au/... 物理常数),
--          v_part_plating_scheme LEFT JOIN 暴露给组件层
--   - 单重: 取 costing_part_weight.weight_g_per_pcs, 通过 ImplicitJoinRewriter 按 partNo 自动注入

-- ============================================================
-- 1. 元素密度参考表 (g/cm³ 物理常数)
-- ============================================================
CREATE TABLE IF NOT EXISTS element_density (
    element_name VARCHAR(20) PRIMARY KEY,
    density      NUMERIC(8,4) NOT NULL,
    notes        VARCHAR(200)
);

INSERT INTO element_density(element_name, density, notes) VALUES
    ('Ag', 10.4900, '银'),
    ('Cu',  8.9600, '铜'),
    ('Ni',  8.9020, '镍'),
    ('Au', 19.3200, '金'),
    ('Sn',  7.3100, '锡'),
    ('Pd', 12.0200, '钯'),
    ('Pt', 21.4500, '铂'),
    ('Zn',  7.1400, '锌'),
    ('Cr',  7.1900, '铬'),
    ('Fe',  7.8740, '铁')
ON CONFLICT (element_name) DO UPDATE SET
    density = EXCLUDED.density,
    notes   = EXCLUDED.notes;

COMMENT ON TABLE element_density IS
'V103: 元素物理密度参考表 (g/cm³). 用于电镀重量公式 v=A×t×ρ; 与电镀方案无关, 按元素查';

-- ============================================================
-- 2. 重建 v_part_plating_scheme: + element_name(plating_element 别名) + density
--    element_name 别名让 ImplicitJoinRewriter 与 v_part_element_price 隐式 JOIN
-- ============================================================
DROP VIEW IF EXISTS v_part_plating_scheme CASCADE;

CREATE VIEW v_part_plating_scheme AS
SELECT
    pf.hf_part_no,
    pf.customer_id,
    pp.plan_code,
    pp.version,
    pp.seq_no,
    pp.plating_element,
    pp.plating_element AS element_name,   -- V103: 与 v_part_element_price.element_name 同名 → 隐式 JOIN
    pp.plating_area,
    pp.coating_thickness,
    pp.plating_requirement,
    ed.density                            -- V103: 来自 element_density 物理常数表
FROM plating_fee pf
JOIN plating_plan pp
  ON pf.plating_plan_code = pp.plan_code
 AND pf.plan_version       = pp.version
LEFT JOIN element_density ed
  ON ed.element_name = pp.plating_element
WHERE pf.is_current = true;

COMMENT ON VIEW v_part_plating_scheme IS
'V103: plating_fee × plating_plan + element_density 联合视图. 暴露 hf_part_no/element_name/density 让组件层公式直接消费';

-- ============================================================
-- 3. COMP-V4-PLATING-SCHEME: 加 单重/元素单价 + 2 个行级 FORMULA
-- ============================================================
UPDATE component
SET fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.seq_no"},
        {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.plan_code"},
        {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.version"},
        {"name":"电镀元素","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.plating_element"},
        {"name":"电镀面积(cm²)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.plating_area"},
        {"name":"镀层厚度(μm)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.coating_thickness"},
        {"name":"密度(g/cm³)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.density","notes":"V103: 来自 element_density 元素物理常数 (按 plating_element 匹配)"},
        {"name":"单重(g/pcs)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_weight.weight_g_per_pcs","notes":"V103: 隐式 JOIN partNo, 当前料号单重"},
        {"name":"元素单价(CNY/KG)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_element_price.costing_price","notes":"V103: 隐式 JOIN element_name → 元素核价单价"},
        {"name":"各元素电镀重量(KG)","field_type":"FORMULA","content":"","is_amount":false,"is_subtotal":false,"notes":"=电镀面积 × 镀层厚度 × 密度 × 单重 ÷ 100000 (展示用; A88 公式)"},
        {"name":"行电镀材料费","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"=电镀面积 × 镀层厚度 × 密度 × 单重 ÷ 100000 × 元素单价 (内联展开; is_subtotal=true 让 PLATING-COST 跨行 SUM)"}
    ]$JSON$::jsonb,
    formulas = $JSON$[
        {"name":"各元素电镀重量(KG)","result_type":"AMOUNT","expression":[
            {"type":"field","label":"电镀面积(cm²)","value":"电镀面积(cm²)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"镀层厚度(μm)","value":"镀层厚度(μm)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"密度(g/cm³)","value":"密度(g/cm³)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"单重(g/pcs)","value":"单重(g/pcs)"},
            {"type":"operator","label":"÷","value":"/"},
            {"type":"number","label":"100000","value":"100000"}
        ]},
        {"name":"行电镀材料费","result_type":"AMOUNT","expression":[
            {"type":"field","label":"电镀面积(cm²)","value":"电镀面积(cm²)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"镀层厚度(μm)","value":"镀层厚度(μm)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"密度(g/cm³)","value":"密度(g/cm³)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"单重(g/pcs)","value":"单重(g/pcs)"},
            {"type":"operator","label":"÷","value":"/"},
            {"type":"number","label":"100000","value":"100000"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"元素单价(CNY/KG)","value":"元素单价(CNY/KG)"}
        ]}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE code = 'COMP-V4-PLATING-SCHEME';

-- ============================================================
-- 4. COMP-V4-PLATING-COST: 电镀材料费 BASIC_DATA → FORMULA (引用 PLATING-SCHEME 小计)
--    保持其余字段不变, 重写公式数组覆盖 电镀材料费 + 电镀成本
-- ============================================================
UPDATE component
SET fields = $JSON$[
        {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.plating_plan_code"},
        {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.plan_version"},
        {"name":"电镀加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"plating_fee.plating_process_fee"},
        {"name":"电镀材料费","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":false,"notes":"V103: =∑(PLATING-SCHEME.行电镀材料费); 不再读 plating_fee.plating_material_fee"},
        {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.defect_rate","notes":"DB 存小数 (0.005 = 0.5%)"},
        {"name":"电镀成本","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"=(电镀加工费 + 电镀材料费) × (1 + 不良率)"}
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
        ]}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE code = 'COMP-V4-PLATING-COST';

-- ============================================================
-- 5. 同步重建模板 components_snapshot
-- ============================================================
DO $$
DECLARE v_template_id UUID;
BEGIN
    SELECT id INTO v_template_id FROM template
    WHERE name = '核价-完整公式版-组件版' AND template_kind = 'COSTING';
    IF v_template_id IS NULL THEN RETURN; END IF;
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
    RAISE NOTICE 'V103: 已重建模板 components_snapshot';
END $$;
