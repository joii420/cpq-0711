-- V91: 把 4 张 BDC sheet 的 attribute column_letter 对齐到 4.0 版 Excel 模板
--
-- 来源: data/template/核价系统功能基础数据功能结构所需字段（4.0版）.xlsx
--
-- 问题: 这 4 张 sheet 的现有 column_letter 是早期占位注册(V58_5/V64), 未对照 4.0 版
-- Excel 真实列布局。导致 v4 上传时读到错位数据(如 currency 读到 'XXXXX'/'要素名称',
-- coating_thickness 读到空白, loss_rate 把"组成用量 -10.7" 当损耗率等)。
--
-- 修复: 4 张 sheet 重建 attributes (DELETE + INSERT), 按 v4 真实列字母重新映射。
-- 不动 sheet_name / target_table / target_discriminator, 只重写 attribute 行。
--
-- 涉及 4 张 sheet:
--   1. 来料BOM (mat_bom, bom_type=INCOMING)
--   2. 电镀方案 (plating_plan)
--   3. 成品其他费用 (mat_fee, fee_type=FINISHED_OTHER)
--   4. 来料其他费用 (mat_fee, fee_type=INCOMING_OTHER)
--
-- 业务影响: 改后这 4 张 sheet 只接受 v4 布局的 Excel 上传。如果有其它格式
-- (V3 模板等) 仍在用, 需要单独再注册或恢复 V58_5 旧布局。

-- ============================================================
-- 1. 来料BOM (v4 16 列, 系统 mat_bom 表只取以下子集)
--    v4 列: A=宏丰料号 B=项次 C=来料料号 D=品名 E=规格 F=尺寸 G=工序编号
--          H=工序名称 I=组成用量 J=组成用量单位 K=底数 L=底数单位
--          M=来料损耗率(%) N=材料固定损耗量 O=不良率(%) P=计算类型
--    DB mat_bom 字段: hf_part_no/seq_no/input_material_no/input_material_name/
--          loss_rate/gross_qty/net_qty/gross_unit/net_unit/output_material_type/defect_rate
--    映射策略: 只读 DB 有对应字段的列, 其它 v4 列(品名/规格/尺寸/工序号/工序名称/
--          材料固定损耗量/计算类型)跳过(not part of mat_bom)
-- ============================================================
DO $$
DECLARE v_id UUID;
BEGIN
    SELECT id INTO v_id FROM basic_data_config
    WHERE sheet_name = '来料BOM' AND status = 'ACTIVE' LIMIT 1;
    IF v_id IS NULL THEN RAISE NOTICE 'V91: 来料BOM 不存在, 跳过'; RETURN; END IF;

    DELETE FROM basic_data_attribute WHERE config_id = v_id;
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_id, 'A', '宏丰料号',     'hf_part_no',          '宏丰料号',          'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'B', '项次',         'seq_no',              '项次',              'IDENTIFIER', 'ACTIVE',  2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'C', '来料料号',     'input_material_no',   '来料料号',          'IDENTIFIER', 'ACTIVE',  3, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'D', '品名',         'input_material_name', '品名',              'VALUE',      'ACTIVE',  4, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'I', '组成用量',     'gross_qty',           '组成用量',          'VALUE',      'ACTIVE',  9, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'J', '组成用量单位', 'gross_unit',          '组成用量单位',      'VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'K', '底数',         'net_qty',             '底数',              'VALUE',      'ACTIVE', 11, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'L', '底数单位',     'net_unit',            '底数单位',          'VALUE',      'ACTIVE', 12, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'M', '来料损耗率(%)','loss_rate',           '来料损耗率(%)',     'VALUE',      'ACTIVE', 13, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'O', '不良率(%)',    'defect_rate',         '不良率(%)',         'VALUE',      'ACTIVE', 15, 'NORMAL',    false, false, now(), now());
    RAISE NOTICE 'V91: 来料BOM 重建 10 个 attribute';
END $$;

