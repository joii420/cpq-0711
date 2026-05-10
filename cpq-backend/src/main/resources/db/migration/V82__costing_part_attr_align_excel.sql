-- V82: 8 张料号级表 attribute label 与原核价 Excel 对齐 + 补缺失字段
--
-- 背景:
--   V79 注册的 attribute 标签是按 Java entity 字段命名的(如"工艺次数 process_count"),
--   但导入 Excel 中实际叫"寿命（次）"。用户在 PathPickerDrawer 选字段时跟 Excel 对不上。
--
-- 实施 (Step 1+2(B)):
--   * Step 1 — column_title 改为 Excel 原始中文标题; 补 currency / unit / is_active /
--     ref_calc_version 等遗漏字段
--   * Step 2(B) — column_letter 保持本表紧凑顺序 (A/B/C...不跳号); variable_label 加
--     ` · Excel X` 后缀提示原 Excel 列 (PathPicker 显示样例:
--     `寿命（次） · Excel J (process_count) [列 G]`)
--
-- 不动 DB 字段名 / 不改 Java entity / 不影响公式与已存数据 — 仅修 basic_data_attribute。
-- BNF 路径用的是 variable_code，与 DB 列名一致 — 现有路径配置不会失效。
--
-- 8 张表对照 Excel sheet:
--   costing_part_process_cost   <- 8 个 sheet (人工/折旧/能耗x2/耗材/材料加工/半品/后道) 共用
--   costing_part_tooling_cost   <- "模具工装成本"
--   costing_part_material_bom   <- "来料BOM"
--   costing_part_element_bom    <- "来料与元素BOM"
--   costing_part_quality_check  <- (Excel 无专用 sheet, 保持现状)
--   costing_part_plating        <- "电镀方案" (字段都是方案级)
--   costing_part_design_cost    <- (Excel 无专用 sheet, 保持现状)
--   costing_part_weight         <- "单重"

-- ========================================================
-- 通用做法: 先把现有 attribute 全部 disable, 然后重新 INSERT 一批新的
-- 目的: 保留 audit, 但避免 update 风险 (uq_bda_config_var on (config_id, variable_code))
-- ========================================================

-- 注: variable_code 不变, 所以现有 BNF 路径 (...table.field_code) 仍可用
-- 我们 UPDATE 现有行的 column_title / variable_label / column_letter / sort_order

-- ========================================================
-- 1) costing_part_tooling_cost — "模具工装成本"
--    Excel 列: A料号 B品名 C规格 D尺寸 E工序编号 F工序名称 G项次 H模具台账/工装编号
--             I单个模具/工装成本 J寿命(次) K单循环产量 L模具工装成本单价 M币种 N计量单位 O是否有效
-- ========================================================
DO $$
DECLARE
    cfg_id UUID;
BEGIN
    SELECT id INTO cfg_id FROM basic_data_config WHERE target_table='costing_part_tooling_cost' AND status='ACTIVE' LIMIT 1;
    IF cfg_id IS NULL THEN RETURN; END IF;
    DELETE FROM basic_data_attribute WHERE config_id = cfg_id;
    INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, status, sort_order, importance_level, affects_calculation, is_required, created_at, updated_at)
    VALUES
      (gen_random_uuid(), cfg_id, 'A', '宏丰料号',         'hf_part_no',        '宏丰料号 · Excel A',           'IDENTIFIER', 'ACTIVE',  1, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'B', '工序编号',         'process_no',        '工序编号 · Excel E',           'IDENTIFIER', 'ACTIVE',  2, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'C', '工序名称',         'process_name',      '工序名称 · Excel F',           'VALUE',      'ACTIVE',  3, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'D', '项次',             'seq_no',            '项次 · Excel G',               'VALUE',      'ACTIVE',  4, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'E', '模具台账/工装编号','tooling_no',        '模具台账/工装编号 · Excel H',  'IDENTIFIER', 'ACTIVE',  5, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'F', '单个模具/工装成本','tooling_unit_cost', '单个模具/工装成本 · Excel I',  'VALUE',      'ACTIVE',  6, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'G', '寿命（次）',       'process_count',     '寿命（次） · Excel J',         'VALUE',      'ACTIVE',  7, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'H', '单循环产量',       'cycle_count',       '单循环产量 · Excel K',         'VALUE',      'ACTIVE',  8, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'I', '模具工装成本单价', 'unit_price',        '模具工装成本单价 · Excel L (自动= I/J/K)', 'VALUE', 'ACTIVE',  9, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'J', '币种',             'currency',          '币种 · Excel M',               'VALUE',      'ACTIVE', 10, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'K', '计量单位',         'unit',              '计量单位 · Excel N',           'VALUE',      'ACTIVE', 11, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'L', '是否有效',         'is_active',         '是否有效 · Excel O',           'VALUE',      'ACTIVE', 12, 'NORMAL', false, false, now(), now());
