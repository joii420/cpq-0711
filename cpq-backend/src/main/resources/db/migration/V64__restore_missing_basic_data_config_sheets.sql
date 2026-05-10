-- V64: 恢复用户在 UI 删除的 16 个生产 sheet + 5 个旧测试兼容 sheet 的 config 行,
-- 并对其中没有 attribute 的 sheet 重新插入标准属性映射(基于 V60/V63 标准)。
--
-- 背景:
--   - V63 修复了已存在 sheet 的 attribute variable_code(VAR_xxx → 物理列名)
--   - 但如果用户在 UI 上删除了 sheet config 行,V63 的 SELECT id INTO cfg_id 拿不到行,
--     attribute 不会插入(silent skip),导致 sheetConfigCache 没该 sheet → 测试解析失败
--   - V64 先恢复这些 sheet config 行,再对每个新插入的 sheet 重新 seed attribute
--
-- 此 migration 幂等:已存在的 sheet 不会重复插入(ON CONFLICT DO NOTHING),
-- attribute 用 NOT EXISTS 防止重复插入(已有 V60/V63 标准属性的 sheet 会被跳过)。

-- ════════════════════════════════════════════════════════════════════
-- Part 1: 恢复 16 个生产 sheet + 5 个旧测试兼容 sheet 的 config 行
-- ════════════════════════════════════════════════════════════════════
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator) VALUES
  ('单重',                  1, 2, '生产料号主档(GLOBAL)',      110, 'ACTIVE', 'mat_part',                    NULL),
  ('来料BOM',                1, 2, '来料BOM(GLOBAL)',           120, 'ACTIVE', 'mat_bom',                     '{"bom_type":"INCOMING"}'::jsonb),
  ('元素BOM',                1, 2, '元素BOM(GLOBAL)',           130, 'ACTIVE', 'mat_bom',                     '{"bom_type":"ELEMENT"}'::jsonb),
  ('组成件BOM及单价',         1, 2, '工艺基础(CUSTOMER)',         140, 'ACTIVE', 'mat_process',                 NULL),
  ('来料固定加工费',          1, 2, '来料固定加工费(CUSTOMER)',   150, 'ACTIVE', 'mat_fee',                     '{"fee_type":"INCOMING_FIXED"}'::jsonb),
  ('来料其他费用',            1, 2, '来料其他费用(CUSTOMER)',     160, 'ACTIVE', 'mat_fee',                     '{"fee_type":"INCOMING_OTHER"}'::jsonb),
  ('成品固定加工费',          1, 2, '成品固定加工费(CUSTOMER)',   170, 'ACTIVE', 'mat_fee',                     '{"fee_type":"FINISHED_FIXED"}'::jsonb),
  ('成品其他费用',            1, 2, '成品其他费用(CUSTOMER)',     180, 'ACTIVE', 'mat_fee',                     '{"fee_type":"FINISHED_OTHER"}'::jsonb),
  ('来料年降',                1, 2, '来料年降(CUSTOMER)',         190, 'ACTIVE', 'mat_fee',                     '{"fee_type":"INCOMING_ANNUAL_DOWN"}'::jsonb),
  ('组装加工费',              1, 2, '组装加工费(CUSTOMER)',       200, 'ACTIVE', 'mat_fee',                     '{"fee_type":"ASSEMBLY_PROCESS"}'::jsonb),
  ('组装加工费年降',          1, 2, '组装加工费年降(CUSTOMER)',   210, 'ACTIVE', 'mat_fee',                     '{"fee_type":"ASSEMBLY_ANNUAL_DOWN"}'::jsonb),
  ('年降系数',                1, 2, '年降系数(CUSTOMER)',         220, 'ACTIVE', 'mat_fee',                     '{"fee_type":"ANNUAL_REDUCTION_FACTOR"}'::jsonb),
  ('电镀费用',                1, 2, '电镀费用(CUSTOMER)',         230, 'ACTIVE', 'plating_fee',                 NULL),
  ('电镀方案',                1, 2, '电镀方案(GLOBAL)',           240, 'ACTIVE', 'plating_plan',                NULL),
  ('客户料号与宏丰料号的关系', 1, 2, '客户料号对照(CUSTOMER)',     250, 'ACTIVE', 'mat_customer_part_mapping',   NULL),
  -- 旧测试兼容
  ('料号主档',                1, 2, '旧测试兼容:mat_part(GLOBAL)',          310, 'ACTIVE', 'mat_part',                    NULL),
  ('BOM清单',                 1, 2, '旧测试兼容:mat_bom(GLOBAL)',           320, 'ACTIVE', 'mat_bom',                     NULL),
  ('组成件BOM',               1, 2, '旧测试兼容:mat_process(CUSTOMER)',     330, 'ACTIVE', 'mat_process',                 NULL),
  ('费用清单',                1, 2, '旧测试兼容:mat_fee(CUSTOMER)',         340, 'ACTIVE', 'mat_fee',                     NULL),
  ('客户料号映射',            1, 2, '旧测试兼容:mat_customer_part_mapping', 350, 'ACTIVE', 'mat_customer_part_mapping',   NULL)