-- ============================================================
-- 2. 电镀方案 (v4 8 列)
--    v4 列: A=方案编号 B=版本 C=项次 D=电镀元素名称 E=电镀面积 F=镀层厚度 G=电镀要求 H=密度
--    plating_plan DB 字段: plan_code/version/seq_no/plating_element/plating_area/
--          coating_thickness/plating_requirement (无 density)
-- ============================================================
DO $$
DECLARE v_id UUID;
BEGIN
    SELECT id INTO v_id FROM basic_data_config
    WHERE sheet_name = '电镀方案' AND status = 'ACTIVE' LIMIT 1;
    IF v_id IS NULL THEN RAISE NOTICE 'V91: 电镀方案 不存在, 跳过'; RETURN; END IF;

    DELETE FROM basic_data_attribute WHERE config_id = v_id;
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_id, 'A', '方案编号',     'plan_code',           '方案编号',          'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'B', '版本',         'version',             '版本',              'IDENTIFIER', 'ACTIVE', 2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'C', '项次',         'seq_no',              '项次',              'IDENTIFIER', 'ACTIVE', 3, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'D', '电镀元素名称', 'plating_element',     '电镀元素名称',      'VALUE',      'ACTIVE', 4, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'E', '电镀面积',     'plating_area',        '电镀面积(cm²)',     'VALUE',      'ACTIVE', 5, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'F', '镀层厚度',     'coating_thickness',   '镀层厚度(μm)',      'VALUE',      'ACTIVE', 6, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'G', '电镀要求',     'plating_requirement', '电镀要求',          'VALUE',      'ACTIVE', 7, 'NORMAL',    false, false, now(), now());
    RAISE NOTICE 'V91: 电镀方案 重建 7 个 attribute';
END $$;

-- ============================================================
-- 3. 成品其他费用 (v4 8 列)
--    v4 列: A=宏丰料号 B=品名 C=规格 D=尺寸 E=项次 F=要素编号 G=要素名称 H=比例(%)
--    mat_fee 字段映射:
--          A → hf_part_no, E → seq_no, G → dim_element_name, H → fee_ratio
--          F (要素编号 XXXXX 占位符) 不读, fee_value/currency/price_unit 不读 (v4 无此列)
-- ============================================================
DO $$
DECLARE v_id UUID;
BEGIN
    SELECT id INTO v_id FROM basic_data_config
    WHERE sheet_name = '成品其他费用' AND status = 'ACTIVE' LIMIT 1;
    IF v_id IS NULL THEN RAISE NOTICE 'V91: 成品其他费用 不存在, 跳过'; RETURN; END IF;

    DELETE FROM basic_data_attribute WHERE config_id = v_id;
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_id, 'A', '宏丰料号', 'hf_part_no',       '宏丰料号',     'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'E', '项次',     'seq_no',           '项次',         'IDENTIFIER', 'ACTIVE', 5, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'G', '要素名称', 'dim_element_name', '要素名称',     'VALUE',      'ACTIVE', 7, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'H', '比例(%)',  'fee_ratio',        '比例(%)',      'VALUE',      'ACTIVE', 8, 'CRITICAL',  true,  true,  now(), now());
    RAISE NOTICE 'V91: 成品其他费用 重建 4 个 attribute';
END $$;

-- ============================================================
-- 4. 来料其他费用 (v4 10 列)
--    v4 列: A=宏丰料号 B=一级项次 C=来料料号 D=品名 E=规格 F=尺寸
--          G=二级项次 H=要素编号 I=要素名称 J=比例(%)
--    mat_fee 字段映射:
--          A → hf_part_no, B → seq_no(一级), C → dim_input_material_no, D → dim_input_material_name
--          G → dim_sub_seq_no, I → dim_element_name, J → fee_ratio
--          E/F (规格/尺寸) 不读, H (要素编号 XXXXX) 不读, fee_value/currency/price_unit 不读
-- ============================================================
DO $$
DECLARE v_id UUID;
BEGIN
    SELECT id INTO v_id FROM basic_data_config
    WHERE sheet_name = '来料其他费用' AND status = 'ACTIVE' LIMIT 1;
    IF v_id IS NULL THEN RAISE NOTICE 'V91: 来料其他费用 不存在, 跳过'; RETURN; END IF;

    DELETE FROM basic_data_attribute WHERE config_id = v_id;
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_id, 'A', '宏丰料号',   'hf_part_no',              '宏丰料号',     'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'B', '一级项次',   'seq_no',                  '一级项次',     'IDENTIFIER', 'ACTIVE',  2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'C', '来料料号',   'dim_input_material_no',   '来料料号',     'IDENTIFIER', 'ACTIVE',  3, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'D', '品名',       'dim_input_material_name', '品名',         'VALUE',      'ACTIVE',  4, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'G', '二级项次',   'dim_sub_seq_no',          '二级项次',     'IDENTIFIER', 'ACTIVE',  7, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'I', '要素名称',   'dim_element_name',        '要素名称',     'VALUE',      'ACTIVE',  9, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'J', '比例(%)',    'fee_ratio',               '比例(%)',      'VALUE',      'ACTIVE', 10, 'CRITICAL',  true,  true,  now(), now());
    RAISE NOTICE 'V91: 来料其他费用 重建 7 个 attribute';
END $$;
