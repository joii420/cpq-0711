-- V63: 修复 basic_data_attribute.variable_code,把 UI 导入自动生成的 VAR_xxx_A/B/C
-- 重置为目标物理表的真实列名(基于 V60 标准映射)。
--
-- 背景:
--   - V60 (DELETE + INSERT) 已为 16 个生产 sheet 配好正确 variable_code (part_no / unit_weight 等)
--   - 用户在 UI 用「Sheet 配置 → 导入」按钮重建 sheet 后,attribute 的 variable_code
--     会被自动生成为 VAR_<config-id-prefix>_<COLUMN_LETTER>(见 BasicDataConfig.tsx L260)
--   - 这导致 BasicDataImportServiceV5 解析时 cv.get("part_no") = null,所有物理表写入失败
--
-- 修复策略:
--   - 仅针对 16 个生产 sheet name 重置(不动用户自建 sheet)
--   - 先 DELETE 该 sheet 所有 attribute,再 INSERT 标准映射(跟 V60 一致)
--   - column_title 也填回中文表头,便于用户在 UI 识别

-- ════════════════════════════════════════════════════════════════════
-- Part 0: 清理 16 个生产 sheet 的所有 attribute(以便重新插入)
-- ════════════════════════════════════════════════════════════════════
DELETE FROM basic_data_attribute WHERE config_id IN (
  SELECT id FROM basic_data_config WHERE sheet_name IN (
    '单重',
    '来料BOM',
    '元素BOM',
    '组成件BOM及单价',
    '来料固定加工费',
    '来料其他费用',
    '成品固定加工费',
    '成品其他费用',
    '来料年降',
    '组装加工费',
    '组装加工费年降',
    '年降系数',
    '电镀方案',
    '电镀费用',
    '客户料号与宏丰料号的关系',
    -- 旧测试兼容 sheet
    '料号主档',
    'BOM清单',
    '组成件BOM',
    '费用清单',
    '客户料号映射'
  )
);

-- ════════════════════════════════════════════════════════════════════
-- 1. 单重 → mat_part
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '单重' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '料号',         'part_no',     '料号',         'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '单重(g/pcs)',  'unit_weight', '单重(g/pcs)',  'VALUE',      20, 'ACTIVE', true);
  END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- 2. 来料BOM → mat_bom (INCOMING)
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '来料BOM' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 3. 元素BOM → mat_bom (ELEMENT)
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '元素BOM' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 4. 组成件BOM及单价 → mat_process
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '组成件BOM及单价' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 5. 来料固定加工费 → mat_fee (INCOMING_FIXED)
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '来料固定加工费' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 6. 来料其他费用 → mat_fee (INCOMING_OTHER)
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '来料其他费用' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 7. 成品固定加工费 → mat_fee (FINISHED_FIXED)
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '成品固定加工费' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号', 'hf_part_no', '宏丰料号', 'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '项次',     'seq_no',     '项次',     'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '值',       'fee_value',  '值',       'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '比例(%)',  'fee_ratio',  '比例(%)',  'VALUE',      40, 'ACTIVE', false),
      (cfg_id, 'E', '货币',     'currency',   '货币',     'VALUE',      50, 'ACTIVE', false),
      (cfg_id, 'F', '计价单位', 'price_unit', '计价单位', 'VALUE',      60, 'ACTIVE', false);
  END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- 8. 成品其他费用 → mat_fee (FINISHED_OTHER)
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '成品其他费用' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 9. 来料年降 → mat_fee (INCOMING_ANNUAL_DOWN)
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '来料年降' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 10. 组装加工费 → mat_fee (ASSEMBLY_PROCESS)
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '组装加工费' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 11. 组装加工费年降 → mat_fee (ASSEMBLY_ANNUAL_DOWN)
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '组装加工费年降' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 12. 年降系数 → mat_fee (ANNUAL_REDUCTION_FACTOR)
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '年降系数' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',         'hf_part_no',     '宏丰料号',         'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '年降顺序',         'dim_sub_seq_no', '年降顺序',         'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '年降系数(%/年)',   'fee_ratio',      '年降系数(%/年)',   'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '单次固定年降金额', 'fee_value',      '单次固定年降金额', 'VALUE',      40, 'ACTIVE', false),
      (cfg_id, 'E', '货币',             'currency',       '货币',             'VALUE',      50, 'ACTIVE', false),
      (cfg_id, 'F', '计价单位',         'price_unit',     '计价单位',         'VALUE',      60, 'ACTIVE', false);
  END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- 13. 电镀方案 → plating_plan
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '电镀方案' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '方案编号',     'plan_code',           '方案编号',     'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '版本',         'version',             '版本',         'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '项次',         'seq_no',              '项次',         'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '电镀元素名称', 'plating_element',     '电镀元素名称', 'VALUE',      40, 'ACTIVE', false),
      -- E/F/G v1 跳过(物理表无对应字段)
      (cfg_id, 'H', '电镀面积',     'plating_area',        '电镀面积',     'VALUE',      80, 'ACTIVE', false),
      (cfg_id, 'I', '镀层厚度',     'coating_thickness',   '镀层厚度',     'VALUE',      90, 'ACTIVE', true),
      (cfg_id, 'J', '电镀要求',     'plating_requirement', '电镀要求',     'VALUE',      100, 'ACTIVE', false);
  END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- 14. 电镀费用 → plating_fee
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '电镀费用' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 15. 客户料号与宏丰料号的关系 → mat_customer_part_mapping
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '客户料号与宏丰料号的关系' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- ════════════════════════════════════════════════════════════════════
-- 16-20. 旧测试兼容 sheet(料号主档/BOM清单/组成件BOM/费用清单/客户料号映射)
-- ════════════════════════════════════════════════════════════════════

