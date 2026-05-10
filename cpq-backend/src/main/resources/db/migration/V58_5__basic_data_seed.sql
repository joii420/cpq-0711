-- V58_5: V5 元数据化 seed — 更新 16 个生产 sheet 配置 + 插入旧测试兼容配置 + 属性列映射
-- Ref: docs/PRD.md V5 元数据化改造 PM 决策表格
-- 注意：
--   1. V46 已 INSERT 10 行（英文表名：mat_part/mat_bom/mat_process/plating_plan/mat_fee/
--      plating_fee/mat_customer_part_mapping/element_price/exchange_rate/customer_tax）
--   2. 本次 UPDATE 这些行的 target_table；同时 INSERT 生产模板 16 个中文 sheet 名配置
--   3. 为旧测试 V5 兼容：INSERT 7 个中文测试 sheet 名配置（料号主档/BOM清单等）
--   4. INSERT basic_data_attribute 列映射 seed

-- ════════════════════════════════════════════════════════════════════
-- Part 1: 更新 V46 已有的英文 sheet_name 配置行（清理用，target_table=NULL 表示不直接导入）
-- （这些英文名称 config 行保留，用于 API 元数据查询，但不参与 parseExcel 流程）
-- ════════════════════════════════════════════════════════════════════
UPDATE basic_data_config SET target_table = NULL WHERE sheet_name = 'mat_part'                  AND target_table IS NULL;
UPDATE basic_data_config SET target_table = NULL WHERE sheet_name = 'mat_bom'                   AND target_table IS NULL;
UPDATE basic_data_config SET target_table = NULL WHERE sheet_name = 'mat_process'               AND target_table IS NULL;
UPDATE basic_data_config SET target_table = NULL WHERE sheet_name = 'plating_plan'              AND target_table IS NULL;
UPDATE basic_data_config SET target_table = NULL WHERE sheet_name = 'mat_fee'                   AND target_table IS NULL;
UPDATE basic_data_config SET target_table = NULL WHERE sheet_name = 'plating_fee'               AND target_table IS NULL;
UPDATE basic_data_config SET target_table = NULL WHERE sheet_name = 'mat_customer_part_mapping' AND target_table IS NULL;
UPDATE basic_data_config SET target_table = NULL WHERE sheet_name = 'element_price'             AND target_table IS NULL;
UPDATE basic_data_config SET target_table = NULL WHERE sheet_name = 'exchange_rate'             AND target_table IS NULL;
UPDATE basic_data_config SET target_table = NULL WHERE sheet_name = 'customer_tax'              AND target_table IS NULL;
-- parent_config_id / join_columns 清理（元数据化后不需要 parent 概念）
UPDATE basic_data_config SET parent_config_id = NULL, join_columns = '[]';

-- ════════════════════════════════════════════════════════════════════
-- Part 2: INSERT 生产模板 16 个中文 sheet 名配置
-- ════════════════════════════════════════════════════════════════════

-- 1. 单重 → mat_part
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('单重', 1, 2, '生产料号主档（GLOBAL）', 110, 'ACTIVE', 'mat_part')
ON CONFLICT DO NOTHING;

-- 2. 来料BOM → mat_bom + bom_type=INCOMING
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('来料BOM', 1, 2, '来料BOM（GLOBAL）bom_type=INCOMING', 120, 'ACTIVE', 'mat_bom', '{"bom_type":"INCOMING"}')
ON CONFLICT DO NOTHING;

-- 3. 元素BOM → mat_bom + bom_type=ELEMENT
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('元素BOM', 1, 2, '元素BOM（GLOBAL）bom_type=ELEMENT', 130, 'ACTIVE', 'mat_bom', '{"bom_type":"ELEMENT"}')
ON CONFLICT DO NOTHING;

-- 4. 组成件BOM及单价 → mat_process
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('组成件BOM及单价', 1, 2, '工艺基础（CUSTOMER）', 140, 'ACTIVE', 'mat_process')
ON CONFLICT DO NOTHING;

