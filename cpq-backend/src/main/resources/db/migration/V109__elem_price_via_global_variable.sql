-- V109: 元素/材料单价字段路径切到全局变量 source_view (ELEM_PRICE / MAT_PRICE)
--
-- 问题:
--   V102 为了让 ImplicitJoinRewriter 跨表隐式 JOIN, 创建了 v_part_element_price / v_part_material_price
--   别名视图 (element_code AS element_name 等). 字段 basic_data_path 写的是别名视图名,
--   语义上脱离全局变量配置 — 用户看到 "v_part_element_price.costing_price" 不知道这就是 ELEM_PRICE.
--
-- 修复 (KISS):
--   1. 给 v_costing_element_price (ELEM_PRICE source_view) 直接加 element_name 别名列
--      → ImplicitJoinRewriter 看到 driver row 的 element_name 时, 直接在 v_costing_element_price 注入,
--        不再需要中间别名视图
--   2. 同理 v_costing_material_price 加 input_material_no 别名列 (跟 costing_part_material_bom.input_material_no 对齐)
--   3. 把 PLATING-SCHEME / ELEMENT-BOM / RAW-BOM 三处的「单价」字段:
--      - basic_data_path 切到 v_costing_*_price 直接路径
--      - 字段加 global_variable_code 元数据 → UI 显示徽章 + 链路明确
--   4. v_part_element_price / v_part_material_price 视图保留 (V102/V108 已用), 标 deprecated
--
-- 兼容性:
--   - CREATE OR REPLACE VIEW 加新列, 不破坏现有 SELECT 列名 (Specific column queries 忽略新列)
--   - CostingSummaryService.loadElementPrices SELECT element_code, costing_price 仍工作
--   - 全局变量配置页 listKeys 按 def.keyColumns=["element_code"] 不受影响

-- ═════════════════════════════════════════════════════════════════════
-- 1. v_costing_element_price 加 element_name 别名列
--    (CREATE OR REPLACE 不需先 DROP, 列结构兼容性已分析)
-- ═════════════════════════════════════════════════════════════════════
-- 注: PostgreSQL CREATE OR REPLACE VIEW 不允许重排列, 别名列只能加在末尾.
-- 但 v_part_element_price 等下游视图引用 v_costing_element_price 的列结构, 必须 DROP CASCADE 重建.
DROP VIEW IF EXISTS v_part_element_price;
DROP VIEW IF EXISTS v_costing_element_price CASCADE;

CREATE VIEW v_costing_element_price AS
SELECT
    e.id,
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
    e.element_code AS element_name    -- V109: 别名列(放末尾), 让 ImplicitJoinRewriter 与 mat_bom.element_name 隐式 JOIN
FROM costing_element_price e
JOIN costing_price_version v ON v.id = e.version_id
WHERE v.version_kind = 'ELEMENT'
  AND v.status       = 'PUBLISHED'
  AND v.is_default   = true;

COMMENT ON COLUMN v_costing_element_price.element_name IS
    'V109 别名: = element_code. 用于 ImplicitJoinRewriter 与 mat_bom (driver 行有 element_name 列) 隐式 JOIN';

-- 重建 v_part_element_price (V102 deprecated 但保留兼容)
CREATE VIEW v_part_element_price AS
SELECT
    element_code AS element_name,
    element_code,
    costing_price,
    market_ref_price,
    currency,
    unit,
    discount_rate,
    sort_order
FROM v_costing_element_price;
COMMENT ON VIEW v_part_element_price IS
    'V109 起 deprecated, 字段路径已直接切到 v_costing_element_price (= ELEM_PRICE 全局变量); 保留作向后兼容';

-- ═════════════════════════════════════════════════════════════════════
-- 2. v_costing_material_price 加 input_material_no 别名列
-- ═════════════════════════════════════════════════════════════════════
-- 同样: 重建 v_costing_material_price + v_part_material_price 以加 input_material_no 别名
DROP VIEW IF EXISTS v_part_material_price;
DROP VIEW IF EXISTS v_costing_material_price CASCADE;