END $$;

-- ========================================================
-- 2) costing_part_process_cost — 8 个工序 sheet 共用
--    主流 sheet 列: A料号 B品名 C规格 D尺寸 E工序编号 F工序名称 G[各类]单价 H币种 I计量单位 J取用的计算版本 K是否有效
-- ========================================================
DO $$
DECLARE
    cfg_id UUID;
BEGIN
    SELECT id INTO cfg_id FROM basic_data_config WHERE target_table='costing_part_process_cost' AND status='ACTIVE' LIMIT 1;
    IF cfg_id IS NULL THEN RETURN; END IF;
    DELETE FROM basic_data_attribute WHERE config_id = cfg_id;
    INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, status, sort_order, importance_level, affects_calculation, is_required, created_at, updated_at)
    VALUES
      (gen_random_uuid(), cfg_id, 'A', '宏丰料号',       'hf_part_no',       '宏丰料号 · Excel A',       'IDENTIFIER', 'ACTIVE',  1, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'B', '工序编号',       'process_no',       '工序编号 · Excel E',       'IDENTIFIER', 'ACTIVE',  2, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'C', '工序名称',       'process_name',     '工序名称 · Excel F',       'VALUE',      'ACTIVE',  3, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'D', '成本类型',       'cost_type',        '成本类型 (内部 discriminator,无 Excel 列)', 'IDENTIFIER', 'ACTIVE', 4, 'CRITICAL', true, true, now(), now()),
      (gen_random_uuid(), cfg_id, 'E', '单价',           'unit_price',       '单价 · Excel G',           'VALUE',      'ACTIVE',  5, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'F', '币种',           'currency',         '币种 · Excel H',           'VALUE',      'ACTIVE',  6, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'G', '计量单位',       'unit',             '计量单位 · Excel I',       'VALUE',      'ACTIVE',  7, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'H', '取用的计算版本', 'ref_calc_version', '取用的计算版本 · Excel J', 'VALUE',      'ACTIVE',  8, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'I', '是否有效',       'is_active',        '是否有效 · Excel K',       'VALUE',      'ACTIVE',  9, 'NORMAL', false, false, now(), now());
END $$;

-- ========================================================
-- 3) costing_part_material_bom — "来料BOM"
--    Excel 列: A料号 B项次 C来料料号 D品名 E规格 F尺寸 G工序编号 H工序名称 I组成用量 J组成用量单位
--             K底数 L底数单位 M来料损耗率(%) N材料固定损耗量 O不良率(%)
-- ========================================================
DO $$
DECLARE
    cfg_id UUID;
BEGIN
    SELECT id INTO cfg_id FROM basic_data_config WHERE target_table='costing_part_material_bom' AND status='ACTIVE' LIMIT 1;
    IF cfg_id IS NULL THEN RETURN; END IF;
    DELETE FROM basic_data_attribute WHERE config_id = cfg_id;
    INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, status, sort_order, importance_level, affects_calculation, is_required, created_at, updated_at)
    VALUES
      (gen_random_uuid(), cfg_id, 'A', '宏丰料号',       'hf_part_no',        '宏丰料号 · Excel A',         'IDENTIFIER', 'ACTIVE',  1, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'B', '项次',           'seq_no',            '项次 · Excel B',             'VALUE',      'ACTIVE',  2, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'C', '来料料号',       'input_material_no', '来料料号 · Excel C',         'IDENTIFIER', 'ACTIVE',  3, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'D', '工序编号',       'process_no',        '工序编号 · Excel G',         'IDENTIFIER', 'ACTIVE',  4, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'E', '工序名称',       'process_name',      '工序名称 · Excel H',         'VALUE',      'ACTIVE',  5, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'F', '组成用量',       'input_qty',         '组成用量 · Excel I',         'VALUE',      'ACTIVE',  6, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'G', '组成用量单位',   'input_unit',        '组成用量单位 · Excel J',     'VALUE',      'ACTIVE',  7, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'H', '底数',           'output_qty',        '底数 · Excel K',             'VALUE',      'ACTIVE',  8, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'I', '底数单位',       'output_unit',       '底数单位 · Excel L',         'VALUE',      'ACTIVE',  9, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'J', '来料损耗率（%）','output_loss_rate',  '来料损耗率（%） · Excel M',  'VALUE',      'ACTIVE', 10, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'K', '材料固定损耗量', 'fixed_loss_qty',    '材料固定损耗量 · Excel N',   'VALUE',      'ACTIVE', 11, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'L', '不良率（%）',    'loss_rate',         '不良率（%） · Excel O',      'VALUE',      'ACTIVE', 12, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'M', '是否有效',       'is_active',         '是否有效 (无 Excel 列, DB 字段)', 'VALUE',  'ACTIVE', 13, 'NORMAL', false, false, now(), now());