ON CONFLICT DO NOTHING;

-- 如果 sheet 已存在但 target_table 被用户改成 NULL,UPDATE 修正
UPDATE basic_data_config SET target_table = 'mat_part',                    target_discriminator = NULL                                              WHERE sheet_name = '单重'                  AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_bom',                     target_discriminator = '{"bom_type":"INCOMING"}'::jsonb                  WHERE sheet_name = '来料BOM'               AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_bom',                     target_discriminator = '{"bom_type":"ELEMENT"}'::jsonb                   WHERE sheet_name = '元素BOM'               AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_process',                 target_discriminator = NULL                                              WHERE sheet_name = '组成件BOM及单价'        AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"INCOMING_FIXED"}'::jsonb            WHERE sheet_name = '来料固定加工费'        AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"INCOMING_OTHER"}'::jsonb            WHERE sheet_name = '来料其他费用'          AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"FINISHED_FIXED"}'::jsonb            WHERE sheet_name = '成品固定加工费'        AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"FINISHED_OTHER"}'::jsonb            WHERE sheet_name = '成品其他费用'          AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"INCOMING_ANNUAL_DOWN"}'::jsonb      WHERE sheet_name = '来料年降'              AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"ASSEMBLY_PROCESS"}'::jsonb          WHERE sheet_name = '组装加工费'            AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"ASSEMBLY_ANNUAL_DOWN"}'::jsonb      WHERE sheet_name = '组装加工费年降'        AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = '{"fee_type":"ANNUAL_REDUCTION_FACTOR"}'::jsonb   WHERE sheet_name = '年降系数'              AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'plating_fee',                 target_discriminator = NULL                                              WHERE sheet_name = '电镀费用'              AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'plating_plan',                target_discriminator = NULL                                              WHERE sheet_name = '电镀方案'              AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_customer_part_mapping',   target_discriminator = NULL                                              WHERE sheet_name = '客户料号与宏丰料号的关系' AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_part',                    target_discriminator = NULL                                              WHERE sheet_name = '料号主档'              AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_bom',                     target_discriminator = NULL                                              WHERE sheet_name = 'BOM清单'               AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_process',                 target_discriminator = NULL                                              WHERE sheet_name = '组成件BOM'             AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_fee',                     target_discriminator = NULL                                              WHERE sheet_name = '费用清单'              AND target_table IS NULL;
UPDATE basic_data_config SET target_table = 'mat_customer_part_mapping',   target_discriminator = NULL                                              WHERE sheet_name = '客户料号映射'          AND target_table IS NULL;

-- ════════════════════════════════════════════════════════════════════
-- Part 2: 对没有任何 attribute 的 sheet 插入 V60/V63 标准属性映射
-- (V63 只对已存在的 sheet 生效,V64 Part 1 新插入的 sheet 还没 attribute,
--  这里检测后补 seed)
-- ════════════════════════════════════════════════════════════════════

-- 对每个 sheet,如果没 attribute 则 seed:用 EXISTS 守门,跑过 V63 已有 attribute 的会跳过

-- 单重
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '单重' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '料号',         'part_no',     '料号',         'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '单重(g/pcs)',  'unit_weight', '单重(g/pcs)',  'VALUE',      20, 'ACTIVE', true);
  END IF;
END $$;