-- 5. 来料固定加工费 → mat_fee + fee_type=INCOMING_FIXED
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('来料固定加工费', 1, 2, '来料固定加工费（CUSTOMER）', 150, 'ACTIVE', 'mat_fee', '{"fee_type":"INCOMING_FIXED"}')
ON CONFLICT DO NOTHING;

-- 6. 来料其他费用 → mat_fee + fee_type=INCOMING_OTHER
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('来料其他费用', 1, 2, '来料其他费用（CUSTOMER）', 160, 'ACTIVE', 'mat_fee', '{"fee_type":"INCOMING_OTHER"}')
ON CONFLICT DO NOTHING;

-- 7. 成品固定加工费 → mat_fee + fee_type=FINISHED_FIXED
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('成品固定加工费', 1, 2, '成品固定加工费（CUSTOMER）', 170, 'ACTIVE', 'mat_fee', '{"fee_type":"FINISHED_FIXED"}')
ON CONFLICT DO NOTHING;

-- 8. 成品其他费用 → mat_fee + fee_type=FINISHED_OTHER
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('成品其他费用', 1, 2, '成品其他费用（CUSTOMER）', 180, 'ACTIVE', 'mat_fee', '{"fee_type":"FINISHED_OTHER"}')
ON CONFLICT DO NOTHING;

-- 9. 来料年降 → mat_fee + fee_type=INCOMING_ANNUAL_DOWN
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('来料年降', 1, 2, '来料年降（CUSTOMER）', 190, 'ACTIVE', 'mat_fee', '{"fee_type":"INCOMING_ANNUAL_DOWN"}')
ON CONFLICT DO NOTHING;

-- 10. 组装加工费 → mat_fee + fee_type=ASSEMBLY_PROCESS
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('组装加工费', 1, 2, '组装加工费（CUSTOMER）', 200, 'ACTIVE', 'mat_fee', '{"fee_type":"ASSEMBLY_PROCESS"}')
ON CONFLICT DO NOTHING;

-- 11. 组装加工费年降 → mat_fee + fee_type=ASSEMBLY_ANNUAL_DOWN
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('组装加工费年降', 1, 2, '组装加工费年降（CUSTOMER）', 210, 'ACTIVE', 'mat_fee', '{"fee_type":"ASSEMBLY_ANNUAL_DOWN"}')
ON CONFLICT DO NOTHING;

-- 12. 年降系数 → mat_fee + fee_type=ANNUAL_REDUCTION_FACTOR
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table, target_discriminator)
VALUES ('年降系数', 1, 2, '年降系数（CUSTOMER）', 220, 'ACTIVE', 'mat_fee', '{"fee_type":"ANNUAL_REDUCTION_FACTOR"}')
ON CONFLICT DO NOTHING;

-- 13. 电镀费用 → plating_fee
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('电镀费用', 1, 2, '电镀费用（CUSTOMER）', 230, 'ACTIVE', 'plating_fee')
ON CONFLICT DO NOTHING;

-- 14. 电镀方案 → plating_plan
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('电镀方案', 1, 2, '电镀方案（GLOBAL）', 240, 'ACTIVE', 'plating_plan')
ON CONFLICT DO NOTHING;

-- 15. 客户料号与宏丰料号的关系 → mat_customer_part_mapping
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('客户料号与宏丰料号的关系', 1, 2, '客户料号对照（CUSTOMER）', 250, 'ACTIVE', 'mat_customer_part_mapping')
ON CONFLICT DO NOTHING;

-- 16. 元素单价 → target_table=NULL（v1 跳过）
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('元素单价', 1, 2, '元素单价（CUSTOMER，v1 跳过）', 260, 'ACTIVE', NULL)
ON CONFLICT DO NOTHING;

