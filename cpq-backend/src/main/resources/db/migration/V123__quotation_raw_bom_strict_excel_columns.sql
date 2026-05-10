-- V123: COMP-Q-RAW-BOM 字段严格对齐报价 Excel「来料BOM」sheet 列, 移除核价侧"单价/行小计"
--
-- 用户截图确认报价 Excel「来料BOM」实际列 (9 列):
--   宏丰料号 / 项次 / 投入料号 / 投入料号名称 / 产出料号类型 /
--   材料毛重 / 材料净重 / 重量单位 / 损耗率(%) / 不良率(%)
--
-- V116/V122 错误地从核价 V4 复用 "单价(CNY/KG)" + "行小计" → 报价 Excel 没这两列
-- → 单价 cell 跨表 JOIN v_costing_material_price 失败 → "55 (共 2 项)"
-- → 行小计公式依赖单价 → 0 / NaN
--
-- 修法: 删掉单价 + 行小计, 加上"产出料号类型"/"重量单位" (Excel 实有但 V122 漏配)
-- 报价侧"销售单价"由报价单层级 (quotation_line_item.subtotal 等) 维护, 不在 BOM tab.

UPDATE component
SET data_driver_path = 'mat_bom[bom_type=''INCOMING'']',
    fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].seq_no"},
        {"name":"投入料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].input_material_no"},
        {"name":"投入料号名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].input_material_name"},
        {"name":"产出料号类型","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].output_material_type"},
        {"name":"材料毛重","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].gross_qty"},
        {"name":"材料净重","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].net_qty"},
        {"name":"重量单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].net_unit"},
        {"name":"损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].loss_rate","notes":"V123: mat_bom.loss_rate 单位=百分比 (5 = 5%)"},
        {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].defect_rate"}
    ]$JSON$::jsonb,
    formulas = '[]'::jsonb,
    column_count = 9,
    updated_at = now()
WHERE code = 'COMP-Q-RAW-BOM';

-- 重建持有此组件的 PUBLISHED 报价模板的 components_snapshot
DO $$
DECLARE v_tpl RECORD;
BEGIN
    FOR v_tpl IN
        SELECT DISTINCT t.id
        FROM template t
        JOIN template_component tc ON tc.template_id = t.id
        JOIN component c ON c.id = tc.component_id
        WHERE c.code = 'COMP-Q-RAW-BOM' AND t.template_kind = 'QUOTATION'
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
        RAISE NOTICE 'V123: 重建 % snapshot', v_tpl.id;
    END LOOP;
END $$;