-- 来料BOM
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '来料BOM' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',      'hf_part_no',           '宏丰料号',      'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '项次',          'seq_no',               '项次',          'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '投入料号',      'input_material_no',    '投入料号',      'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '投入料号名称',  'input_material_name',  '投入料号名称',  'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '产出料号类型',  'output_material_type', '产出料号类型',  'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '材料毛重',      'gross_qty',            '材料毛重',      'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '材料净重',      'net_qty',              '材料净重',      'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '重量单位',      'gross_unit',           '重量单位',      'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '损耗率(%)',     'loss_rate',            '损耗率(%)',     'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '不良率(%)',     'defect_rate',          '不良率(%)',     'VALUE',      100, 'ACTIVE', false);
  END IF;
END $$;

-- 元素BOM
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '元素BOM' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',      'hf_part_no',          '宏丰料号',      'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '投入料号',      'input_material_no',   '投入料号',      'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '投入料号名称',  'input_material_name', '投入料号名称',  'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '项次',          'seq_no',              '项次',          'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '元素',          'element_name',        '元素',          'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '组成含量(%)',   'composition_pct',     '组成含量(%)',   'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '损耗率%',       'loss_rate',           '损耗率%',       'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '毛用量',        'gross_qty',           '毛用量',        'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '毛用量单位',    'gross_unit',          '毛用量单位',    'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '净用量',        'net_qty',             '净用量',        'VALUE',      100, 'ACTIVE', false),
      (cfg_id, 'K', '净用量单位',    'net_unit',            '净用量单位',    'VALUE',      110, 'ACTIVE', false);
  END IF;
END $$;

-- 组成件BOM及单价
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '组成件BOM及单价' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',     'hf_part_no',        '宏丰料号',     'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '项次',         'seq_no',            '项次',         'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '工序编号',     'process_code',      '工序编号',     'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '组装工序',     'assembly_process',  '组装工序',     'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '组成件项次',   'sub_seq_no',        '组成件项次',   'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '组成件料号',   'component_part_no', '组成件料号',   'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '组成件名称',   'component_name',    '组成件名称',   'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '供应商编号',   'supplier_code',     '供应商编号',   'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '供应商名称',   'supplier_name',     '供应商名称',   'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '组成数量',     'quantity',          '组成数量',     'VALUE',      100, 'ACTIVE', false),
      (cfg_id, 'K', '组成单位',     'quantity_unit',     '组成单位',     'VALUE',      110, 'ACTIVE', false),
      (cfg_id, 'L', '单价',         'unit_price',        '单价',         'VALUE',      120, 'ACTIVE', false),
      (cfg_id, 'M', '运费',         'freight',           '运费',         'VALUE',      130, 'ACTIVE', false),
      (cfg_id, 'N', '货币',         'currency',          '货币',         'VALUE',      140, 'ACTIVE', false),
      (cfg_id, 'O', '计价单位',     'price_unit',        '计价单位',     'VALUE',      150, 'ACTIVE', false);
  END IF;
END $$;

-- 来料固定加工费
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '来料固定加工费' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',                'hf_part_no',              '宏丰料号',                'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '项次',                    'seq_no',                  '项次',                    'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '投入料号',                'dim_input_material_no',   '投入料号',                'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '投入料号名称',            'dim_input_material_name', '投入料号名称',            'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '值',                      'fee_value',               '值',                      'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '比例(%)',                 'fee_ratio',               '比例(%)',                 'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '货币',                    'currency',                '货币',                    'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '计价单位',                'price_unit',              '计价单位',                'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '是否随材料价格波动',      'price_floating',          '是否随材料价格波动',      'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '材料结算涨幅比例(%)',     'settlement_rise_ratio',   '材料结算涨幅比例(%)',     'VALUE',      100, 'ACTIVE', false),
      (cfg_id, 'K', '材料固定的涨幅值',        'fixed_rise_value',        '材料固定的涨幅值',        'VALUE',      110, 'ACTIVE', false),
      (cfg_id, 'L', '货币(涨幅)',              'rise_currency',           '货币(涨幅)',              'VALUE',      120, 'ACTIVE', false),
      (cfg_id, 'M', '涨幅单位',                'rise_unit',               '涨幅单位',                'VALUE',      130, 'ACTIVE', false);
  END IF;
END $$;