-- ════════════════════════════════════════════════════════════════════
-- Part 3: INSERT 旧测试兼容的 7 个中文 sheet 名配置（使测试不退化）
-- 旧测试 Excel 列顺序（按 buildSampleExcel/buildSinglePartExcel 方法）：
--   料号主档: A=HF_PART_NO B=PART_NAME C=SPECIFICATION D=SIZE_INFO E=UNIT_WEIGHT F=WEIGHT_UNIT G=STATUS_CODE
--   其余 sheet: 旧测试 Excel 不含这些 sheet（跳过即可，不影响测试）
-- ════════════════════════════════════════════════════════════════════

-- 料号主档 → mat_part（旧测试兼容）
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('料号主档', 1, 2, '旧测试兼容：mat_part（GLOBAL）', 310, 'ACTIVE', 'mat_part')
ON CONFLICT DO NOTHING;

-- BOM清单 → mat_bom（旧测试兼容，默认无 discriminator，bom_type 从列读取）
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('BOM清单', 1, 2, '旧测试兼容：mat_bom（GLOBAL）', 320, 'ACTIVE', 'mat_bom')
ON CONFLICT DO NOTHING;

-- 组成件BOM → mat_process（旧测试兼容）
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('组成件BOM', 1, 2, '旧测试兼容：mat_process（CUSTOMER）', 330, 'ACTIVE', 'mat_process')
ON CONFLICT DO NOTHING;

-- 电镀方案（旧）→ plating_plan（已在生产 sheet 14 插入 '电镀方案'，共用）
-- 旧常量 SHEET_PLATING_PLAN = "电镀方案"，与生产模板同名，无需额外插入

-- 费用清单 → mat_fee（旧测试兼容，无 discriminator，fee_type 从列读取）
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('费用清单', 1, 2, '旧测试兼容：mat_fee（CUSTOMER）', 340, 'ACTIVE', 'mat_fee')
ON CONFLICT DO NOTHING;

-- 电镀费用（旧）→ plating_fee（已在生产 sheet 13 插入 '电镀费用'，共用）
-- 旧常量 SHEET_PLATING_FEE = "电镀费用"，与生产模板同名，无需额外插入

-- 客户料号映射 → mat_customer_part_mapping（旧测试兼容）
INSERT INTO basic_data_config (sheet_name, header_row_index, data_start_row_index, description, sort_order, status, target_table)
VALUES ('客户料号映射', 1, 2, '旧测试兼容：mat_customer_part_mapping（CUSTOMER）', 350, 'ACTIVE', 'mat_customer_part_mapping')
ON CONFLICT DO NOTHING;

-- ════════════════════════════════════════════════════════════════════
-- Part 4: INSERT basic_data_attribute 列映射 seed
-- variable_code = 物理表列名（小写，等于 Java 字段映射 key）
-- column_letter = Excel 列字母（A/B/C...）
-- is_required = true 表示为空时校验报错
-- ════════════════════════════════════════════════════════════════════

-- ── 料号主档 / 单重 → mat_part 属性 ──────────────────────────────────────────
-- 两个 config 行（料号主档 + 单重）共用同一列结构