-- 料号主档 → mat_part(7 列结构,V58_5 标准)
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '料号主档' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
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

-- BOM清单 → mat_bom(含 BOM_TYPE 列,旧测试)
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = 'BOM清单' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',     'hf_part_no',          '宏丰料号',     'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', 'BOM类型',      'bom_type',            'BOM类型',      'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', 'BOM序号',      'seq_no',              'BOM序号',      'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '来料编号',     'input_material_no',   '来料编号',     'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '来料名称',     'input_material_name', '来料名称',     'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '损耗率',       'loss_rate',           '损耗率',       'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '毛用量',       'gross_qty',           '毛用量',       'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '净用量',       'net_qty',             '净用量',       'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '毛用量单位',   'gross_unit',          '毛用量单位',   'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '净用量单位',   'net_unit',            '净用量单位',   'VALUE',      100, 'ACTIVE', false),
      (cfg_id, 'K', '产出物料类型', 'output_material_type','产出物料类型', 'VALUE',      110, 'ACTIVE', false),
      (cfg_id, 'L', '不良率',       'defect_rate',         '不良率',       'VALUE',      120, 'ACTIVE', false),
      (cfg_id, 'M', '元素名称',     'element_name',        '元素名称',     'VALUE',      130, 'ACTIVE', false),
      (cfg_id, 'N', '含量百分比',   'composition_pct',     '含量百分比',   'VALUE',      140, 'ACTIVE', false);
  END IF;
END $$;

-- 组成件BOM → mat_process(同生产 sheet「组成件BOM及单价」)
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '组成件BOM' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',   'hf_part_no',        '宏丰料号',   'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '工序序号',   'seq_no',            '工序序号',   'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '子序号',     'sub_seq_no',        '子序号',     'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '工序编码',   'process_code',      '工序编码',   'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '装配工序',   'assembly_process',  '装配工序',   'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '组成件料号', 'component_part_no', '组成件料号', 'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '组成件名称', 'component_name',    '组成件名称', 'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '供应商编码', 'supplier_code',     '供应商编码', 'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '供应商名称', 'supplier_name',     '供应商名称', 'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '数量',       'quantity',          '数量',       'VALUE',      100, 'ACTIVE', false),
      (cfg_id, 'K', '数量单位',   'quantity_unit',     '数量单位',   'VALUE',      110, 'ACTIVE', false),
      (cfg_id, 'L', '单价',       'unit_price',        '单价',       'VALUE',      120, 'ACTIVE', false),
      (cfg_id, 'M', '运费',       'freight',           '运费',       'VALUE',      130, 'ACTIVE', false),
      (cfg_id, 'N', '货币',       'currency',          '货币',       'VALUE',      140, 'ACTIVE', false),
      (cfg_id, 'O', '价格单位',   'price_unit',        '价格单位',   'VALUE',      150, 'ACTIVE', false);
  END IF;
END $$;

-- 费用清单 → mat_fee(通用结构,无 discriminator,fee_type 从列读)
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '费用清单' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '宏丰料号',         'hf_part_no',             '宏丰料号',         'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '费用类型',          'fee_type',               '费用类型',         'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '费用序号',          'seq_no',                 '费用序号',         'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '费用值',            'fee_value',              '费用值',           'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '费用比例',          'fee_ratio',              '费用比例',         'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '货币',              'currency',               '货币',             'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '价格单位',          'price_unit',             '价格单位',         'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '关联来料编号',      'dim_input_material_no',  '关联来料编号',     'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '关联来料名称',      'dim_input_material_name','关联来料名称',     'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '关联元素名称',      'dim_element_name',       '关联元素名称',     'VALUE',      100, 'ACTIVE', false),
      (cfg_id, 'K', '关联装配工序',      'dim_assembly_process',   '关联装配工序',     'VALUE',      110, 'ACTIVE', false),
      (cfg_id, 'L', '关联子序号',        'dim_sub_seq_no',         '关联子序号',       'VALUE',      120, 'ACTIVE', false),
      (cfg_id, 'M', '价格浮动',          'price_floating',         '价格浮动',         'VALUE',      130, 'ACTIVE', false),
      (cfg_id, 'N', '结算涨价比例',      'settlement_rise_ratio',  '结算涨价比例',     'VALUE',      140, 'ACTIVE', false),
      (cfg_id, 'O', '固定涨价值',        'fixed_rise_value',       '固定涨价值',       'VALUE',      150, 'ACTIVE', false),
      (cfg_id, 'P', '涨价货币',          'rise_currency',          '涨价货币',         'VALUE',      160, 'ACTIVE', false),
      (cfg_id, 'Q', '涨价单位',          'rise_unit',              '涨价单位',         'VALUE',      170, 'ACTIVE', false),
      (cfg_id, 'R', '报废率',            'reject_rate',            '报废率',           'VALUE',      180, 'ACTIVE', false);
  END IF;
END $$;

-- 客户料号映射 → mat_customer_part_mapping
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '客户料号映射' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '客户产品编号', 'customer_product_no', '客户产品编号', 'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '客户料号名称', 'customer_part_name',  '客户料号名称', 'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '客户图纸编号', 'customer_drawing_no', '客户图纸编号', 'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '宏丰料号',     'hf_part_no',          '宏丰料号',     'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '付款方式',     'payment_method',      '付款方式',     'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '基准货币',     'base_currency',       '基准货币',     'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '报价货币',     'quote_currency',      '报价货币',     'VALUE',      70,  'ACTIVE', false);
  END IF;
END $$;

COMMENT ON TABLE basic_data_attribute IS
    'V63: 修复 UI 导入自动生成的 VAR_xxx_X variable_code,重置为目标物理表的真实列名';