-- 来料其他费用
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '来料其他费用' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',     'hf_part_no',              '宏丰料号',     'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '项次',         'seq_no',                  '项次',         'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '投入料号',     'dim_input_material_no',   '投入料号',     'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '投入料号名称', 'dim_input_material_name', '投入料号名称', 'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '项次(内)',     'dim_sub_seq_no',          '项次(内)',     'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '要素名称',     'dim_element_name',        '要素名称',     'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '值',           'fee_value',               '值',           'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '比例(%)',      'fee_ratio',               '比例(%)',      'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '货币',         'currency',                '货币',         'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '计价单位',     'price_unit',              '计价单位',     'VALUE',      100, 'ACTIVE', false);
  END IF;
END $$;

-- 成品固定加工费
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '成品固定加工费' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号', 'hf_part_no', '宏丰料号', 'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '项次',     'seq_no',     '项次',     'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '值',       'fee_value',  '值',       'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '比例(%)',  'fee_ratio',  '比例(%)',  'VALUE',      40, 'ACTIVE', false),
      (cfg_id, 'E', '货币',     'currency',   '货币',     'VALUE',      50, 'ACTIVE', false),
      (cfg_id, 'F', '计价单位', 'price_unit', '计价单位', 'VALUE',      60, 'ACTIVE', false);
  END IF;
END $$;

-- 成品其他费用
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '成品其他费用' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号', 'hf_part_no',       '宏丰料号', 'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '项次',     'seq_no',           '项次',     'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '要素名称', 'dim_element_name', '要素名称', 'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '值',       'fee_value',        '值',       'VALUE',      40, 'ACTIVE', false),
      (cfg_id, 'E', '比例(%)',  'fee_ratio',        '比例(%)',  'VALUE',      50, 'ACTIVE', false),
      (cfg_id, 'F', '货币',     'currency',         '货币',     'VALUE',      60, 'ACTIVE', false),
      (cfg_id, 'G', '计价单位', 'price_unit',       '计价单位', 'VALUE',      70, 'ACTIVE', false);
  END IF;
END $$;

-- 来料年降
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '来料年降' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',         'hf_part_no',              '宏丰料号',         'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '项次',             'seq_no',                  '项次',             'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '投入料号',         'dim_input_material_no',   '投入料号',         'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '投入料号名称',     'dim_input_material_name', '投入料号名称',     'VALUE',      40, 'ACTIVE', false),
      (cfg_id, 'E', '年降顺序',         'dim_sub_seq_no',          '年降顺序',         'VALUE',      50, 'ACTIVE', false),
      (cfg_id, 'F', '年降系数(%)',      'fee_ratio',               '年降系数(%)',      'VALUE',      60, 'ACTIVE', false),
      (cfg_id, 'G', '单次固定年降值',   'fee_value',               '单次固定年降值',   'VALUE',      70, 'ACTIVE', false),
      (cfg_id, 'H', '货币',             'currency',                '货币',             'VALUE',      80, 'ACTIVE', false),
      (cfg_id, 'I', '计价单位',         'price_unit',              '计价单位',         'VALUE',      90, 'ACTIVE', false);
  END IF;
END $$;

-- 组装加工费
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '组装加工费' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号', 'hf_part_no',           '宏丰料号', 'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '项次',     'seq_no',               '项次',     'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '组装工序', 'dim_assembly_process', '组装工序', 'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '组装加工费','fee_value',           '组装加工费','VALUE',     40, 'ACTIVE', false),
      (cfg_id, 'E', '货币',     'currency',             '货币',     'VALUE',      50, 'ACTIVE', false),
      (cfg_id, 'F', '计价单位', 'price_unit',           '计价单位', 'VALUE',      60, 'ACTIVE', false),
      (cfg_id, 'G', '拒收率',   'reject_rate',          '拒收率',   'VALUE',      70, 'ACTIVE', false);
  END IF;
END $$;

-- 组装加工费年降
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '组装加工费年降' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',       'hf_part_no',           '宏丰料号',       'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '项次',           'seq_no',               '项次',           'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '组装工序',       'dim_assembly_process', '组装工序',       'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '年降顺序',       'dim_sub_seq_no',       '年降顺序',       'VALUE',      40, 'ACTIVE', false),
      (cfg_id, 'E', '年降系数(%)',    'fee_ratio',            '年降系数(%)',    'VALUE',      50, 'ACTIVE', false),
      (cfg_id, 'F', '单次固定年降值', 'fee_value',            '单次固定年降值', 'VALUE',      60, 'ACTIVE', false),
      (cfg_id, 'G', '货币',           'currency',             '货币',           'VALUE',      70, 'ACTIVE', false),
      (cfg_id, 'H', '计价单位',       'price_unit',           '计价单位',       'VALUE',      80, 'ACTIVE', false);
  END IF;
