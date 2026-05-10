-- ============================================================
-- V139: 「组成件其他费用」tab 端到端支持
-- ============================================================
-- 背景:
--   2.0 版 Excel 新增「组成件其他费用」sheet (15 列), 存储工序级组件费用项
--   (包装费/运费/单价/加工费等)。物理表复用 mat_fee, fee_type='COMPONENT_OTHER'。
--   本迁移完成 6 步:
--     1. mat_fee.fee_type CHECK 约束扩展: 加入 COMPONENT_OTHER
--     2. basic_data_config 注册「组成件其他费用」sheet
--     3. basic_data_attribute 插入 10 条列映射 (A/B/D/E/F/G/L/M/N/O)
--     4. 创建视图 v_q_component_fee_merged
--     5. 创建组件 COMP-QX-COMPONENT-FEE (10 字段)
--     6. 模板「报价标准模板-Excel基础结构 v1.0」绑定第 8 个组件 sort_order=7
-- ============================================================

-- ════════════════════════════════════════════════════════════════════════════
-- 1. mat_fee.fee_type CHECK 约束扩展: 加入 COMPONENT_OTHER
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_fee DROP CONSTRAINT IF EXISTS chk_mat_fee_type;
ALTER TABLE mat_fee ADD CONSTRAINT chk_mat_fee_type CHECK (fee_type IN (
    'INCOMING_FIXED',
    'INCOMING_OTHER',
    'FINISHED_FIXED',
    'FINISHED_OTHER',
    'ASSEMBLY_PROCESS',
    'INCOMING_ANNUAL_DOWN',
    'ASSEMBLY_ANNUAL_DOWN',
    'ANNUAL_REDUCTION_FACTOR',
    'MATERIAL_RECYCLE',        -- V118
    'ELEMENT_RECYCLE',         -- V128
    'COMPONENT_OTHER'          -- V139
));

