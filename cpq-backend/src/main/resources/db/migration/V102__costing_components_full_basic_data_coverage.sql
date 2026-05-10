-- V102: 把"核价模板组件"目录下所有"物理表已存储"的字段统一切到 BASIC_DATA
--
-- V99 配 BASIC_DATA 时, 有 4 个字段因跨表列名不匹配只能留 INPUT_NUMBER/INPUT_TEXT,
-- V102 通过创建 3 个"列名对齐视图"补齐, 让 ImplicitJoinRewriter 隐式 JOIN 可达。
--
-- 场景对照:
--   ① COMP-V4-ELEMENT-BOM."元素单价(CNY/KG)"
--      driver: mat_bom[bom_type='ELEMENT']  →  driver row has element_name='Cu'
--      源表:   v_costing_element_price.element_code   (列名 element_code, 与 driver 不同)
--      解法:   建 v_part_element_price (element_code AS element_name) → 同名列, 自动 JOIN
--
--   ② COMP-V4-RAW-BOM."单价(CNY/KG)"
--      driver: costing_part_material_bom    →  driver row has input_material_no='2000140001'
--      源表:   v_costing_material_price.material_no
--      解法:   建 v_part_material_price (material_no AS input_material_no)
--
--   ③ COMP-V4-PLATING-SCHEME (6/7 字段无路径)
--      字段全在 plating_plan 表 (plan_code/version/seq_no/plating_element/plating_area/coating_thickness)
--      但 plating_plan 没有 hf_part_no 列, 跨 part 共享 → 隐式 JOIN 无法收窄
--      解法:   建 v_part_plating_scheme = plating_fee JOIN plating_plan ON plan_code+version,
--              输出 hf_part_no + 方案明细全字段 → driver/字段都指它, partNo 自动注入
--      注: "密度(g/cm³)"是元素物理常数 (Cu=8.96, Au=19.3 等), DB 无列存, 保留 INPUT_NUMBER
--
-- 已确认无须改的字段:
--   - COMP-V4-EXCHANGE-RATE."基础货币"/"核价货币" = FIXED_VALUE(CNY/USD), 是常量标签不是物理列

-- ============================================================
-- 1. 列名对齐视图 (3 个)
-- ============================================================

CREATE OR REPLACE VIEW v_part_element_price AS
SELECT
    element_code        AS element_name,   -- 别名: 与 mat_bom.element_name 同名 → 隐式 JOIN
    element_code,                          -- 保留原列以兼容旧路径
    costing_price,
    market_ref_price,
    currency,
    unit,
    discount_rate,
    sort_order
FROM v_costing_element_price;

COMMENT ON VIEW v_part_element_price IS
'V102: v_costing_element_price 别名视图, element_code → element_name 让 ImplicitJoinRewriter 与 mat_bom 行隐式 JOIN';

CREATE OR REPLACE VIEW v_part_material_price AS
SELECT
    material_no         AS input_material_no,  -- 别名: 与 costing_part_material_bom.input_material_no 同名
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

COMMENT ON VIEW v_part_material_price IS
'V102: v_costing_material_price 别名视图, material_no → input_material_no 让 ImplicitJoinRewriter 与 costing_part_material_bom 行隐式 JOIN';

CREATE OR REPLACE VIEW v_part_plating_scheme AS
SELECT
    pf.hf_part_no,                  -- 关键: 暴露 part 维度, ImplicitJoinRewriter 自动按 partNo 收窄
    pf.customer_id,
    pp.plan_code,
    pp.version,
    pp.seq_no,
    pp.plating_element,
    pp.plating_area,
    pp.coating_thickness,
    pp.plating_requirement
FROM plating_fee pf
JOIN plating_plan pp
  ON pf.plating_plan_code = pp.plan_code
 AND pf.plan_version       = pp.version
WHERE pf.is_current = true;

COMMENT ON VIEW v_part_plating_scheme IS
'V102: plating_fee × plating_plan 联合视图, 让 COMP-V4-PLATING-SCHEME 通过 partNo 取到对应方案的多行明细';

-- ============================================================
-- 2. 组件字段补 BASIC_DATA 路径
-- ============================================================

-- ── ① COMP-V4-ELEMENT-BOM 元素单价 ──
UPDATE component
SET fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].seq_no"},
        {"name":"元素代码","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].element_name"},
        {"name":"组成含量(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].composition_pct","notes":"DB 存百分比 (75 = 75%)"},
        {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].input_material_no"},
        {"name":"元素单价(CNY/KG)","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_part_element_price.costing_price","notes":"V102: 通过 v_part_element_price (element_code→element_name 别名) 与 mat_bom 行隐式 JOIN"},
        {"name":"损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].loss_rate","notes":"DB 存小数 (0.05 = 5%)"},
        {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE code = 'COMP-V4-ELEMENT-BOM';

-- ── ② COMP-V4-RAW-BOM 单价 ──
UPDATE component
SET fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.seq_no"},
        {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.input_material_no"},
        {"name":"组成用量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.input_qty"},
        {"name":"底数","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.output_qty"},
        {"name":"来料损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.loss_rate","notes":"DB 存小数"},
        {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.output_loss_rate","notes":"DB 存小数"},
        {"name":"单价(CNY/KG)","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_part_material_price.costing_price","notes":"V102: 通过 v_part_material_price (material_no→input_material_no 别名) 与 BOM 行隐式 JOIN"},
        {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE code = 'COMP-V4-RAW-BOM';

-- ── ③ COMP-V4-PLATING-SCHEME 整体切到 v_part_plating_scheme ──
UPDATE component
SET data_driver_path = 'v_part_plating_scheme',
    fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.seq_no"},
        {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.plan_code"},
        {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.version"},
        {"name":"电镀元素","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.plating_element"},
        {"name":"电镀面积(cm²)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.plating_area"},
        {"name":"镀层厚度(μm)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_part_plating_scheme.coating_thickness"},
        {"name":"密度(g/cm³)","field_type":"INPUT_NUMBER","content":"","is_amount":false,"is_subtotal":false,"notes":"元素物理常数 (Cu=8.96/Au=19.3 等), DB 无列存, 保留手填"}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE code = 'COMP-V4-PLATING-SCHEME';

-- ============================================================
-- 3. 同步重建模板 components_snapshot
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
    RAISE NOTICE 'V102: 已重建模板 components_snapshot';
END $$;