END $$;

-- 年降系数
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '年降系数' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',         'hf_part_no',     '宏丰料号',         'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '年降顺序',         'dim_sub_seq_no', '年降顺序',         'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '年降系数(%/年)',   'fee_ratio',      '年降系数(%/年)',   'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '单次固定年降金额', 'fee_value',      '单次固定年降金额', 'VALUE',      40, 'ACTIVE', false),
      (cfg_id, 'E', '货币',             'currency',       '货币',             'VALUE',      50, 'ACTIVE', false),
      (cfg_id, 'F', '计价单位',         'price_unit',     '计价单位',         'VALUE',      60, 'ACTIVE', false);
  END IF;
END $$;

-- 电镀方案
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '电镀方案' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '方案编号',     'plan_code',           '方案编号',     'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '版本',         'version',             '版本',         'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '项次',         'seq_no',              '项次',         'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '电镀元素名称', 'plating_element',     '电镀元素名称', 'VALUE',      40, 'ACTIVE', false),
      (cfg_id, 'H', '电镀面积',     'plating_area',        '电镀面积',     'VALUE',      80, 'ACTIVE', false),
      (cfg_id, 'I', '镀层厚度',     'coating_thickness',   '镀层厚度',     'VALUE',      90, 'ACTIVE', true),
      (cfg_id, 'J', '电镀要求',     'plating_requirement', '电镀要求',     'VALUE',      100, 'ACTIVE', false);
  END IF;
END $$;

-- 电镀费用
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '电镀费用' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',     'hf_part_no',          '宏丰料号',     'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '电镀方案编码', 'plating_plan_code',   '电镀方案编码', 'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '方案版本号',   'plan_version',        '方案版本号',   'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '电镀加工费',   'plating_process_fee', '电镀加工费',   'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '电镀材料费',   'plating_material_fee','电镀材料费',   'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '货币',         'currency',            '货币',         'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '价格单位',     'price_unit',          '价格单位',     'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '不良率',       'defect_rate',         '不良率',       'VALUE',      80,  'ACTIVE', false);
  END IF;
END $$;

-- 客户料号与宏丰料号的关系
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '客户料号与宏丰料号的关系' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '客户料号名称', 'customer_part_name',  '客户料号名称', 'VALUE',      10, 'ACTIVE', false),
      (cfg_id, 'B', '客户产品编号', 'customer_product_no', '客户产品编号', 'IDENTIFIER', 20, 'ACTIVE', true),
      (cfg_id, 'C', '客户图号',     'customer_drawing_no', '客户图号',     'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '宏丰料号',     'hf_part_no',          '宏丰料号',     'VALUE',      40, 'ACTIVE', true),
      (cfg_id, 'E', '付款方式',     'payment_method',      '付款方式',     'VALUE',      50, 'ACTIVE', false),
      (cfg_id, 'F', '基础货币',     'base_currency',       '基础货币',     'VALUE',      60, 'ACTIVE', false),
      (cfg_id, 'G', '报价货币',     'quote_currency',      '报价货币',     'VALUE',      70, 'ACTIVE', false);
  END IF;
END $$;

-- 料号主档(7 列)
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '料号主档' LIMIT 1;
  IF cfg_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM basic_data_attribute WHERE config_id = cfg_id) THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号', 'part_no',       '宏丰料号', 'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '料号名称', 'part_name',     '料号名称', 'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '规格',     'specification', '规格',     'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '尺寸信息', 'size_info',     '尺寸信息', 'VALUE',      40, 'ACTIVE', false),
      (cfg_id, 'E', '单重',     'unit_weight',   '单重',     'VALUE',      50, 'ACTIVE', true),
      (cfg_id, 'F', '重量单位', 'weight_unit',   '重量单位', 'VALUE',      60, 'ACTIVE', false),
      (cfg_id, 'G', '状态',     'status_code',   '状态',     'VALUE',      70, 'ACTIVE', false);
  END IF;
END $$;

COMMENT ON TABLE basic_data_config IS
    'V64: 恢复用户在 UI 误删的 16 个生产 sheet + 5 个旧测试兼容 sheet 的 config 行;
     对没有 attribute 的 sheet 重新 seed 物理列名作 variable_code';