-- ════════════════════════════════════════════════════════════════════════════
-- 2. basic_data_config 注册「组成件其他费用」sheet
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_config_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '组成件其他费用'
          AND template_kind = 'QUOTATION'
          AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V139: sheet「组成件其他费用」已存在, 跳过插入';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index,
        target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index,
        sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_config_id,
        '组成件其他费用',
        406,
        'mat_fee',
        '{"fee_type":"COMPONENT_OTHER"}'::jsonb,
        'QUOTATION',
        1, 2,
        106, 'ACTIVE',
        'V139: 2.0 版 Excel「组成件其他费用」sheet, 物理表 mat_fee + fee_type=COMPONENT_OTHER; 存储工序级组件费用项(包装费/运费/单价/加工费等)',
        now(), now()
    );

    -- 插入 10 条 basic_data_attribute (A/B/D/E/F/G/L/M/N/O 列)
    -- 跳过: C=工序编号 / H=供应商编号 / I=供应商名称 / J=项次(费用级) / K=要素编号
    INSERT INTO basic_data_attribute (
        id, config_id,
        column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order,
        importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
        (gen_random_uuid(), v_config_id, 'A', '宏丰料号',   'hf_part_no',              '宏丰料号',   'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  false, true,  now(), now()),
        (gen_random_uuid(), v_config_id, 'B', '项次',       'seq_no',                  '项次',       'IDENTIFIER', 'ACTIVE',  2, 'IMPORTANT', false, true,  now(), now()),
        (gen_random_uuid(), v_config_id, 'D', '组装工序',   'dim_assembly_process',    '组装工序',   'IDENTIFIER', 'ACTIVE',  3, 'IMPORTANT', false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'E', '组件项次',   'dim_sub_seq_no',          '组件项次',   'IDENTIFIER', 'ACTIVE',  4, 'IMPORTANT', false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'F', '组成件料号', 'dim_input_material_no',   '组成件料号', 'VALUE',      'ACTIVE',  5, 'NORMAL',    false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'G', '组成件名称', 'dim_input_material_name', '组成件名称', 'VALUE',      'ACTIVE',  6, 'NORMAL',    false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'L', '要素名称',   'dim_element_name',        '要素名称',   'IDENTIFIER', 'ACTIVE',  7, 'IMPORTANT', false, true,  now(), now()),
        (gen_random_uuid(), v_config_id, 'M', '值',         'fee_value',               '值',         'VALUE',      'ACTIVE',  8, 'CRITICAL',  true,  true,  now(), now()),
        (gen_random_uuid(), v_config_id, 'N', '货币',       'currency',                '货币',       'VALUE',      'ACTIVE',  9, 'NORMAL',    false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'O', '计价单位',   'price_unit',              '计价单位',   'VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V139: 注册 sheet「组成件其他费用」成功 → config_id=%', v_config_id;
    RAISE NOTICE 'V139: 已插入 10 条 basic_data_attribute (A/B/D/E/F/G/L/M/N/O 列)';
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- 3. 创建视图 v_q_component_fee_merged
-- ════════════════════════════════════════════════════════════════════════════
CREATE OR REPLACE VIEW v_q_component_fee_merged AS
SELECT
    'COMPONENT_OTHER'::VARCHAR              AS source_type,
    hf_part_no,
    seq_no,
    dim_assembly_process                    AS assembly_process,
    dim_sub_seq_no                          AS sub_seq_no,
    dim_input_material_no                   AS component_part_no,
    dim_input_material_name                 AS component_name,
    dim_element_name                        AS element_name,
    fee_value,
    currency,
    price_unit
FROM mat_fee
WHERE fee_type = 'COMPONENT_OTHER'
  AND is_current = true;

-- ════════════════════════════════════════════════════════════════════════════
-- 4. 创建组件 COMP-QX-COMPONENT-FEE (10 字段)
-- ════════════════════════════════════════════════════════════════════════════
INSERT INTO component (
    id, directory_id, name, code, component_type, status,
    data_driver_path, fields, formulas, column_count, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    (SELECT id FROM component_directory WHERE name = '报价模板组件V3-Excel结构' LIMIT 1),
    '组件费用',
    'COMP-QX-COMPONENT-FEE',
    'NORMAL',
    'ACTIVE',
    'v_q_component_fee_merged',
    $JSON$[
        {"name":"宏丰料号",   "field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.hf_part_no"},
        {"name":"项次",       "field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.seq_no"},
        {"name":"组装工序",   "field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.assembly_process"},
        {"name":"组件项次",   "field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.sub_seq_no"},
        {"name":"组成件料号", "field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.component_part_no"},
        {"name":"组成件名称", "field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.component_name"},
        {"name":"要素名称",   "field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.element_name"},
        {"name":"值",         "field_type":"BASIC_DATA","content":"","is_amount":true, "is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.fee_value"},
        {"name":"货币",       "field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.currency"},
        {"name":"计价单位",   "field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.price_unit"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    10,
    now(), now()
)
ON CONFLICT (code) DO UPDATE SET
    fields           = EXCLUDED.fields,
    formulas         = EXCLUDED.formulas,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count     = EXCLUDED.column_count,
    updated_at       = now();

-- ════════════════════════════════════════════════════════════════════════════
-- 5. 模板「报价标准模板-Excel基础结构 v1.0」绑定第 8 个组件 (sort_order=7)
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_tpl_id   UUID;
    v_comp_id  UUID;
BEGIN
    SELECT id INTO v_tpl_id
    FROM template
    WHERE name = '报价标准模板-Excel基础结构 v1.0'
      AND template_kind = 'QUOTATION'
    LIMIT 1;

    IF v_tpl_id IS NULL THEN
        RAISE NOTICE 'V139: 未找到模板「报价标准模板-Excel基础结构 v1.0」, 跳过绑定';
        RETURN;
    END IF;

    SELECT id INTO v_comp_id FROM component WHERE code = 'COMP-QX-COMPONENT-FEE' LIMIT 1;

    IF v_comp_id IS NULL THEN
        RAISE EXCEPTION 'V139: 组件 COMP-QX-COMPONENT-FEE 未找到, 无法绑定模板';
    END IF;

    IF EXISTS (
        SELECT 1 FROM template_component
        WHERE template_id = v_tpl_id AND component_id = v_comp_id
    ) THEN
        RAISE NOTICE 'V139: 模板已绑定组件 COMP-QX-COMPONENT-FEE, 跳过';
        RETURN;
    END IF;

    INSERT INTO template_component (
        id, template_id, component_id, tab_name, sort_order, created_at
    ) VALUES (
        gen_random_uuid(), v_tpl_id, v_comp_id, '组件费用', 7, now()
    );

    RAISE NOTICE 'V139: 模板绑定完成 → template_id=% component_id=% sort_order=7', v_tpl_id, v_comp_id;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- 6. 末尾自检报告
-- ════════════════════════════════════════════════════════════════════════════
DO $$ DECLARE
    c1 INT;
    c2 INT;
    c3 INT;
BEGIN
    SELECT COUNT(*) INTO c1 FROM basic_data_config
    WHERE sheet_name = '组成件其他费用' AND status = 'ACTIVE';

    SELECT COUNT(*) INTO c2 FROM component
    WHERE code = 'COMP-QX-COMPONENT-FEE';

    SELECT COUNT(*) INTO c3 FROM template_component tc
    JOIN template t ON tc.template_id = t.id
    WHERE t.name = '报价标准模板-Excel基础结构 v1.0';

    RAISE NOTICE 'V139 完成: sheet_config=% comp=% template_components=%', c1, c2, c3;
END $$;