CREATE VIEW v_costing_material_price AS
SELECT
    m.id,
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
    m.material_no AS input_material_no   -- V109: 别名(末尾), 与 costing_part_material_bom 隐式 JOIN
FROM costing_material_price m
JOIN costing_price_version v ON v.id = m.version_id
WHERE v.version_kind = 'MATERIAL'
  AND v.status       = 'PUBLISHED'
  AND v.is_default   = true;

COMMENT ON COLUMN v_costing_material_price.input_material_no IS
    'V109 别名: = material_no. 用于 ImplicitJoinRewriter 与 costing_part_material_bom 隐式 JOIN';

CREATE VIEW v_part_material_price AS
SELECT
    material_no AS input_material_no,
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
    'V109 起 deprecated, 字段路径已直接切到 v_costing_material_price (= MAT_PRICE 全局变量); 保留作向后兼容';

-- ═════════════════════════════════════════════════════════════════════
-- 3. PLATING-SCHEME 元素单价 路径切到 ELEM_PRICE 全局变量直接路径
--    + global_variable_code 元数据
-- ═════════════════════════════════════════════════════════════════════
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
        {"name":"元素单价(CNY/KG)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_costing_element_price.costing_price","global_variable_code":"ELEM_PRICE","notes":"V109: 取自全局变量 ELEM_PRICE; ImplicitJoinRewriter 按 element_name 自动注入"},
        {"name":"各元素电镀重量(KG)","field_type":"FORMULA","content":"","is_amount":false,"is_subtotal":false,"notes":"=电镀面积 × 镀层厚度 × 密度 × 单重 ÷ 100000 (展示用; A88 公式)"},
        {"name":"行电镀材料费","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"=电镀面积 × 镀层厚度 × 密度 × 单重 ÷ 100000 × 元素单价 (内联展开; is_subtotal=true 让 PLATING-COST 跨行 SUM)"}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE code = 'COMP-V4-PLATING-SCHEME';

-- ═════════════════════════════════════════════════════════════════════
-- 4. ELEMENT-BOM 元素单价 路径切到 ELEM_PRICE
-- ═════════════════════════════════════════════════════════════════════
UPDATE component
SET fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].seq_no"},
        {"name":"元素代码","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].element_name"},
        {"name":"组成含量(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].composition_pct","notes":"DB 存百分比 (75 = 75%)"},
        {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].input_material_no"},
        {"name":"元素单价(CNY/KG)","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_costing_element_price.costing_price","global_variable_code":"ELEM_PRICE","notes":"V109: 取自全局变量 ELEM_PRICE; ImplicitJoinRewriter 按 element_name 自动注入"},
        {"name":"损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].loss_rate","notes":"DB 存小数 (0.05 = 5%)"},
        {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE code = 'COMP-V4-ELEMENT-BOM';

-- ═════════════════════════════════════════════════════════════════════
-- 5. RAW-BOM 单价 路径切到 MAT_PRICE
-- ═════════════════════════════════════════════════════════════════════
UPDATE component
SET fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.seq_no"},
        {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.input_material_no"},
        {"name":"组成用量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.input_qty"},
        {"name":"底数","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.output_qty"},
        {"name":"来料损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.loss_rate","notes":"DB 存小数"},
        {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.output_loss_rate","notes":"DB 存小数"},
        {"name":"单价(CNY/KG)","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_costing_material_price.costing_price","global_variable_code":"MAT_PRICE","notes":"V109: 取自全局变量 MAT_PRICE; ImplicitJoinRewriter 按 input_material_no 自动注入"},
        {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE code = 'COMP-V4-RAW-BOM';

-- ═════════════════════════════════════════════════════════════════════
-- 6. 同步重建模板 components_snapshot
-- ═════════════════════════════════════════════════════════════════════
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
    RAISE NOTICE 'V109: 已重建模板 components_snapshot';
END $$;