END $$;

-- ========================================================
-- 4) costing_part_element_bom — "来料与元素BOM"
--    Excel 列: A来料料号 B品名 C规格 D尺寸 E项次 F元素代码 G组成含量(%) H损耗率(%)
-- ========================================================
DO $$
DECLARE
    cfg_id UUID;
BEGIN
    SELECT id INTO cfg_id FROM basic_data_config WHERE target_table='costing_part_element_bom' AND status='ACTIVE' LIMIT 1;
    IF cfg_id IS NULL THEN RETURN; END IF;
    DELETE FROM basic_data_attribute WHERE config_id = cfg_id;
    INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, status, sort_order, importance_level, affects_calculation, is_required, created_at, updated_at)
    VALUES
      (gen_random_uuid(), cfg_id, 'A', '来料料号',       'input_material_no', '来料料号 · Excel A',     'IDENTIFIER', 'ACTIVE', 1, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'B', '项次',           'seq_no',            '项次 · Excel E',         'VALUE',      'ACTIVE', 2, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'C', '元素代码',       'element_code',      '元素代码 · Excel F',     'IDENTIFIER', 'ACTIVE', 3, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'D', '组成含量（%）',  'composition_pct',   '组成含量（%） · Excel G','VALUE',      'ACTIVE', 4, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'E', '损耗率（%）',    'loss_rate',         '损耗率（%） · Excel H',  'VALUE',      'ACTIVE', 5, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'F', '是否有效',       'is_active',         '是否有效 (无 Excel 列, DB 字段)', 'VALUE', 'ACTIVE', 6, 'NORMAL', false, false, now(), now());
END $$;

-- ========================================================
-- 5) costing_part_quality_check — Excel 无专用 sheet, 保持业务化命名 (无 Excel 列后缀)
-- ========================================================
DO $$
DECLARE
    cfg_id UUID;
BEGIN
    SELECT id INTO cfg_id FROM basic_data_config WHERE target_table='costing_part_quality_check' AND status='ACTIVE' LIMIT 1;
    IF cfg_id IS NULL THEN RETURN; END IF;
    DELETE FROM basic_data_attribute WHERE config_id = cfg_id;
    INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, status, sort_order, importance_level, affects_calculation, is_required, created_at, updated_at)
    VALUES
      (gen_random_uuid(), cfg_id, 'A', '宏丰料号',     'hf_part_no',        '宏丰料号',                      'IDENTIFIER', 'ACTIVE', 1, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'B', '阶段',         'stage',             '阶段 (INCOMING/SEMI_FINISHED)', 'IDENTIFIER', 'ACTIVE', 2, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'C', '一级序号',     'primary_seq_no',    '一级序号',                      'VALUE',      'ACTIVE', 3, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'D', '序号',         'seq_no',            '序号',                          'VALUE',      'ACTIVE', 4, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'E', '要件编号',     'requirement_code',  '要件编号',                      'IDENTIFIER', 'ACTIVE', 5, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'F', '要件描述',     'requirement_desc',  '要件描述',                      'VALUE',      'ACTIVE', 6, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'G', '报废率%',      'scrap_rate',        '报废率%',                       'VALUE',      'ACTIVE', 7, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'H', '是否有效',     'is_active',         '是否有效',                      'VALUE',      'ACTIVE', 8, 'NORMAL', false, false, now(), now());
END $$;

-- ========================================================
-- 6) costing_part_plating — "电镀方案" (字段都是方案级,料号+加工费费在另一表 — V77 简化)
-- ========================================================
DO $$
DECLARE
    cfg_id UUID;
BEGIN
    SELECT id INTO cfg_id FROM basic_data_config WHERE target_table='costing_part_plating' AND status='ACTIVE' LIMIT 1;
    IF cfg_id IS NULL THEN RETURN; END IF;
    DELETE FROM basic_data_attribute WHERE config_id = cfg_id;
    INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, status, sort_order, importance_level, affects_calculation, is_required, created_at, updated_at)
    VALUES
      (gen_random_uuid(), cfg_id, 'A', '方案编号',       'plating_no',         '方案编号 · 电镀方案 Excel A',     'IDENTIFIER', 'ACTIVE', 1, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'B', '版本',           'version_number',     '版本 · 电镀方案 Excel B',         'IDENTIFIER', 'ACTIVE', 2, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'C', '项次',           'seq_no',             '项次 · 电镀方案 Excel C',         'VALUE',      'ACTIVE', 3, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'D', '电镀元素名称',   'element_attr',       '电镀元素名称 · 电镀方案 Excel D', 'VALUE',      'ACTIVE', 4, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'E', '电镀面积（cm2）','plating_area_cm2',   '电镀面积（cm2） · 电镀方案 Excel E', 'VALUE',   'ACTIVE', 5, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'F', '镀层厚度（μm）', 'layer_thickness_um', '镀层厚度（μm） · 电镀方案 Excel F','VALUE',     'ACTIVE', 6, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'G', '电镀要求',       'requirement',        '电镀要求 · 电镀方案 Excel G',     'VALUE',      'ACTIVE', 7, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'H', '是否有效',       'is_active',          '是否有效',                        'VALUE',      'ACTIVE', 8, 'NORMAL', false, false, now(), now());
