-- V60: 按生产模板真实列布局重建 basic_data_attribute
-- Ref: data/template/报价系统功能基础数据功能结构.xlsx 实际列结构
-- 根因: V58_5 把 9 个费用类 sheet 都用同一通用 mat_fee 17 列结构(A-R)套,
--       但实际生产模板每个 sheet 的列布局都不同,导致大量列错位:
--       - 单重 实际只有 2 列(A=料号,B=单重),配了 7 列
--       - 元素BOM 实际 11 列,只配了 4 列且 B/C/D 错位
--       - 来料BOM 列错位(E 应是 output_material_type 而非 loss_rate)
--       - 组成件BOM及单价 col B/C/D/E 错位导致 mat_process 唯一键退化
--       - 客户料号与宏丰料号 col A/B 反序
--       - 电镀方案 col E/F/G 错位
--
-- 策略: 删除受影响 sheet 的所有 attribute,按真实列布局逐 sheet 重建
-- v1 简化映射(物理 schema 暂无对应字段的列):
--   - 年降"降价次数" — 丢弃(mat_fee 物理表无字段,v2 处理)
--   - 客户料号"汇率" col H — 丢弃(mat_customer_part_mapping 物理表无字段,v2 处理)
--   - 电镀方案 网址/网站名称/抓取规则 — 丢弃(plating_plan 物理表无对应,v2 处理)
--   - 来料BOM "重量单位"列只有一个 — 仅映射 gross_unit,net_unit 留空

-- ════════════════════════════════════════════════════════════════════
-- Part 0: 清理需要重建的 sheet 的所有 attribute
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
    '客户料号与宏丰料号的关系'
  )
);

-- ════════════════════════════════════════════════════════════════════
-- 1. 单重 → mat_part (实际 2 列: A=料号, B=单重(g/pcs))
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
-- 2. 来料BOM → mat_bom (INCOMING) — 实际 10 列
--    A=宏丰料号, B=项次, C=投入料号, D=投入料号名称, E=产出料号类型,
--    F=材料毛重, G=材料净重, H=重量单位, I=损耗率(%), J=不良率(%)
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
-- 3. 元素BOM → mat_bom (ELEMENT) — 实际 11 列
--    A=宏丰料号, B=投入料号, C=投入料号名称, D=项次, E=元素,
--    F=组成含量(%), G=损耗率%, H=毛用量, I=毛用量单位, J=净用量, K=净用量单位
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
-- 4. 组成件BOM及单价 → mat_process — 实际 15 列
--    A=宏丰料号, B=项次(外层=工序序号), C=工序编号, D=组装工序,
--    E=项次(内层=组成件序号), F=组成件料号, G=组成件名称,
--    H=供应商编号, I=供应商名称, J=组成数量, K=组成单位,
--    L=单价, M=运费, N=货币, O=计价单位
-- 关键: B=seq_no(工序), E=sub_seq_no(组成件) — 唯一键 (hf_part, seq_no, sub_seq_no)
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
-- 5. 来料固定加工费 → mat_fee (INCOMING_FIXED) — 实际 13 列
--    A=宏丰料号, B=项次, C=投入料号, D=投入料号名称,
--    E=值, F=比例(%), G=货币, H=计价单位,
--    I=是否随材料价格波动, J=材料结算涨幅比例(%), K=材料固定的涨幅值,
--    L=货币(涨幅), M=涨幅单位
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
-- 6. 来料其他费用 → mat_fee (INCOMING_OTHER) — 实际 10 列
--    A=宏丰料号, B=项次, C=投入料号, D=投入料号名称,
--    E=项次(内), F=要素名称, G=值, H=比例(%), I=货币, J=计价单位
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
-- 7. 成品固定加工费 → mat_fee (FINISHED_FIXED) — 实际 6 列
--    A=宏丰料号, B=项次, C=值, D=比例(%), E=货币, F=计价单位
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
-- 8. 成品其他费用 → mat_fee (FINISHED_OTHER) — 实际 7 列
--    A=宏丰料号, B=项次, C=要素名称, D=值, E=比例(%), F=货币, G=计价单位
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
-- 9. 来料年降 → mat_fee (INCOMING_ANNUAL_DOWN) — 实际 10 列
--    A=宏丰料号, B=项次, C=投入料号, D=投入料号名称,
--    E=年降顺序, F=年降系数(%), G=单次固定年降值, H=货币, I=计价单位, J=降价次数
-- v1 简化: 年降顺序→dim_sub_seq_no, 系数→fee_ratio, 固定值→fee_value, 降价次数→丢弃(v2)
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
      -- J=降价次数 — v1 丢弃,mat_fee 物理表无对应字段,v2 加 reduction_count 字段后重新映射
  END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- 10. 组装加工费 → mat_fee (ASSEMBLY_PROCESS) — 实际 7 列
