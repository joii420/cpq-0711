-- V195: 「选配产品标准报价模板-组合产品」(b1d2e3f4-...-163) snapshot 改造
--
-- 配套 V194 — 把材质/元素含量/工序/单重 4 个 Tab 的 driver path 改成聚合视图,
-- 字段路径改成 v_composite_child_*.<col>. 让父卡片一次性展示所有子件数据.
--
-- 设计约定:
--   - 4 个 Tab 加「子件」列在最前显示 child_part_name (识别哪行属哪子件)
--   - driver_path 用聚合视图本名 (无谓词), ImplicitJoinRewriter 自动注入父级 hf_part_no
--   - 单重 Tab 从「无 driver」改为按子件展开 (N 行)

-- ── 仅改 snapshot, 不改 component 表 ──────────────────────────────────────
-- 注意: 这里只动 template.components_snapshot, COMP-CFG-MATERIAL-RECIPE 等
-- component 配置不变 (这些 component 仍服务于「选配产品标准报价模板-单一产品」)
UPDATE template
SET components_snapshot = (
    SELECT jsonb_agg(
        CASE
            -- 材质 Tab: 改 v_composite_child_materials 视图按子件展开
            WHEN (comp->>'componentCode') = 'COMP-CFG-MATERIAL-RECIPE' THEN
                jsonb_set(
                    jsonb_set(comp, '{data_driver_path}',
                        to_jsonb('v_composite_child_materials'::text)),
                    '{fields}', '[
                        {"name":"子件","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.child_part_name"},
                        {"name":"材质代码","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.material_code"},
                        {"name":"化学符号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.chemical_symbol"},
                        {"name":"材质名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.material_name"},
                        {"name":"规格标签","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.spec_label"},
                        {"name":"配方类型","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.recipe_type"}
                    ]'::jsonb
                )
            -- 元素含量 Tab: 改 v_composite_child_elements
            WHEN (comp->>'componentCode') = 'COMP-CFG-ELEMENT-BOM' THEN
                jsonb_set(
                    jsonb_set(comp, '{data_driver_path}',
                        to_jsonb('v_composite_child_elements'::text)),
                    '{fields}', '[
                        {"name":"子件","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_elements.child_part_name"},
                        {"name":"序号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_elements.seq_no"},
                        {"name":"元素","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_elements.element_name"},
                        {"name":"含量","notes":"百分比 75=75%","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_elements.composition_pct"},
                        {"name":"单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_costing_element_price.unit","global_variable_code":"ELEM_PRICE"},
                        {"name":"单价","content":"","is_amount":true,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_costing_element_price.costing_price","global_variable_code":"ELEM_PRICE"},
                        {"name":"小计","is_amount":true,"field_type":"FORMULA","is_subtotal":true,"formula_tokens":[{"type":"field","value":"含量"},{"type":"operator","label":"÷","value":"/"},{"type":"number","value":"100"},{"type":"operator","label":"×","value":"*"},{"type":"field","value":"单价"}]}
                    ]'::jsonb
                )
            -- 工序 Tab: 改 v_composite_child_processes
            WHEN (comp->>'componentCode') = 'COMP-CFG-PROCESS' THEN
                jsonb_set(
                    jsonb_set(comp, '{data_driver_path}',
                        to_jsonb('v_composite_child_processes'::text)),
                    '{fields}', '[
                        {"name":"子件","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_processes.child_part_name"},
                        {"name":"序号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_processes.seq_no"},
                        {"name":"工序代码","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_processes.process_code"},
                        {"name":"工序","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_processes.assembly_process"},
                        {"name":"单价","content":"","is_amount":true,"field_type":"DATA_SOURCE","is_subtotal":false,"datasource_binding":{"type":"GLOBAL_VARIABLE","global_variable_code":"PROCESS_DEFAULT_PRICE","key_field_refs":{}}},
                        {"name":"成材率","content":"100","is_amount":false,"field_type":"DATA_SOURCE","is_subtotal":false,"datasource_binding":{"type":"GLOBAL_VARIABLE","global_variable_code":"PROCESS_DEFAULT_YIELD","key_field_refs":{}}},
                        {"name":"小计","is_amount":true,"field_type":"FORMULA","is_subtotal":true,"formula_tokens":[{"type":"previous_row_subtotal","fallback_component_code":"COMP-CFG-ELEMENT-BOM"},{"type":"operator","label":"÷","value":"/"},{"type":"field","value":"成材率"},{"type":"operator","label":"×","value":"*"},{"type":"number","value":"100"},{"type":"operator","label":"+","value":"+"},{"type":"field","value":"单价"}]}
                    ]'::jsonb
                )
            -- 单重 Tab: 改 v_composite_child_weights 按子件展开
            WHEN (comp->>'componentCode') = 'COMP-CFG-WEIGHT' THEN
                jsonb_set(
                    jsonb_set(comp, '{data_driver_path}',
                        to_jsonb('v_composite_child_weights'::text)),
                    '{fields}', '[
                        {"name":"子件","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_weights.child_part_name"},
                        {"name":"单重","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_weights.unit_weight"},
                        {"name":"单位","content":"g","is_amount":false,"field_type":"FIXED_VALUE","is_subtotal":false}
                    ]'::jsonb
                )
            -- 组合工艺 / 子配件 / 总成本 Tab 保持不变
            ELSE comp
        END
    )
    FROM jsonb_array_elements(components_snapshot) AS comp
),
updated_at = NOW()
WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';

-- ── 自检 ────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_materials_updated BOOLEAN;
    v_elements_updated BOOLEAN;
    v_processes_updated BOOLEAN;
    v_weights_updated BOOLEAN;
BEGIN
    SELECT components_snapshot::text LIKE '%v_composite_child_materials%' INTO v_materials_updated
        FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';
    SELECT components_snapshot::text LIKE '%v_composite_child_elements%' INTO v_elements_updated
        FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';
    SELECT components_snapshot::text LIKE '%v_composite_child_processes%' INTO v_processes_updated
        FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';
    SELECT components_snapshot::text LIKE '%v_composite_child_weights%' INTO v_weights_updated
        FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';

    IF NOT (v_materials_updated AND v_elements_updated AND v_processes_updated AND v_weights_updated) THEN
        RAISE EXCEPTION 'V195 自检失败: 组合模板 snapshot 4 Tab 未全部切到 v_composite_child_* 视图 (m=% e=% p=% w=%)',
            v_materials_updated, v_elements_updated, v_processes_updated, v_weights_updated;
    END IF;
    RAISE NOTICE 'V195 完成: 组合产品模板 163 snapshot 已切到聚合视图';
END $$;
