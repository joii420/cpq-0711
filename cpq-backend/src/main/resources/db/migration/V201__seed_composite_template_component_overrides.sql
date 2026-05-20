-- V201: 把 V195 写在 snapshot 字面的 COMPOSITE 覆盖落到 template_component.override 列
--
-- 背景:
--   V200 已加 data_driver_path_override / fields_override 两列.
--   V195/V198/V199 都在 template.components_snapshot 字面写覆盖, 每次 publish 都丢失.
--   现在把覆盖从 snapshot 物理迁移到 template_component 上 — 给所有"组合产品"系列模板
--   (id IN v1.0..v1.3) 的 4 个 COMPOSITE-only 组件 (材质/元素含量/工序/单重) 落 override 行.
--
-- 落到 override 之后:
--   - publish() 会按 (component 基础) ⊕ (template_component.override) 重建 snapshot
--     → v_composite_child_* + 子件列 全部保留
--   - 用户派生新版本(createNewDraft 已复制 override) → 新 draft 自动继承覆盖
--   - 不再需要 V198/V199 这种"修补 snapshot 字面"的迁移

DO $main$
DECLARE
    v_target_ids UUID[] := ARRAY[
        'b1d2e3f4-cf63-4163-8163-000000000163'::UUID,
        '2d196350-2096-402d-bb1b-119cb2dec9bc'::UUID,
        'b3b0b65f-d201-45b0-94c7-caef352d4398'::UUID,
        'ab826fea-2f4a-4844-af99-4d1de9930c8e'::UUID
    ];
    v_tpl_id UUID;
    v_updated INT := 0;