-- 料号主档 属性
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '料号主档' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
    VALUES
      (cfg_id, 'A', '宏丰料号',  'part_no',        '宏丰料号',   'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '料号名称',  'part_name',      '料号名称',   'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '规格',      'specification',  '规格',       'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '尺寸信息',  'size_info',      '尺寸信息',   'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '单重',      'unit_weight',    '单重',       'VALUE',      50,  'ACTIVE', true),
      (cfg_id, 'F', '重量单位',  'weight_unit',    '重量单位',   'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '状态',      'status_code',    '状态',       'VALUE',      70,  'ACTIVE', false)
    ON CONFLICT (config_id, variable_code) DO NOTHING;
  END IF;
END $$;

-- 单重 属性
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '单重' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
    VALUES
      (cfg_id, 'A', '宏丰料号',  'part_no',        '宏丰料号',   'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '料号名称',  'part_name',      '料号名称',   'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '规格',      'specification',  '规格',       'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '尺寸信息',  'size_info',      '尺寸信息',   'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '单重',      'unit_weight',    '单重',       'VALUE',      50,  'ACTIVE', true),
      (cfg_id, 'F', '重量单位',  'weight_unit',    '重量单位',   'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '状态',      'status_code',    '状态',       'VALUE',      70,  'ACTIVE', false)
    ON CONFLICT (config_id, variable_code) DO NOTHING;
  END IF;
END $$;

-- ── 来料BOM / BOM清单 → mat_bom (INCOMING) 属性 ────────────────────────────

DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '来料BOM' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
    VALUES
      (cfg_id, 'A', '宏丰料号',        'hf_part_no',          '宏丰料号',       'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', 'BOM序号',         'seq_no',              'BOM序号',        'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '来料编号',         'input_material_no',   '来料编号',       'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '来料名称',         'input_material_name', '来料名称',       'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '损耗率',           'loss_rate',           '损耗率',         'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '毛用量',           'gross_qty',           '毛用量',         'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '净用量',           'net_qty',             '净用量',         'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '毛用量单位',       'gross_unit',          '毛用量单位',     'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '净用量单位',       'net_unit',            '净用量单位',     'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '产出物料类型',     'output_material_type','产出物料类型',   'VALUE',      100, 'ACTIVE', false),
      (cfg_id, 'K', '不良率',           'defect_rate',         '不良率',         'VALUE',      110, 'ACTIVE', false)
    ON CONFLICT (config_id, variable_code) DO NOTHING;
  END IF;
END $$;

-- BOM清单（旧测试兼容，含 BOM_TYPE 列）
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = 'BOM清单' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
    VALUES
      (cfg_id, 'A', '宏丰料号',    'hf_part_no',  '宏丰料号', 'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', 'BOM类型',     'bom_type',    'BOM类型',  'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', 'BOM序号',     'seq_no',      'BOM序号',  'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '来料编号',    'input_material_no',   '来料编号',   'VALUE', 40, 'ACTIVE', false),
      (cfg_id, 'E', '来料名称',    'input_material_name', '来料名称',   'VALUE', 50, 'ACTIVE', false),
      (cfg_id, 'F', '损耗率',      'loss_rate',   '损耗率',   'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '毛用量',      'gross_qty',   '毛用量',   'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '净用量',      'net_qty',     '净用量',   'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '毛用量单位',  'gross_unit',  '毛用量单位', 'VALUE',    90,  'ACTIVE', false),
      (cfg_id, 'J', '净用量单位',  'net_unit',    '净用量单位', 'VALUE',    100, 'ACTIVE', false),
      (cfg_id, 'K', '产出物料类型','output_material_type', '产出物料类型', 'VALUE', 110, 'ACTIVE', false),
      (cfg_id, 'L', '不良率',      'defect_rate', '不良率',   'VALUE',      120, 'ACTIVE', false),
      (cfg_id, 'M', '元素名称',    'element_name','元素名称', 'VALUE',      130, 'ACTIVE', false),
      (cfg_id, 'N', '含量百分比',  'composition_pct','含量百分比', 'VALUE',  140, 'ACTIVE', false)
    ON CONFLICT (config_id, variable_code) DO NOTHING;
  END IF;
END $$;

-- ── 元素BOM → mat_bom (ELEMENT) 属性 ───────────────────────────────────────

DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '元素BOM' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
    VALUES
      (cfg_id, 'A', '宏丰料号',   'hf_part_no',    '宏丰料号',   'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', 'BOM序号',    'seq_no',         'BOM序号',    'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '元素名称',   'element_name',   '元素名称',   'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '含量百分比', 'composition_pct','含量百分比', 'VALUE',      40,  'ACTIVE', false)
    ON CONFLICT (config_id, variable_code) DO NOTHING;
  END IF;
END $$;

-- ── 组成件BOM及单价 / 组成件BOM → mat_process 属性 ─────────────────────────

DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '组成件BOM及单价' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
    VALUES
      (cfg_id, 'A', '宏丰料号',    'hf_part_no',       '宏丰料号',   'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '工序序号',    'seq_no',            '工序序号',   'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '子序号',      'sub_seq_no',        '子序号',     'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '工序编码',    'process_code',      '工序编码',   'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '装配工序',    'assembly_process',  '装配工序',   'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '组成件料号',  'component_part_no', '组成件料号', 'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '组成件名称',  'component_name',    '组成件名称', 'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '供应商编码',  'supplier_code',     '供应商编码', 'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '供应商名称',  'supplier_name',     '供应商名称', 'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '数量',        'quantity',          '数量',       'VALUE',      100, 'ACTIVE', false),
      (cfg_id, 'K', '数量单位',    'quantity_unit',     '数量单位',   'VALUE',      110, 'ACTIVE', false),
      (cfg_id, 'L', '单价',        'unit_price',        '单价',       'VALUE',      120, 'ACTIVE', false),
      (cfg_id, 'M', '运费',        'freight',           '运费',       'VALUE',      130, 'ACTIVE', false),
      (cfg_id, 'N', '货币',        'currency',          '货币',       'VALUE',      140, 'ACTIVE', false),
      (cfg_id, 'O', '价格单位',    'price_unit',        '价格单位',   'VALUE',      150, 'ACTIVE', false)
    ON CONFLICT (config_id, variable_code) DO NOTHING;
  END IF;
END $$;

-- 组成件BOM（旧测试兼容）
DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '组成件BOM' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
    VALUES
      (cfg_id, 'A', '宏丰料号',   'hf_part_no',        '宏丰料号',   'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '工序序号',   'seq_no',             '工序序号',   'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '子序号',     'sub_seq_no',         '子序号',     'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '工序编码',   'process_code',       '工序编码',   'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '装配工序',   'assembly_process',   '装配工序',   'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '组成件料号', 'component_part_no',  '组成件料号', 'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '组成件名称', 'component_name',     '组成件名称', 'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '供应商编码', 'supplier_code',      '供应商编码', 'VALUE',      80,  'ACTIVE', false),
      (cfg_id, 'I', '供应商名称', 'supplier_name',      '供应商名称', 'VALUE',      90,  'ACTIVE', false),
      (cfg_id, 'J', '数量',       'quantity',           '数量',       'VALUE',      100, 'ACTIVE', false),
      (cfg_id, 'K', '数量单位',   'quantity_unit',      '数量单位',   'VALUE',      110, 'ACTIVE', false),
      (cfg_id, 'L', '单价',       'unit_price',         '单价',       'VALUE',      120, 'ACTIVE', false),
      (cfg_id, 'M', '运费',       'freight',            '运费',       'VALUE',      130, 'ACTIVE', false),
      (cfg_id, 'N', '货币',       'currency',           '货币',       'VALUE',      140, 'ACTIVE', false),
      (cfg_id, 'O', '价格单位',   'price_unit',         '价格单位',   'VALUE',      150, 'ACTIVE', false)
    ON CONFLICT (config_id, variable_code) DO NOTHING;
  END IF;
END $$;

-- ── 费用类 sheet → mat_fee 属性（通用结构，discriminator 决定 fee_type）──────
-- 共用：来料固定加工费/来料其他费用/成品固定加工费/成品其他费用/来料年降/组装加工费/组装加工费年降/年降系数/费用清单

DO $$
DECLARE cfg_id UUID;
DECLARE sheet_names TEXT[] := ARRAY['来料固定加工费','来料其他费用','成品固定加工费','成品其他费用',
                                     '来料年降','组装加工费','组装加工费年降','年降系数','费用清单'];
DECLARE sname TEXT;
BEGIN
  FOREACH sname IN ARRAY sheet_names LOOP
    SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = sname LIMIT 1;
    IF cfg_id IS NOT NULL THEN
      INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
      VALUES
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
        (cfg_id, 'R', '报废率',            'reject_rate',            '报废率',           'VALUE',      180, 'ACTIVE', false)
      ON CONFLICT (config_id, variable_code) DO NOTHING;
    END IF;
  END LOOP;
END $$;

-- ── 电镀方案 → plating_plan 属性 ────────────────────────────────────────────

DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '电镀方案' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
    VALUES
      (cfg_id, 'A', '方案编码',   'plan_code',           '方案编码',   'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '版本号',     'version',             '版本号',     'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '序号',       'seq_no',              '序号',       'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '镀层元素',   'plating_element',     '镀层元素',   'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '镀层面积',   'plating_area',        '镀层面积',   'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '镀层厚度',   'coating_thickness',   '镀层厚度',   'VALUE',      60,  'ACTIVE', true),
      (cfg_id, 'G', '镀层要求',   'plating_requirement', '镀层要求',   'VALUE',      70,  'ACTIVE', false)
    ON CONFLICT (config_id, variable_code) DO NOTHING;
  END IF;
END $$;

-- ── 电镀费用 → plating_fee 属性 ─────────────────────────────────────────────

DO $$ DECLARE cfg_id UUID; BEGIN
  SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = '电镀费用' LIMIT 1;
  IF cfg_id IS NOT NULL THEN
    INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
    VALUES
      (cfg_id, 'A', '宏丰料号',     'hf_part_no',          '宏丰料号',     'IDENTIFIER', 10,  'ACTIVE', true),
      (cfg_id, 'B', '电镀方案编码', 'plating_plan_code',   '电镀方案编码', 'VALUE',      20,  'ACTIVE', false),
      (cfg_id, 'C', '方案版本号',   'plan_version',        '方案版本号',   'VALUE',      30,  'ACTIVE', false),
      (cfg_id, 'D', '电镀加工费',   'plating_process_fee', '电镀加工费',   'VALUE',      40,  'ACTIVE', false),
      (cfg_id, 'E', '电镀材料费',   'plating_material_fee','电镀材料费',   'VALUE',      50,  'ACTIVE', false),
      (cfg_id, 'F', '货币',         'currency',            '货币',         'VALUE',      60,  'ACTIVE', false),
      (cfg_id, 'G', '价格单位',     'price_unit',          '价格单位',     'VALUE',      70,  'ACTIVE', false),
      (cfg_id, 'H', '不良率',       'defect_rate',         '不良率',       'VALUE',      80,  'ACTIVE', false)
    ON CONFLICT (config_id, variable_code) DO NOTHING;
  END IF;
END $$;

-- ── 客户料号与宏丰料号的关系 / 客户料号映射 → mat_customer_part_mapping 属性 ──

DO $$
DECLARE cfg_id UUID;
DECLARE sheet_names TEXT[] := ARRAY['客户料号与宏丰料号的关系', '客户料号映射'];
DECLARE sname TEXT;
BEGIN
  FOREACH sname IN ARRAY sheet_names LOOP
    SELECT id INTO cfg_id FROM basic_data_config WHERE sheet_name = sname LIMIT 1;
    IF cfg_id IS NOT NULL THEN
      INSERT INTO basic_data_attribute (config_id, column_letter, column_title, variable_code, variable_label, data_type, sort_order, status, is_required)
      VALUES
        (cfg_id, 'A', '客户产品编号', 'customer_product_no', '客户产品编号', 'IDENTIFIER', 10,  'ACTIVE', true),
        (cfg_id, 'B', '客户料号名称', 'customer_part_name',  '客户料号名称', 'VALUE',      20,  'ACTIVE', false),
        (cfg_id, 'C', '客户图纸编号', 'customer_drawing_no', '客户图纸编号', 'VALUE',      30,  'ACTIVE', false),
        (cfg_id, 'D', '宏丰料号',     'hf_part_no',          '宏丰料号',     'VALUE',      40,  'ACTIVE', false),
        (cfg_id, 'E', '付款方式',     'payment_method',      '付款方式',     'VALUE',      50,  'ACTIVE', false),
        (cfg_id, 'F', '基准货币',     'base_currency',       '基准货币',     'VALUE',      60,  'ACTIVE', false),
        (cfg_id, 'G', '报价货币',     'quote_currency',      '报价货币',     'VALUE',      70,  'ACTIVE', false)
      ON CONFLICT (config_id, variable_code) DO NOTHING;
    END IF;
  END LOOP;
END $$;