END $$;

-- ========================================================
-- 7) costing_part_design_cost — Excel 无专用 sheet, 业务化命名
-- ========================================================
DO $$
DECLARE
    cfg_id UUID;
BEGIN
    SELECT id INTO cfg_id FROM basic_data_config WHERE target_table='costing_part_design_cost' AND status='ACTIVE' LIMIT 1;
    IF cfg_id IS NULL THEN RETURN; END IF;
    DELETE FROM basic_data_attribute WHERE config_id = cfg_id;
    INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, status, sort_order, importance_level, affects_calculation, is_required, created_at, updated_at)
    VALUES
      (gen_random_uuid(), cfg_id, 'A', '宏丰料号',     'hf_part_no',          '宏丰料号',         'IDENTIFIER', 'ACTIVE', 1, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'B', '设计图编号',   'design_drawing_no',   '设计图编号',       'IDENTIFIER', 'ACTIVE', 2, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'C', '版本',         'version_number',      '版本',             'IDENTIFIER', 'ACTIVE', 3, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'D', '设计加工费',   'design_proc_fee',     '设计加工费',       'VALUE',      'ACTIVE', 4, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'E', '设计材料费',   'design_material_fee', '设计材料费',       'VALUE',      'ACTIVE', 5, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'F', '币种',         'currency',            '币种',             'VALUE',      'ACTIVE', 6, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'G', '计量单位',     'unit',                '计量单位',         'VALUE',      'ACTIVE', 7, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'H', '损耗率%',      'loss_rate',           '损耗率%',          'VALUE',      'ACTIVE', 8, 'NORMAL', false, false, now(), now()),
      (gen_random_uuid(), cfg_id, 'I', '是否有效',     'is_active',           '是否有效',         'VALUE',      'ACTIVE', 9, 'NORMAL', false, false, now(), now());
END $$;

-- ========================================================
-- 8) costing_part_weight — "单重"
--    Excel 列: A宏丰料号 B单重(g/pcs)
-- ========================================================
DO $$
DECLARE
    cfg_id UUID;
BEGIN
    SELECT id INTO cfg_id FROM basic_data_config WHERE target_table='costing_part_weight' AND status='ACTIVE' LIMIT 1;
    IF cfg_id IS NULL THEN RETURN; END IF;
    DELETE FROM basic_data_attribute WHERE config_id = cfg_id;
    INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, status, sort_order, importance_level, affects_calculation, is_required, created_at, updated_at)
    VALUES
      (gen_random_uuid(), cfg_id, 'A', '宏丰料号',       'hf_part_no',       '宏丰料号 · Excel A',     'IDENTIFIER', 'ACTIVE', 1, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'B', '单重（g/pcs）',  'weight_g_per_pcs', '单重（g/pcs） · Excel B','VALUE',      'ACTIVE', 2, 'NORMAL', false, true,  now(), now()),
      (gen_random_uuid(), cfg_id, 'C', '是否有效',       'is_active',        '是否有效',               'VALUE',      'ACTIVE', 3, 'NORMAL', false, false, now(), now());
END $$;

-- ========================================================
-- 也顺手对齐 sheet_name 让用户在下拉里更眼熟 (V79 注册的"核价-料号XX" -> Excel sheet 原名)
-- ========================================================
UPDATE basic_data_config SET sheet_name='核价-模具工装成本',  updated_at=now() WHERE target_table='costing_part_tooling_cost'  AND status='ACTIVE';
UPDATE basic_data_config SET sheet_name='核价-工序成本(8类)', updated_at=now() WHERE target_table='costing_part_process_cost'  AND status='ACTIVE';
UPDATE basic_data_config SET sheet_name='核价-来料BOM',       updated_at=now() WHERE target_table='costing_part_material_bom' AND status='ACTIVE';
UPDATE basic_data_config SET sheet_name='核价-来料与元素BOM', updated_at=now() WHERE target_table='costing_part_element_bom'  AND status='ACTIVE';
UPDATE basic_data_config SET sheet_name='核价-单重',          updated_at=now() WHERE target_table='costing_part_weight'      AND status='ACTIVE';
UPDATE basic_data_config SET sheet_name='核价-电镀方案',      updated_at=now() WHERE target_table='costing_part_plating'      AND status='ACTIVE';
-- quality_check / design_cost 在 Excel 中无专用 sheet, 保持「核价-料号XX」原名
