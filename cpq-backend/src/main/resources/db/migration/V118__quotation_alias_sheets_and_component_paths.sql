-- V118: 报价侧 3 个 sheet 别名注册 + 4 个组件 BASIC_DATA path 化 (用户拍板的方案 A 减项版)
--
-- 用户拍板:
--   ✓ 材料固定加工费 → mat_fee[fee_type='INCOMING_FIXED'] 别名注册 (kind=QUOTATION)
--   ✓ 材料其他费用   → mat_fee[fee_type='INCOMING_OTHER']  别名注册
--   ✓ 材料回收折扣   → mat_fee 新 fee_type='MATERIAL_RECYCLE' (扩 CHECK 约束 + 全新 5 列 attribute)
--   ✓ 元素回收折扣   → 组件配置已是 mat_bom[bom_type='ELEMENT'] BASIC_DATA, 但 sheet 名跟 V5 注册的「元素BOM」不一致;
--                     V5 import 仍然用「元素BOM」入库 (含 defect_rate 字段). 此组件的 BASIC_DATA path 没问题, 不动配置.
--                     用户「元素回收折扣」sheet 当前不被 V5 识别 → 不入库; 不在本次范围.
--   ✗ 材料年降       (用户暂不做)
--   ✗ 组成件BOM及零部件 (sheet 不存在)
--   ✗ 产品电镀         (sheet 不存在)

-- ════════════════════════════════════════════════════════════════════════════
-- 1. mat_fee.fee_type CHECK 约束扩展: 新增 MATERIAL_RECYCLE
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_fee DROP CONSTRAINT IF EXISTS chk_mat_fee_type;
ALTER TABLE mat_fee ADD CONSTRAINT chk_mat_fee_type CHECK (fee_type IN (
    'INCOMING_FIXED','INCOMING_OTHER','FINISHED_FIXED','FINISHED_OTHER',
    'ASSEMBLY_PROCESS','INCOMING_ANNUAL_DOWN','ASSEMBLY_ANNUAL_DOWN','ANNUAL_REDUCTION_FACTOR',
    'MATERIAL_RECYCLE'  -- V118
));

-- ════════════════════════════════════════════════════════════════════════════
-- 2. basic_data_config + basic_data_attribute 注册 3 个 sheet 别名
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_src_fixed_id    UUID;
    v_src_other_id    UUID;
    v_new_fixed_id    UUID := gen_random_uuid();
    v_new_other_id    UUID := gen_random_uuid();
    v_new_recycle_id  UUID := gen_random_uuid();
BEGIN
    -- 找源 config 行 (复用「来料固定加工费」/「来料其他费用」的 attribute)
    SELECT id INTO v_src_fixed_id FROM basic_data_config
        WHERE sheet_name='来料固定加工费' AND template_kind='BOTH' AND status='ACTIVE' LIMIT 1;
    SELECT id INTO v_src_other_id FROM basic_data_config
        WHERE sheet_name='来料其他费用'   AND template_kind='QUOTATION' AND status='ACTIVE' LIMIT 1;

    -- ── (1) 材料固定加工费 (QUOTATION 别名)
    IF v_src_fixed_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM basic_data_config WHERE sheet_name='材料固定加工费' AND template_kind='QUOTATION' AND status='ACTIVE'
    ) THEN
        INSERT INTO basic_data_config (id, sheet_name, target_table, target_discriminator, template_kind,
                                       header_row_index, data_start_row_index, sort_order, status, description,
                                       created_at, updated_at)
        SELECT v_new_fixed_id, '材料固定加工费', target_table, target_discriminator, 'QUOTATION',
               header_row_index, data_start_row_index, sort_order, status,
               'V118: 报价侧 sheet 别名「材料固定加工费」, 物理表 mat_fee + fee_type=INCOMING_FIXED, 等价于「来料固定加工费」',
               now(), now()
        FROM basic_data_config WHERE id = v_src_fixed_id;

        INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label,
                                          data_type, status, sort_order, importance_level, affects_calculation, is_required,
                                          created_at, updated_at)
        SELECT gen_random_uuid(), v_new_fixed_id, column_letter, column_title, variable_code, variable_label,
               data_type, status, sort_order, importance_level, affects_calculation, is_required, now(), now()
        FROM basic_data_attribute WHERE config_id = v_src_fixed_id;

        RAISE NOTICE 'V118: 注册 sheet 别名「材料固定加工费」 → %', v_new_fixed_id;
    END IF;

    -- ── (2) 材料其他费用 (QUOTATION 别名)
    IF v_src_other_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM basic_data_config WHERE sheet_name='材料其他费用' AND template_kind='QUOTATION' AND status='ACTIVE'
    ) THEN
        INSERT INTO basic_data_config (id, sheet_name, target_table, target_discriminator, template_kind,
                                       header_row_index, data_start_row_index, sort_order, status, description,
                                       created_at, updated_at)
        SELECT v_new_other_id, '材料其他费用', target_table, target_discriminator, 'QUOTATION',
               header_row_index, data_start_row_index, sort_order, status,
               'V118: 报价侧 sheet 别名「材料其他费用」, 物理表 mat_fee + fee_type=INCOMING_OTHER, 等价于「来料其他费用」',
               now(), now()
        FROM basic_data_config WHERE id = v_src_other_id;

        INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label,
                                          data_type, status, sort_order, importance_level, affects_calculation, is_required,
                                          created_at, updated_at)
        SELECT gen_random_uuid(), v_new_other_id, column_letter, column_title, variable_code, variable_label,
               data_type, status, sort_order, importance_level, affects_calculation, is_required, now(), now()
        FROM basic_data_attribute WHERE config_id = v_src_other_id;

        RAISE NOTICE 'V118: 注册 sheet 别名「材料其他费用」 → %', v_new_other_id;
    END IF;

    -- ── (3) 材料回收折扣 (QUOTATION, 全新 fee_type=MATERIAL_RECYCLE)
    IF NOT EXISTS (
        SELECT 1 FROM basic_data_config WHERE sheet_name='材料回收折扣' AND template_kind='QUOTATION' AND status='ACTIVE'
    ) THEN
        INSERT INTO basic_data_config (id, sheet_name, target_table, target_discriminator, template_kind,
                                       header_row_index, data_start_row_index, sort_order, status, description,
                                       created_at, updated_at)
        VALUES (v_new_recycle_id, '材料回收折扣', 'mat_fee',
                '{"fee_type":"MATERIAL_RECYCLE"}'::jsonb, 'QUOTATION',
                1, 2, 100, 'ACTIVE',
                'V118: 报价侧「材料回收折扣」, 物理表 mat_fee + fee_type=MATERIAL_RECYCLE; fee_ratio 字段存折扣百分比',
                now(), now());

        INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label,
                                          data_type, status, sort_order, importance_level, affects_calculation, is_required,
                                          created_at, updated_at) VALUES
            (gen_random_uuid(), v_new_recycle_id, 'A', '宏丰料号',     'hf_part_no',              '宏丰料号',   'IDENTIFIER', 'ACTIVE', 1, 'IMPORTANT', false, true,  now(), now()),
            (gen_random_uuid(), v_new_recycle_id, 'B', '序号',         'seq_no',                  '序号',       'VALUE',      'ACTIVE', 2, 'NORMAL',    false, false, now(), now()),
            (gen_random_uuid(), v_new_recycle_id, 'C', '投料号',       'dim_input_material_no',   '投料号',     'VALUE',      'ACTIVE', 3, 'NORMAL',    false, false, now(), now()),
            (gen_random_uuid(), v_new_recycle_id, 'D', '投料号名称',   'dim_input_material_name', '投料号名称', 'VALUE',      'ACTIVE', 4, 'NORMAL',    false, false, now(), now()),
            (gen_random_uuid(), v_new_recycle_id, 'E', '回收折扣(%)',  'fee_ratio',               '回收折扣',   'VALUE',      'ACTIVE', 5, 'IMPORTANT', true,  false, now(), now());

        RAISE NOTICE 'V118: 注册 sheet「材料回收折扣」 → %', v_new_recycle_id;
    END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- 3. 升级 4 个组件: INPUT 占位 → BASIC_DATA path
