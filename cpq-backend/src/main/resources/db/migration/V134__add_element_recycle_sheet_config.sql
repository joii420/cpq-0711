-- V134: 注册「元素回收折扣」sheet 到 basic_data_config
--
-- 背景：V118 注释里已明确「元素回收折扣 sheet 当前不被 V5 识别 → 不入库; 不在本次范围」。
--      V128 已扩展 mat_fee.fee_type CHECK 约束加入 ELEMENT_RECYCLE，
--      但 basic_data_config 中始终缺少对应记录，导致 V5 import 完全忽略该 sheet。
--
-- Excel sheet「元素回收折扣」列映射：
--   A: hf_part_no              (IDENTIFIER) 宏丰料号
--   B: dim_input_material_no   (IDENTIFIER) 投入料号
--   C: dim_input_material_name (IDENTIFIER) 投入料号名称
--   D: seq_no                  (IDENTIFIER) 项次
--   E: dim_element_name        (IDENTIFIER) 元素
--   F: fee_ratio               (VALUE)      回收折扣（%）
--
-- 参考 V118 line 87-110 添加 MATERIAL_RECYCLE 配置的 INSERT 模式。
-- 幂等：DO $$ BEGIN IF NOT EXISTS ... END IF; END $$

DO $$
DECLARE
    v_config_id UUID := gen_random_uuid();
BEGIN
    -- 存在性检查（幂等保护）
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '元素回收折扣'
          AND template_kind = 'QUOTATION'
          AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V134: sheet「元素回收折扣」已存在，跳过插入';
        RETURN;
    END IF;

    -- 插入 basic_data_config 记录
    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index,
        target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index,
        sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_config_id,
        '元素回收折扣',
        405,
        'mat_fee',
        '{"fee_type":"ELEMENT_RECYCLE"}'::jsonb,
        'QUOTATION',
        1, 2,
        105, 'ACTIVE',
        'V134: 报价侧「元素回收折扣」, 物理表 mat_fee + fee_type=ELEMENT_RECYCLE; fee_ratio 字段存回收折扣百分比（toDecimalPercent 入库，视图已 x100 显示）',
        now(), now()
    );

    -- 插入 6 条 basic_data_attribute 记录
    INSERT INTO basic_data_attribute (
        id, config_id,
        column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order,
        importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
        (gen_random_uuid(), v_config_id, 'A', '宏丰料号',     'hf_part_no',              '宏丰料号',     'IDENTIFIER', 'ACTIVE', 1, 'IMPORTANT', false, true,  now(), now()),
        (gen_random_uuid(), v_config_id, 'B', '投入料号',     'dim_input_material_no',   '投入料号',     'IDENTIFIER', 'ACTIVE', 2, 'IMPORTANT', false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'C', '投入料号名称', 'dim_input_material_name', '投入料号名称', 'IDENTIFIER', 'ACTIVE', 3, 'NORMAL',    false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'D', '项次',         'seq_no',                  '项次',         'IDENTIFIER', 'ACTIVE', 4, 'IMPORTANT', false, true,  now(), now()),
        (gen_random_uuid(), v_config_id, 'E', '元素',         'dim_element_name',        '元素',         'IDENTIFIER', 'ACTIVE', 5, 'IMPORTANT', false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'F', '回收折扣(%)',  'fee_ratio',               '回收折扣',     'VALUE',      'ACTIVE', 6, 'IMPORTANT', true,  false, now(), now());

    RAISE NOTICE 'V134: 注册 sheet「元素回收折扣」成功 → config_id=%', v_config_id;
    RAISE NOTICE 'V134: 已插入 6 条 basic_data_attribute 记录（A-F 列）';
END $$;