--     A=宏丰料号, B=项次, C=组装工序, D=组装加工费, E=货币, F=计价单位, G=拒收率
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
-- 11. 组装加工费年降 → mat_fee (ASSEMBLY_ANNUAL_DOWN) — 实际 9 列
--     A=宏丰料号, B=项次, C=组装工序, D=年降顺序, E=年降系数(%),
--     F=单次固定年降值, G=货币, H=计价单位, I=降价次数
-- v1 简化: 同 #9
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
      -- I=降价次数 v1 丢弃
  END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- 12. 年降系数 → mat_fee (ANNUAL_REDUCTION_FACTOR) — 实际 7 列
--     A=宏丰料号, B=年降顺序, C=年降系数(%/年), D=单次固定年降金额,
--     E=货币, F=计价单位, G=降价次数
-- v1 简化: 同 #9
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
      -- G=降价次数 v1 丢弃
      -- 备注: 该 sheet 仅 hf_part 主键, seq_no 留空, 唯一性靠 fee_type+dim_sub_seq_no 区分
  END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- 13. 电镀方案 → plating_plan — 实际 10 列
--     A=方案编号, B=版本, C=项次, D=电镀元素名称,
--     E=元素单价网址, F=网站名称, G=抓取规则,    -- v1 跳过(物理表无字段)
--     H=电镀面积, I=镀层厚度, J=电镀要求
-- ════════════════════════════════════════════════════════════════════
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '电镀方案' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required) VALUES
      (cfg_id, 'A', '方案编号',     'plan_code',           '方案编号',     'IDENTIFIER', 10, 'ACTIVE', true),
      (cfg_id, 'B', '版本',         'version',             '版本',         'VALUE',      20, 'ACTIVE', false),
      (cfg_id, 'C', '项次',         'seq_no',              '项次',         'VALUE',      30, 'ACTIVE', false),
      (cfg_id, 'D', '电镀元素名称', 'plating_element',     '电镀元素名称', 'VALUE',      40, 'ACTIVE', false),
      -- E/F/G 元素单价网址/网站名称/抓取规则 — v1 跳过(物理表无字段,v2 经 element_price_source/fetch_rule 处理)
      (cfg_id, 'H', '电镀面积',     'plating_area',        '电镀面积',     'VALUE',      80, 'ACTIVE', false),
      (cfg_id, 'I', '镀层厚度',     'coating_thickness',   '镀层厚度',     'VALUE',      90, 'ACTIVE', true),
      (cfg_id, 'J', '电镀要求',     'plating_requirement', '电镀要求',     'VALUE',      100, 'ACTIVE', false);
  END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- 14. 客户料号与宏丰料号的关系 → mat_customer_part_mapping — 实际 8 列
--     A=客户料号名称, B=客户产品编号, C=客户图号, D=宏丰料号,
--     E=付款方式, F=基础货币, G=报价货币, H=汇率
-- v1 简化: H=汇率 → 丢弃(物理表无 exchange_rate 字段, v2 走独立 exchange_rate 表)
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
      -- H=汇率 v1 丢弃
  END IF;
END $$;

COMMENT ON TABLE basic_data_attribute IS
    'V60: 按生产模板真实列布局重建 14 个 sheet 的属性映射(消除 V58_5 列错位)';