-- ════════════════════════════════════════════════════════════════════════════

-- COMP-Q-MATERIAL-FEE: 重写为 mat_fee[INCOMING_FIXED] 多行 driver
UPDATE component
SET data_driver_path = 'mat_fee[fee_type=''INCOMING_FIXED'']',
    fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_FIXED'].seq_no"},
        {"name":"投料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_FIXED'].dim_input_material_no"},
        {"name":"投料号名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_FIXED'].dim_input_material_name"},
        {"name":"值","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_FIXED'].fee_value"},
        {"name":"比例(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_FIXED'].fee_ratio"},
        {"name":"货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_FIXED'].currency"},
        {"name":"计价单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_FIXED'].price_unit"}
    ]$JSON$::jsonb,
    formulas = '[]'::jsonb,
    column_count = 7,
    updated_at = now()
WHERE code = 'COMP-Q-MATERIAL-FEE';

-- COMP-Q-MATERIAL-OTHER: V116 复用核价 INCOMING-OTHER, 已是 mat_fee[INCOMING_OTHER] BASIC_DATA, 验证不变
-- (此处不改; 显式列出确认状态)

-- COMP-Q-MATERIAL-RECYCLE: INPUT 占位 → BASIC_DATA path mat_fee[MATERIAL_RECYCLE]
UPDATE component
SET data_driver_path = 'mat_fee[fee_type=''MATERIAL_RECYCLE'']',
    fields = $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='MATERIAL_RECYCLE'].seq_no"},
        {"name":"投料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='MATERIAL_RECYCLE'].dim_input_material_no"},
        {"name":"投料号名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='MATERIAL_RECYCLE'].dim_input_material_name"},
        {"name":"回收折扣(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='MATERIAL_RECYCLE'].fee_ratio","notes":"V118: 折扣百分比, 公式中按业务约定使用 (×(1-折扣) 或 ×折扣)"}
    ]$JSON$::jsonb,
    formulas = '[]'::jsonb,
    column_count = 4,
    updated_at = now()
WHERE code = 'COMP-Q-MATERIAL-RECYCLE';

-- COMP-Q-ELEMENT-RECYCLE: V116 已是 mat_bom[bom_type='ELEMENT'] BASIC_DATA. 不改.

-- ════════════════════════════════════════════════════════════════════════════
-- 4. 重建持有这些组件的 PUBLISHED 模板的 components_snapshot
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE v_tpl RECORD;
BEGIN
    FOR v_tpl IN
        SELECT DISTINCT t.id
        FROM template t
        JOIN template_component tc ON tc.template_id = t.id
        JOIN component c ON c.id = tc.component_id
        WHERE c.code IN ('COMP-Q-MATERIAL-FEE','COMP-Q-MATERIAL-RECYCLE')
          AND t.status = 'PUBLISHED'
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
        RAISE NOTICE 'V118: 已重建模板 % components_snapshot', v_tpl.id;
    END LOOP;
END $$;
