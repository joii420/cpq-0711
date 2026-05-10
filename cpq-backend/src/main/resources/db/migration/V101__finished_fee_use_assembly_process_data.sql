-- V101: COMP-V4-FINISHED-FEE 不良率字段改 BASIC_DATA + 整组件切到 mat_fee[fee_type='ASSEMBLY_PROCESS']
--
-- 用户报告: 「核价-成品加工费」 的"不良率%"是 INPUT_NUMBER (用户手填), 应该是 BASIC_DATA 引用物理表。
--
-- 问题分析:
--   1. costing_part_process_cost 表没有 reject_rate / defect_rate 列 → 原 V99 路径无法读到不良率
--   2. mat_fee[fee_type='ASSEMBLY_PROCESS'] 表有完整 4 字段:
--        dim_assembly_process (工序名), fee_value (加工费), reject_rate (不良率小数), seq_no
--   3. 实测 partNo='3100080003' 返回 2 行数据 (焊接 44/0.01, 铆接 23/0.02)
--
-- 修复:
--   1. data_driver_path 切到 mat_fee[fee_type='ASSEMBLY_PROCESS'] (所有字段在同一表, implicit join 完美)
--   2. 工序号/加工费/不良率 全改 BASIC_DATA, 路径都从 mat_fee[ASSEMBLY_PROCESS] 取
--   3. 行小计公式改为 `=加工费 × (1 + 不良率)` — reject_rate 在 DB 是小数 (0.01=1%), 不再 /100
--
-- 同步修 COMP-V4-PLATING-COST 的同类问题:
--   plating_fee.defect_rate 也是小数, 行小计公式 `=(加工+材料)×(1+不良率/100)` 应该改 `×(1+不良率)`

-- ── 1. COMP-V4-FINISHED-FEE 重写 ──
UPDATE component
SET data_driver_path = 'mat_fee[fee_type=''ASSEMBLY_PROCESS'']',
    fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='ASSEMBLY_PROCESS'].seq_no"},
        {"name":"工序","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='ASSEMBLY_PROCESS'].dim_assembly_process","notes":"组装工序名 (焊接/铆接 等)"},
        {"name":"加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='ASSEMBLY_PROCESS'].fee_value"},
        {"name":"不良率","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='ASSEMBLY_PROCESS'].reject_rate","notes":"DB 存小数 (0.01=1%), 公式中直接乘不再 /100"},
        {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 加工费 × (1 + 不良率)"}
    ]$JSON$::jsonb,
    formulas = $JSON$[
        {"name":"行小计","result_type":"AMOUNT","expression":[
            {"type":"field","label":"加工费","value":"加工费"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"bracket_open","label":"(","value":"("},
            {"type":"number","label":"1","value":"1"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"field","label":"不良率","value":"不良率"},
            {"type":"bracket_close","label":")","value":")"}
        ]}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE code = 'COMP-V4-FINISHED-FEE';

-- ── 2. COMP-V4-PLATING-COST 修同类问题 (defect_rate 也是小数) ──
-- 仅修改公式, fields 不变 (V99 已配 plating_fee.defect_rate BASIC_DATA path)
UPDATE component
SET formulas = $JSON$[
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

-- ── 3. 同步重建模板 components_snapshot ──
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
    RAISE NOTICE 'V101: 已重建模板 components_snapshot';
END $$;