BEGIN
    FOREACH v_tpl_id IN ARRAY v_target_ids
    LOOP
        IF NOT EXISTS (SELECT 1 FROM template WHERE id = v_tpl_id) THEN
            RAISE NOTICE 'V201: 模板 % 不存在, 跳过', v_tpl_id;
            CONTINUE;
        END IF;

        UPDATE template_component tc
        SET data_driver_path_override = CASE
            WHEN c.code = 'COMP-CFG-MATERIAL-RECIPE' THEN 'v_composite_child_materials'
            WHEN c.code = 'COMP-CFG-ELEMENT-BOM' THEN 'v_composite_child_elements'
            WHEN c.code = 'COMP-CFG-PROCESS' THEN 'v_composite_child_processes'
            WHEN c.code = 'COMP-CFG-WEIGHT' THEN 'v_composite_child_weights'
            ELSE NULL
        END
        FROM component c
        WHERE tc.component_id = c.id
          AND tc.template_id = v_tpl_id
          AND c.code IN ('COMP-CFG-MATERIAL-RECIPE', 'COMP-CFG-ELEMENT-BOM', 'COMP-CFG-PROCESS', 'COMP-CFG-WEIGHT');

        UPDATE template_component tc
        SET fields_override = CASE
            WHEN c.code = 'COMP-CFG-MATERIAL-RECIPE' THEN $fields$[{"name":"子件","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.child_part_name"},{"name":"材质代码","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.material_code"},{"name":"化学符号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.chemical_symbol"},{"name":"材质名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.material_name"},{"name":"规格标签","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.spec_label"},{"name":"配方类型","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_materials.recipe_type"}]$fields$::jsonb
            WHEN c.code = 'COMP-CFG-ELEMENT-BOM' THEN $fields$[{"name":"子件","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_elements.child_part_name"},{"name":"序号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_elements.seq_no"},{"name":"元素","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_elements.element_name"},{"name":"含量","notes":"百分比 75=75%","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_elements.composition_pct"},{"name":"单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_costing_element_price.unit","global_variable_code":"ELEM_PRICE"},{"name":"单价","content":"","is_amount":true,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_costing_element_price.costing_price","global_variable_code":"ELEM_PRICE"},{"name":"小计","is_amount":true,"field_type":"FORMULA","is_subtotal":true,"formula_tokens":[{"type":"field","value":"含量"},{"type":"operator","label":"÷","value":"/"},{"type":"number","value":"100"},{"type":"operator","label":"×","value":"*"},{"type":"field","value":"单价"}]}]$fields$::jsonb
            WHEN c.code = 'COMP-CFG-PROCESS' THEN $fields$[{"name":"子件","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_processes.child_part_name"},{"name":"序号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_processes.seq_no"},{"name":"工序代码","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_processes.process_code"},{"name":"工序","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_processes.assembly_process"},{"name":"单价","content":"","is_amount":true,"field_type":"DATA_SOURCE","is_subtotal":false,"datasource_binding":{"type":"GLOBAL_VARIABLE","global_variable_code":"PROCESS_DEFAULT_PRICE","key_field_refs":{}}},{"name":"成材率","content":"100","is_amount":false,"field_type":"DATA_SOURCE","is_subtotal":false,"datasource_binding":{"type":"GLOBAL_VARIABLE","global_variable_code":"PROCESS_DEFAULT_YIELD","key_field_refs":{}}},{"name":"小计","is_amount":true,"field_type":"FORMULA","is_subtotal":true,"formula_tokens":[{"type":"previous_row_subtotal","fallback_component_code":"COMP-CFG-ELEMENT-BOM"},{"type":"operator","label":"÷","value":"/"},{"type":"field","value":"成材率"},{"type":"operator","label":"×","value":"*"},{"type":"number","value":"100"},{"type":"operator","label":"+","value":"+"},{"type":"field","value":"单价"}]}]$fields$::jsonb
            WHEN c.code = 'COMP-CFG-WEIGHT' THEN $fields$[{"name":"子件","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_weights.child_part_name"},{"name":"单重","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_composite_child_weights.unit_weight"},{"name":"单位","content":"g","is_amount":false,"field_type":"FIXED_VALUE","is_subtotal":false}]$fields$::jsonb
            ELSE NULL
        END
        FROM component c
        WHERE tc.component_id = c.id
          AND tc.template_id = v_tpl_id
          AND c.code IN ('COMP-CFG-MATERIAL-RECIPE', 'COMP-CFG-ELEMENT-BOM', 'COMP-CFG-PROCESS', 'COMP-CFG-WEIGHT');

        v_updated := v_updated + 1;
        RAISE NOTICE 'V201: 已为模板 % 落 4 个 COMPOSITE override', v_tpl_id;
    END LOOP;

    RAISE NOTICE 'V201 part-1 完成: 共更新 % 份组合产品模板的 template_component override', v_updated;
END
$main$;

-- 用 override 立即重建 4 个模板的 snapshot (与 publish() 逻辑同款)
UPDATE template t
SET components_snapshot = (
    SELECT jsonb_agg(
        jsonb_build_object(
            'id', tc.id::text,
            'componentId', c.id::text,
            'componentName', c.name,
            'componentCode', c.code,
            'componentType', c.component_type,
            'tabName', tc.tab_name,
            'sortOrder', tc.sort_order,
            'fields', COALESCE(tc.fields_override, c.fields::jsonb, '[]'::jsonb),
            'formulas', COALESCE(c.formulas::jsonb, '[]'::jsonb),
            'preset_rows', COALESCE(tc.preset_rows, '[]'::jsonb),
            'data_driver_path', COALESCE(tc.data_driver_path_override, c.data_driver_path),
            'formula_assignments', COALESCE(tc.formula_assignments, '{}'::jsonb)
        )
        ORDER BY tc.sort_order
    )
    FROM template_component tc
    JOIN component c ON c.id = tc.component_id
    WHERE tc.template_id = t.id
),
updated_at = NOW()
WHERE t.id IN (
    'b1d2e3f4-cf63-4163-8163-000000000163',
    '2d196350-2096-402d-bb1b-119cb2dec9bc',
    'b3b0b65f-d201-45b0-94c7-caef352d4398',
    'ab826fea-2f4a-4844-af99-4d1de9930c8e'
);

DO $$
DECLARE
    v_tpl_id UUID;
    v_snap_text TEXT;
BEGIN
    FOR v_tpl_id IN
        SELECT unnest(ARRAY[
            'b1d2e3f4-cf63-4163-8163-000000000163'::UUID,
            '2d196350-2096-402d-bb1b-119cb2dec9bc'::UUID,
            'b3b0b65f-d201-45b0-94c7-caef352d4398'::UUID,
            'ab826fea-2f4a-4844-af99-4d1de9930c8e'::UUID
        ])
    LOOP
        IF NOT EXISTS (SELECT 1 FROM template WHERE id = v_tpl_id) THEN CONTINUE; END IF;
        SELECT components_snapshot::text INTO v_snap_text FROM template WHERE id = v_tpl_id;
        IF v_snap_text NOT LIKE '%v_composite_child_materials%'
           OR v_snap_text NOT LIKE '%v_composite_child_elements%'
           OR v_snap_text NOT LIKE '%v_composite_child_processes%'
           OR v_snap_text NOT LIKE '%v_composite_child_weights%' THEN
            RAISE EXCEPTION 'V201 自检失败: 模板 % snapshot 缺 v_composite_child_* 路径', v_tpl_id;
        END IF;
    END LOOP;
    RAISE NOTICE 'V201 自检通过: 4 份 COMPOSITE 模板 snapshot 已含 v_composite_child_*';
END
$$;
