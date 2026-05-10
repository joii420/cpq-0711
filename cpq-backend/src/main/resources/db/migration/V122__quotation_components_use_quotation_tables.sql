-- V122: 报价模板组件 driver 表切换 — 从核价侧物理表切到报价侧物理表
--
-- 用户报告 QT-20260507-1356 报价产品卡片「来料BOM」tab 显示问题:
--   - 行数: 1 行 (driver 展开 0 行 → fallback comp.rows)
--   - cell: 大部分「—」, 单价显示「55 (共 2 项)」, 行小计 NaN
--
-- 根因 (4 类联合):
--   1. partNo=3120012574 在 costing_part_material_bom 表 0 行 → driver 展开返 0
--   2. fallback 后 row 没数据, BASIC_DATA cell 走 globalPathCache
--   3. v_costing_material_price 视图无 hf_part_no 列 → ImplicitJoinRewriter 不收窄 → 全表 2 行
--   4. **真正问题**: V116 配 COMP-Q-RAW-BOM/COMPONENT-BOM/WEIGHT 时直接复用核价 V4 配置
--      → 指向了 costing_part_* 这些**核价侧物理表**, 而 V5 import 报价模板 (kind=QUOTATION)
--      把报价 Excel 的同名 sheet 数据写到 mat_bom/mat_process/mat_part 等**报价侧表**
--
-- 报价侧表对应:
--   sheet「来料BOM」(QUOTATION)        → mat_bom[bom_type='INCOMING']  (basic_data_config 已注册)
--   sheet「组成件BOM及单价」 (QUOTATION) → mat_process                   (basic_data_config 已注册)
--   sheet「单重」 (QUOTATION)           → mat_part                       (basic_data_config 已注册)
--
-- 修法: 改 3 个报价组件的 data_driver_path + fields BASIC_DATA path 指向报价侧物理表

-- ════════════════════════════════════════════════════════════════════════════
-- A. COMP-Q-RAW-BOM: 切到 mat_bom[bom_type='INCOMING']
-- ════════════════════════════════════════════════════════════════════════════
UPDATE component
SET data_driver_path = 'mat_bom[bom_type=''INCOMING'']',
    fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].seq_no"},
        {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].input_material_no"},
        {"name":"来料料号名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].input_material_name"},
        {"name":"组成用量(毛重)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].gross_qty"},
        {"name":"底数(净重)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].net_qty"},
        {"name":"净重单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].net_unit"},
        {"name":"来料损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].loss_rate","notes":"V122: mat_bom.loss_rate 单位=百分比 (5 = 5%)"},
        {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='INCOMING'].defect_rate"},
        {"name":"单价(CNY/KG)","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_costing_material_price.costing_price","global_variable_code":"MAT_PRICE","notes":"V122: 跨表全局变量, ImplicitJoinRewriter 按 input_material_no 自动 JOIN"},
        {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"=组成用量÷底数×(1+不良率÷100)×单价"}
    ]$JSON$::jsonb,
    formulas = $JSON$[
        {"name":"行小计","result_type":"AMOUNT","expression":[
            {"type":"field","label":"组成用量(毛重)","value":"组成用量(毛重)"},
            {"type":"operator","label":"÷","value":"/"},
            {"type":"field","label":"底数(净重)","value":"底数(净重)"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"bracket_open","label":"(","value":"("},
            {"type":"number","label":"1","value":"1"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"field","label":"不良率(%)","value":"不良率(%)"},
            {"type":"operator","label":"÷","value":"/"},
            {"type":"number","label":"100","value":"100"},
            {"type":"bracket_close","label":")","value":")"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"field","label":"单价(CNY/KG)","value":"单价(CNY/KG)"}
        ]}
    ]$JSON$::jsonb,
    column_count = 10,
    updated_at = now()
WHERE code = 'COMP-Q-RAW-BOM';

-- ════════════════════════════════════════════════════════════════════════════
-- B. COMP-Q-COMPONENT-BOM: 切到 mat_process
-- ════════════════════════════════════════════════════════════════════════════
UPDATE component
SET data_driver_path = 'mat_process',
    fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.seq_no"},
        {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.process_code"},
        {"name":"装配类型","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.assembly_process"},
        {"name":"二级序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.sub_seq_no"},
        {"name":"组成件料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.component_part_no"},
        {"name":"组成件名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.component_name"},
        {"name":"供应商编码","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.supplier_code"},
        {"name":"供应商名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.supplier_name"},
        {"name":"组成数量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.quantity"},
        {"name":"组成单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.quantity_unit"},
        {"name":"单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"mat_process.unit_price"},
        {"name":"运费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"mat_process.freight"},
        {"name":"货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.currency"},
        {"name":"计价单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_process.price_unit"}
    ]$JSON$::jsonb,
    formulas = '[]'::jsonb,
    column_count = 14,
    updated_at = now()
WHERE code = 'COMP-Q-COMPONENT-BOM';

-- ════════════════════════════════════════════════════════════════════════════
-- C. COMP-Q-WEIGHT: 切到 mat_part (按 part_no 隐式 JOIN partNo)
-- ════════════════════════════════════════════════════════════════════════════
UPDATE component
SET data_driver_path = 'mat_part',
    fields = $JSON$[
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_part.part_no"},
        {"name":"料号名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_part.part_name"},
        {"name":"单重(g/pcs)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_part.unit_weight"},
        {"name":"单重单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_part.weight_unit"}
    ]$JSON$::jsonb,
    formulas = '[]'::jsonb,
    column_count = 4,
    updated_at = now()
WHERE code = 'COMP-Q-WEIGHT';

-- ════════════════════════════════════════════════════════════════════════════
-- D. 重建持有这些组件的 PUBLISHED 报价模板的 components_snapshot
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE v_tpl RECORD;
BEGIN
    FOR v_tpl IN
        SELECT DISTINCT t.id
        FROM template t
        JOIN template_component tc ON tc.template_id = t.id
        JOIN component c ON c.id = tc.component_id
        WHERE c.code IN ('COMP-Q-RAW-BOM','COMP-Q-COMPONENT-BOM','COMP-Q-WEIGHT')
          AND t.template_kind = 'QUOTATION'
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
        RAISE NOTICE 'V122-D: 重建 % snapshot', v_tpl.id;
    END LOOP;
END $$;
