-- V94: 把同名 sheet 按 templateKind 拆成两份配置, 解决 V91/V92 改动导致的报价单导入回归
--
-- 问题: V91 改了「成品其他费用」「来料其他费用」「电镀方案」「来料BOM」4 张 sheet 的 column_letter
--       对齐 v4 核价 Excel, 但报价 Excel (V3 layout) 列布局不同 →
--       报价单导入入口上传报价 Excel 时 BV-META-01 大量列必填空报错。
--
-- 解决: 同名 sheet 允许多份配置, 按 template_kind 区分:
--   - 现有 V91/V92 修改后的 sheet 标记为 template_kind='COSTING' (供核价入口)
--   - 新建同名 sheet 用 V3 layout + template_kind='QUOTATION' (供报价单入口)
-- 配合后端 sheetConfigCache 改造 (按 templateKind 过滤选择), 两条入口互不干扰。
--
-- 注意: basic_data_config 表的 partial unique index uq_bdc_sheet_name(sheet_name) WHERE status=ACTIVE
-- (V27 创建) 阻止同名 sheet 共存, V94 必须先把它换成 (sheet_name, template_kind) 复合唯一索引,
-- 才能让 QUOTATION + COSTING 两份同名配置共存。

-- ============================================================
-- Step 0: 替换唯一索引: (sheet_name) → (sheet_name, template_kind)
-- ============================================================
DROP INDEX IF EXISTS uq_bdc_sheet_name;
CREATE UNIQUE INDEX IF NOT EXISTS uq_bdc_sheet_name_kind
    ON basic_data_config(sheet_name, template_kind)
    WHERE status = 'ACTIVE';
COMMENT ON INDEX uq_bdc_sheet_name_kind IS
    'V94: 同 sheet_name 在不同 template_kind (QUOTATION/COSTING/BOTH) 下可有独立配置';

-- ============================================================
-- Step 1: 现有 V91 修改的 4 张 sheet 改 template_kind = 'COSTING'
-- ============================================================
UPDATE basic_data_config
SET template_kind = 'COSTING',
    description = COALESCE(description, '') || ' [V94] 限定到核价导入入口 (template_kind=COSTING)',
    updated_at = now()
WHERE sheet_name IN ('成品其他费用','来料其他费用','电镀方案','来料BOM')
  AND status = 'ACTIVE'
  AND template_kind != 'COSTING';

-- ============================================================
-- Step 2: 新建 4 张同名 sheet (V3 layout + template_kind='QUOTATION')
-- ============================================================

-- 2.1 成品其他费用 (V3 layout: A/B/C/D/E/F/G)
DO $$
DECLARE v_id UUID := gen_random_uuid();
BEGIN
    -- 防重复: 已存在 (sheet_name='成品其他费用', template_kind='QUOTATION') 则跳过
    IF EXISTS (SELECT 1 FROM basic_data_config
               WHERE sheet_name='成品其他费用' AND template_kind='QUOTATION' AND status='ACTIVE') THEN
        RAISE NOTICE 'V94: 报价版「成品其他费用」(QUOTATION) 已存在, 跳过';
        RETURN;
    END IF;
    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, header_row_index, data_start_row_index,
        description, parent_config_id, join_columns, sort_order, status,
        target_table, target_discriminator, template_kind, created_at, updated_at
    ) VALUES (
        v_id, '成品其他费用', 401, 1, 2,
        '报价单基础数据 V3 版「成品其他费用」(V94 新建, template_kind=QUOTATION, 与核价版同名共存)。',
        NULL, '[]'::jsonb, 401, 'ACTIVE', 'mat_fee',
        '{"fee_type": "FINISHED_OTHER"}'::jsonb,
        'QUOTATION', now(), now()
    );
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_id, 'A', '宏丰料号', 'hf_part_no',       '宏丰料号',     'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'B', '项次',     'seq_no',           '项次',         'IDENTIFIER', 'ACTIVE', 2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'C', '要素名称', 'dim_element_name', '要素名称',     'VALUE',      'ACTIVE', 3, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'D', '值',       'fee_value',        '值',           'VALUE',      'ACTIVE', 4, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'E', '比例(%)',  'fee_ratio',        '比例(%)',      'VALUE',      'ACTIVE', 5, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'F', '货币',     'currency',         '货币',         'VALUE',      'ACTIVE', 6, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'G', '计价单位', 'price_unit',       '计价单位',     'VALUE',      'ACTIVE', 7, 'NORMAL',    false, false, now(), now());
    RAISE NOTICE 'V94: 已建报价版「成品其他费用」 (QUOTATION) configId=%', v_id;
END $$;

-- 2.2 来料其他费用 (V3 layout: A/B/C/D/E/F/G/H/I/J)
DO $$
DECLARE v_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (SELECT 1 FROM basic_data_config
               WHERE sheet_name='来料其他费用' AND template_kind='QUOTATION' AND status='ACTIVE') THEN
        RAISE NOTICE 'V94: 报价版「来料其他费用」(QUOTATION) 已存在, 跳过';
        RETURN;
    END IF;
    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, header_row_index, data_start_row_index,
        description, parent_config_id, join_columns, sort_order, status,
        target_table, target_discriminator, template_kind, created_at, updated_at
    ) VALUES (
        v_id, '来料其他费用', 402, 1, 2,
        '报价单基础数据 V3 版「来料其他费用」(V94 新建, template_kind=QUOTATION, 与核价版同名共存)。',
        NULL, '[]'::jsonb, 402, 'ACTIVE', 'mat_fee',
        '{"fee_type": "INCOMING_OTHER"}'::jsonb,
        'QUOTATION', now(), now()
    );
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_id, 'A', '宏丰料号',   'hf_part_no',              '宏丰料号',     'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'B', '项次',       'seq_no',                  '项次',         'IDENTIFIER', 'ACTIVE',  2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'C', '投入料号',   'dim_input_material_no',   '投入料号',     'IDENTIFIER', 'ACTIVE',  3, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_id, 'D', '投入料号名称','dim_input_material_name','投入料号名称', 'VALUE',      'ACTIVE',  4, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'E', '项次(内)',   'dim_sub_seq_no',          '项次(内)',     'IDENTIFIER', 'ACTIVE',  5, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_id, 'F', '要素名称',   'dim_element_name',        '要素名称',     'VALUE',      'ACTIVE',  6, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'G', '值',         'fee_value',               '值',           'VALUE',      'ACTIVE',  7, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'H', '比例(%)',    'fee_ratio',               '比例(%)',      'VALUE',      'ACTIVE',  8, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'I', '货币',       'currency',                '货币',         'VALUE',      'ACTIVE',  9, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'J', '计价单位',   'price_unit',              '计价单位',     'VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now());
    RAISE NOTICE 'V94: 已建报价版「来料其他费用」 (QUOTATION) configId=%', v_id;
END $$;

-- 2.3 电镀方案 (V3 layout: A/B/C/D/H/I/J)
DO $$
DECLARE v_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (SELECT 1 FROM basic_data_config
               WHERE sheet_name='电镀方案' AND template_kind='QUOTATION' AND status='ACTIVE') THEN
        RAISE NOTICE 'V94: 报价版「电镀方案」(QUOTATION) 已存在, 跳过';
        RETURN;
    END IF;
    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, header_row_index, data_start_row_index,
        description, parent_config_id, join_columns, sort_order, status,
        target_table, target_discriminator, template_kind, created_at, updated_at
    ) VALUES (
        v_id, '电镀方案', 403, 1, 2,
        '报价单基础数据 V3 版「电镀方案」(V94 新建, template_kind=QUOTATION, 与核价版同名共存)。',
        NULL, '[]'::jsonb, 403, 'ACTIVE', 'plating_plan',
        NULL, 'QUOTATION', now(), now()
    );
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_id, 'A', '方案编号', 'plan_code',           '方案编号',     'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'B', '版本',     'version',             '版本',         'IDENTIFIER', 'ACTIVE', 2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'C', '项次',     'seq_no',              '项次',         'IDENTIFIER', 'ACTIVE', 3, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'D', '电镀元素名称','plating_element',  '电镀元素名称', 'VALUE',      'ACTIVE', 4, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'H', '电镀面积', 'plating_area',        '电镀面积',     'VALUE',      'ACTIVE', 8, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'I', '镀层厚度', 'coating_thickness',   '镀层厚度',     'VALUE',      'ACTIVE', 9, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'J', '电镀要求', 'plating_requirement', '电镀要求',     'VALUE',      'ACTIVE',10, 'NORMAL',    false, false, now(), now());
    RAISE NOTICE 'V94: 已建报价版「电镀方案」 (QUOTATION) configId=%', v_id;
END $$;

-- 2.4 来料BOM (V3 layout: A/B/C/D/E/F/G/H/I/J, target=mat_bom + bom_type=INCOMING)
DO $$
DECLARE v_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (SELECT 1 FROM basic_data_config
               WHERE sheet_name='来料BOM' AND template_kind='QUOTATION' AND status='ACTIVE') THEN
        RAISE NOTICE 'V94: 报价版「来料BOM」(QUOTATION) 已存在, 跳过';
        RETURN;
    END IF;
    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, header_row_index, data_start_row_index,
        description, parent_config_id, join_columns, sort_order, status,
        target_table, target_discriminator, template_kind, created_at, updated_at
    ) VALUES (
        v_id, '来料BOM', 404, 1, 2,
        '报价单基础数据 V3 版「来料BOM」(V94 新建, template_kind=QUOTATION, target=mat_bom, 与核价版同名共存)。',
        NULL, '[]'::jsonb, 404, 'ACTIVE', 'mat_bom',
        '{"bom_type": "INCOMING"}'::jsonb,
        'QUOTATION', now(), now()
    );
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_id, 'A', '宏丰料号',   'hf_part_no',          '宏丰料号',         'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'B', '项次',       'seq_no',              '项次',             'IDENTIFIER', 'ACTIVE',  2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'C', '投入料号',   'input_material_no',   '投入料号',         'IDENTIFIER', 'ACTIVE',  3, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'D', '投入料号名称','input_material_name','投入料号名称',     'VALUE',      'ACTIVE',  4, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'E', '产出料号类型','output_material_type','产出料号类型',    'VALUE',      'ACTIVE',  5, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'F', '材料毛重',   'gross_qty',           '材料毛重',         'VALUE',      'ACTIVE',  6, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'G', '材料净重',   'net_qty',             '材料净重',         'VALUE',      'ACTIVE',  7, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'H', '重量单位',   'gross_unit',          '重量单位',         'VALUE',      'ACTIVE',  8, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'I', '损耗率(%)',  'loss_rate',           '损耗率(%)',        'VALUE',      'ACTIVE',  9, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'J', '不良率(%)',  'defect_rate',         '不良率(%)',        'VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now());
    RAISE NOTICE 'V94: 已建报价版「来料BOM」 (QUOTATION, target=mat_bom) configId=%', v_id;
END $$;
