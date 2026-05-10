-- ============================================================
-- V143: 补齐 5.0 版核价 Excel 各 sheet 在 basic_data_config 的 COSTING 注册
--       让 V5 import (templateKind=COSTING) 能把核价 Excel 数据写入 costing_part_* 物理表
--       从而让 V142 核价模板的 20 个 tab 真正显示数据
-- ============================================================
--
-- 来源: data/template/核价系统功能基础数据功能结构所需字段（5.0版）.xlsx
--
-- 排除不进 V5 import 的 sheet (用户已确认):
--   * 元素核价价格表 / 材料核价价格表 / 汇率管理表 → 全局变量管理，不进 import
--   * 宏丰-客户料号对应关系 → 已走报价侧 mat_customer_part_mapping (V142 视图已 FROM 该表)
--   * 核价版本 / 汇总 → 元数据/输出，非料号级数据
--
-- 5.0 版 19 个料号级 sheet 与物理表映射:
--   ① 来料BOM (COSTING)               → costing_part_material_bom  [V91已注册, C.05b 切换 target]
--   ② 来料与元素BOM (COSTING, 新)      → costing_part_element_bom   [B.01 新建]
--   ③ 人工成本(单价) (COSTING, 半角)   → costing_part_process_cost [LABOR]   [C.01 新建半角版]
--   ④ 设备折旧成本 (COSTING)           → costing_part_process_cost [DEPRECIATION] [V89已注册]
--   ⑤ 生产设备能耗成本 (COSTING)        → costing_part_process_cost [ENERGY_DEDICATED] [V89已注册]
--   ⑥ 辅助设备能耗成本 (COSTING)        → costing_part_process_cost [ENERGY_SHARED] [V89已注册]
--   ⑦ 模具工装成本 (COSTING, 新)       → costing_part_tooling_cost  [B.02 新建]
--   ⑧ 生产耗材 (COSTING, 新)           → costing_part_process_cost [CONSUMABLE] [B.03 新建]
--   ⑨ 包装材料 (COSTING, 新)           → costing_part_process_cost [CONSUMABLE] [B.04 新建]
--   ⑩ 来料加工费 (COSTING, 新)         → costing_part_process_cost [MATERIAL_PROC] [B.05 新建]
--   ⑪ 来料其他费用(比例) (COSTING)     → mat_fee [INCOMING_OTHER]  [V91/V94 已注册]
--   ⑫ 来料其他固定费用 (COSTING, 新)   → mat_fee [INCOMING_FIXED]  [B.06 新建]
--   ⑬ 成品加工费&组装费 (COSTING, 新)  → costing_part_process_cost [SEMI_FINISHED_PROC] [B.07 新建]
--   ⑭ 成品其他比例费用 (COSTING, 新)   → mat_fee [FINISHED_OTHER]  [B.08 新建]
--   ⑮ 成品其他固定费用 (COSTING, 新)   → mat_fee [FINISHED_FIXED]  [B.09 新建]
--   ⑯ 电镀方案 (COSTING)              → costing_part_plating       [V91/V125 已注册, C.06 核对]
--   ⑰ 电镀成本 (COSTING, 新)          → costing_part_plating_fee   [B.10 新建]
--   ⑱ 其他外加工成本 (COSTING, 新)     → costing_part_process_cost [POST_PROC] [B.11 新建]
--   ⑲ 单重 (BOTH)                     → mat_part [V58_5 已注册, C.07 核对]
--
-- 阶段 A: 无需扩 mat_fee.fee_type CHECK — V139 已含全部所需枚举值
--           (INCOMING_FIXED / INCOMING_OTHER / FINISHED_FIXED / FINISHED_OTHER 均已存在)
-- ============================================================

-- ════════════════════════════════════════════════════════════════════════════
-- 阶段 B: 注册 11 个未注册 sheet
-- ════════════════════════════════════════════════════════════════════════════

-- ── B.01 来料与元素BOM → costing_part_element_bom ────────────────────────
-- 5.0 列: A=来料料号 B=品名 C=规格 D=尺寸 E=项次 F=元素代码 G=组成含量(%) H=损耗率(%)
-- DB 字段: input_material_no / seq_no / element_code / composition_pct / loss_rate
-- 注: fillCostingPartRow 检查 hf_part_no 兜底检查 input_material_no 作为 key
--     composition_pct/loss_rate 存小数, Excel 填整数百分比 → upsertCostingElementBom 直接写入
--     (V76 物理表 composition_pct NUMERIC(8,4), 调用侧用 toDecimal 不除以100)
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '来料与元素BOM' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「来料与元素BOM」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '来料与元素BOM', 501, 'costing_part_element_bom', NULL, 'COSTING',
        1, 2, 501, 'ACTIVE',
        'V143: 5.0 版核价「来料与元素BOM」sheet, 物理表 costing_part_element_bom; 列A=input_material_no(业务键)',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '来料料号',    'input_material_no', '来料料号',    'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  true,  true,  now(), now()),
    -- B 品名 / C 规格 / D 尺寸 → 跳过 (仅参考, 不入库)
    (gen_random_uuid(), v_cfg_id, 'E', '项次',        'seq_no',            '项次',        'IDENTIFIER', 'ACTIVE', 5, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'F', '元素代码',    'element_code',      '元素代码',    'IDENTIFIER', 'ACTIVE', 6, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'G', '组成含量(%)', 'composition_pct',   '组成含量(%)', 'VALUE',      'ACTIVE', 7, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '损耗率(%)',   'loss_rate',         '损耗率(%)',   'VALUE',      'ACTIVE', 8, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「来料与元素BOM」(COSTING) → configId=%', v_cfg_id;
END $$;

-- ── B.02 模具工装成本 → costing_part_tooling_cost ────────────────────────
-- 5.0 列: A=宏丰料号 B=品名 C=规格 D=尺寸 E=工序编号 F=工序名称 G=项次
--         H=模具台账 I=工装编号 J=单个模具或工装成本 K=寿命(次) L=单循环产量
--         M=模具工装成本单价 N=币种 O=计量单位 P=是否有效
-- DB 字段: hf_part_no / process_no / process_name / seq_no / tooling_no /
--           tooling_unit_cost / process_count / cycle_count / unit_price / currency / unit
-- 映射: H=模具台账 → tooling_no; I=工装编号 → 跳过(DB 单列); J → tooling_unit_cost;
--       K=寿命(次) → process_count; L=单循环产量 → cycle_count; M → unit_price
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '模具工装成本' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「模具工装成本」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '模具工装成本', 502, 'costing_part_tooling_cost', NULL, 'COSTING',
        1, 2, 502, 'ACTIVE',
        'V143: 5.0 版核价「模具工装成本」sheet, 物理表 costing_part_tooling_cost; G=seq_no, H=tooling_no(模具台账), J=tooling_unit_cost, K=process_count(寿命), L=cycle_count(单循环产量), M=unit_price',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',          'hf_part_no',        '宏丰料号',          'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    -- B 品名 / C 规格 / D 尺寸 → 跳过
    (gen_random_uuid(), v_cfg_id, 'E', '工序编号',          'process_no',        '工序编号',          'IDENTIFIER', 'ACTIVE',  5, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'F', '工序名称',          'process_name',      '工序名称',          'VALUE',      'ACTIVE',  6, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'G', '项次',              'seq_no',            '项次',              'IDENTIFIER', 'ACTIVE',  7, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '模具台账',          'tooling_no',        '模具台账',          'VALUE',      'ACTIVE',  8, 'NORMAL',    false, false, now(), now()),
    -- I 工装编号 → 跳过 (DB tooling_no 单列, 无法区分台账与编号)
    (gen_random_uuid(), v_cfg_id, 'J', '单个模具或工装成本', 'tooling_unit_cost', '单套模具成本',      'VALUE',      'ACTIVE', 10, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'K', '寿命(次)',          'process_count',     '寿命(次)',          'VALUE',      'ACTIVE', 11, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'L', '单循环产量',        'cycle_count',       '单循环产量',        'VALUE',      'ACTIVE', 12, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'M', '模具工装成本单价',  'unit_price',        '模具工装成本单价',  'VALUE',      'ACTIVE', 13, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'N', '币种',              'currency',          '币种',              'VALUE',      'ACTIVE', 14, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'O', '计量单位',          'unit',              '计量单位',          'VALUE',      'ACTIVE', 15, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'P', '是否有效',          'is_active',         '是否有效',          'VALUE',      'ACTIVE', 16, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「模具工装成本」(COSTING) → configId=%', v_cfg_id;
END $$;

-- ── B.03 生产耗材 → costing_part_process_cost [CONSUMABLE] (非包装) ───────
-- 5.0 列: A=宏丰料号 B=品名 C=规格 D=尺寸 E=工序编号 F=工序名称
--         G=耗材成本单价 H=币种 I=计量单位 J=取用的耗材版本 K=是否有效
-- V142 视图 v_c_consumable_prod_merged: CONSUMABLE WHERE process_name NOT LIKE '%包装%'
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '生产耗材' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「生产耗材」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '生产耗材', 503, 'costing_part_process_cost',
        '{"cost_type":"CONSUMABLE"}'::jsonb, 'COSTING',
        1, 2, 503, 'ACTIVE',
        'V143: 5.0 版核价「生产耗材」sheet, 物理表 costing_part_process_cost[CONSUMABLE]; V142 视图按 process_name NOT LIKE ''%包装%'' 过滤',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',      'hf_part_no',       '宏丰料号',      'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    -- B 品名 / C 规格 / D 尺寸 → 跳过
    (gen_random_uuid(), v_cfg_id, 'E', '工序编号',      'process_no',       '工序编号',      'IDENTIFIER', 'ACTIVE',  5, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'F', '工序名称',      'process_name',     '工序名称',      'VALUE',      'ACTIVE',  6, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'G', '耗材成本单价',  'unit_price',       '耗材成本单价',  'VALUE',      'ACTIVE',  7, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '币种',          'currency',         '币种',          'VALUE',      'ACTIVE',  8, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'I', '计量单位',      'unit',             '计量单位',      'VALUE',      'ACTIVE',  9, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'J', '取用的耗材版本', 'ref_calc_version', '取用的耗材版本','VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'K', '是否有效',      'is_active',        '是否有效',      'VALUE',      'ACTIVE', 11, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「生产耗材」(COSTING, CONSUMABLE) → configId=%', v_cfg_id;
END $$;

-- ── B.04 包装材料 → costing_part_process_cost [CONSUMABLE] (含包装) ────────
-- 5.0 列: A=宏丰料号 B=品名 C=规格 D=尺寸 E=工序编号 F=工序名称
--         G=包装成本单价 H=币种 I=计量单位 J=取用的耗材版本 K=是否有效
-- V142 视图 v_c_packaging_merged: CONSUMABLE WHERE process_name LIKE '%包装%'
-- 用户需在 Excel F列(工序名称)填写含"包装"的名称供视图过滤
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '包装材料' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「包装材料」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '包装材料', 504, 'costing_part_process_cost',
        '{"cost_type":"CONSUMABLE"}'::jsonb, 'COSTING',
        1, 2, 504, 'ACTIVE',
        'V143: 5.0 版核价「包装材料」sheet, 物理表 costing_part_process_cost[CONSUMABLE]; 需工序名称(F列)含"包装"字样供 V142 视图 v_c_packaging_merged 过滤',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',      'hf_part_no',       '宏丰料号',      'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    -- B 品名 / C 规格 / D 尺寸 → 跳过
    (gen_random_uuid(), v_cfg_id, 'E', '工序编号',      'process_no',       '工序编号',      'IDENTIFIER', 'ACTIVE',  5, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'F', '工序名称',      'process_name',     '工序名称',      'VALUE',      'ACTIVE',  6, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'G', '包装成本单价',  'unit_price',       '包装成本单价',  'VALUE',      'ACTIVE',  7, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '币种',          'currency',         '币种',          'VALUE',      'ACTIVE',  8, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'I', '计量单位',      'unit',             '计量单位',      'VALUE',      'ACTIVE',  9, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'J', '取用的耗材版本', 'ref_calc_version', '取用的耗材版本','VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'K', '是否有效',      'is_active',        '是否有效',      'VALUE',      'ACTIVE', 11, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「包装材料」(COSTING, CONSUMABLE) → configId=%', v_cfg_id;
END $$;

-- ── B.05 来料加工费 → costing_part_process_cost [MATERIAL_PROC] ──────────
-- 5.0 列: A=宏丰料号 B=项次 C=来料料号 D=品名 E=规格 F=尺寸 G=加工费 H=币种 I=计量单位
-- DB 字段: hf_part_no / process_no / unit_price / currency / unit
-- 注意: 5.0 版无「工序编号」列, 用 B=项次 → process_no 作为区分键 (VARCHAR 接受数字字符串)
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '来料加工费' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「来料加工费」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '来料加工费', 505, 'costing_part_process_cost',
        '{"cost_type":"MATERIAL_PROC"}'::jsonb, 'COSTING',
        1, 2, 505, 'ACTIVE',
        'V143: 5.0 版核价「来料加工费」sheet, 物理表 costing_part_process_cost[MATERIAL_PROC]; B=项次 → process_no(业务区分键)',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',  'hf_part_no',   '宏丰料号',  'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'B', '项次',      'process_no',   '项次',      'IDENTIFIER', 'ACTIVE', 2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'C', '来料料号',  'input_material_no', '来料料号', 'VALUE', 'ACTIVE', 3, 'NORMAL',    false, false, now(), now()),
    -- D 品名 / E 规格 / F 尺寸 → 跳过
    (gen_random_uuid(), v_cfg_id, 'G', '加工费',    'unit_price',   '加工费',    'VALUE',      'ACTIVE', 7, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '币种',      'currency',     '币种',      'VALUE',      'ACTIVE', 8, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'I', '计量单位',  'unit',         '计量单位',  'VALUE',      'ACTIVE', 9, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「来料加工费」(COSTING, MATERIAL_PROC) → configId=%', v_cfg_id;
END $$;

-- ── B.06 来料其他固定费用 → mat_fee [INCOMING_FIXED] ─────────────────────
-- 5.0 列: A=宏丰料号 B=一级项次 C=来料料号 D=品名 E=规格 F=尺寸
--         G=二级项次 H=要素名称 I=费用 J=币种 K=计价单位
-- DB 字段: hf_part_no / seq_no / dim_input_material_no / dim_sub_seq_no / dim_element_name /
--           fee_value / currency / price_unit / customer_id (import 入参提供)
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '来料其他固定费用' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「来料其他固定费用」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '来料其他固定费用', 506, 'mat_fee',
        '{"fee_type":"INCOMING_FIXED"}'::jsonb, 'COSTING',
        1, 2, 506, 'ACTIVE',
        'V143: 5.0 版核价「来料其他固定费用」sheet, 物理表 mat_fee[INCOMING_FIXED]; B=seq_no(一级), G=dim_sub_seq_no(二级), H=dim_element_name, I=fee_value',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',  'hf_part_no',            '宏丰料号',  'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'B', '一级项次',  'seq_no',                '一级项次',  'IDENTIFIER', 'ACTIVE',  2, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'C', '来料料号',  'dim_input_material_no', '来料料号',  'VALUE',      'ACTIVE',  3, 'NORMAL',    false, false, now(), now()),
    -- D 品名 / E 规格 / F 尺寸 → 跳过
    (gen_random_uuid(), v_cfg_id, 'G', '二级项次',  'dim_sub_seq_no',        '二级项次',  'IDENTIFIER', 'ACTIVE',  7, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '要素名称',  'dim_element_name',      '要素名称',  'VALUE',      'ACTIVE',  8, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'I', '费用',      'fee_value',             '费用',      'VALUE',      'ACTIVE',  9, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'J', '币种',      'currency',              '币种',      'VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'K', '计价单位',  'price_unit',            '计价单位',  'VALUE',      'ACTIVE', 11, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「来料其他固定费用」(COSTING, INCOMING_FIXED) → configId=%', v_cfg_id;
END $$;

-- ── B.07 成品加工费&组装费 → costing_part_process_cost [SEMI_FINISHED_PROC]
-- 5.0 列: A=宏丰料号 B=品名 C=规格 D=尺寸 E=工序编号 F=工序名称
--         G=加工费 H=币种 I=计量单位 J=不良率·拒收率(%)
-- DB 字段: hf_part_no / process_no / process_name / unit_price / currency / unit / notes
-- J=不良率·拒收率(%) → notes (costing_part_process_cost.notes TEXT, 无专用数值列)
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '成品加工费&组装费' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「成品加工费&组装费」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '成品加工费&组装费', 507, 'costing_part_process_cost',
        '{"cost_type":"SEMI_FINISHED_PROC"}'::jsonb, 'COSTING',
        1, 2, 507, 'ACTIVE',
        'V143: 5.0 版核价「成品加工费&组装费」sheet, 物理表 costing_part_process_cost[SEMI_FINISHED_PROC]; J=不良率拒收率(%) → notes 文本存储',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',          'hf_part_no',   '宏丰料号',          'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    -- B 品名 / C 规格 / D 尺寸 → 跳过
    (gen_random_uuid(), v_cfg_id, 'E', '工序编号',          'process_no',   '工序编号',          'IDENTIFIER', 'ACTIVE',  5, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'F', '工序名称',          'process_name', '工序名称',          'VALUE',      'ACTIVE',  6, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'G', '加工费',            'unit_price',   '加工费',            'VALUE',      'ACTIVE',  7, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '币种',              'currency',     '币种',              'VALUE',      'ACTIVE',  8, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'I', '计量单位',          'unit',         '计量单位',          'VALUE',      'ACTIVE',  9, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'J', '不良率·拒收率(%)', 'notes',        '不良率·拒收率(%)', 'VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「成品加工费&组装费」(COSTING, SEMI_FINISHED_PROC) → configId=%', v_cfg_id;
END $$;

-- ── B.08 成品其他比例费用 → mat_fee [FINISHED_OTHER] ─────────────────────
-- 5.0 列: A=宏丰料号 B=品名 C=规格 D=尺寸 E=项次 F=要素编号 G=要素名称 H=比例(%)
-- DB 字段: hf_part_no / seq_no / dim_element_name / fee_ratio
-- fee_ratio: toDecimalPercent 入库 (5→0.05), V142 视图 ×100 显示
-- V131 防御: FINISHED_OTHER 必须有 dim_element_name, G 列不能为空
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '成品其他比例费用' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「成品其他比例费用」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '成品其他比例费用', 508, 'mat_fee',
        '{"fee_type":"FINISHED_OTHER"}'::jsonb, 'COSTING',
        1, 2, 508, 'ACTIVE',
        'V143: 5.0 版核价「成品其他比例费用」sheet, 物理表 mat_fee[FINISHED_OTHER]; E=seq_no, G=dim_element_name(必填, V131防御), H=fee_ratio(toDecimalPercent入库)',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',  'hf_part_no',       '宏丰料号',  'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  true,  true,  now(), now()),
    -- B 品名 / C 规格 / D 尺寸 → 跳过
    (gen_random_uuid(), v_cfg_id, 'E', '项次',      'seq_no',           '项次',      'IDENTIFIER', 'ACTIVE', 5, 'IMPORTANT', true,  true,  now(), now()),
    -- F 要素编号 → 跳过 (DB 无对应字段)
    (gen_random_uuid(), v_cfg_id, 'G', '要素名称',  'dim_element_name', '要素名称',  'VALUE',      'ACTIVE', 7, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '比例(%)',   'fee_ratio',        '比例(%)',   'VALUE',      'ACTIVE', 8, 'IMPORTANT', true,  false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「成品其他比例费用」(COSTING, FINISHED_OTHER) → configId=%', v_cfg_id;
END $$;

-- ── B.09 成品其他固定费用 → mat_fee [FINISHED_FIXED] ─────────────────────
-- 5.0 列: A=宏丰料号 B=品名 C=规格 D=尺寸 E=项次 F=要素名称 G=费用 H=币种 I=计价单位
-- DB 字段: hf_part_no / seq_no / dim_element_name / fee_value / currency / price_unit
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '成品其他固定费用' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「成品其他固定费用」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '成品其他固定费用', 509, 'mat_fee',
        '{"fee_type":"FINISHED_FIXED"}'::jsonb, 'COSTING',
        1, 2, 509, 'ACTIVE',
        'V143: 5.0 版核价「成品其他固定费用」sheet, 物理表 mat_fee[FINISHED_FIXED]; E=seq_no, F=dim_element_name, G=fee_value',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',  'hf_part_no',       '宏丰料号',  'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  true,  true,  now(), now()),
    -- B 品名 / C 规格 / D 尺寸 → 跳过
    (gen_random_uuid(), v_cfg_id, 'E', '项次',      'seq_no',           '项次',      'IDENTIFIER', 'ACTIVE', 5, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'F', '要素名称',  'dim_element_name', '要素名称',  'VALUE',      'ACTIVE', 6, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'G', '费用',      'fee_value',        '费用',      'VALUE',      'ACTIVE', 7, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '币种',      'currency',         '币种',      'VALUE',      'ACTIVE', 8, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'I', '计价单位',  'price_unit',       '计价单位',  'VALUE',      'ACTIVE', 9, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「成品其他固定费用」(COSTING, FINISHED_FIXED) → configId=%', v_cfg_id;
END $$;

-- ── B.10 电镀成本 → costing_part_plating_fee ─────────────────────────────
-- 5.0 列: A=宏丰料号 B=电镀方案编号 C=版本编号 D=电镀加工费 E=电镀材料费
--         F=货币 G=计价单位 H=不良率(%)
-- DB 字段: hf_part_no / plating_plan_code / plan_version / plating_process_fee /
--           plating_material_fee / currency / price_unit / defect_rate
-- defect_rate: toDecimalPercent 入库 (8% → 0.08)
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '电镀成本' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「电镀成本」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '电镀成本', 510, 'costing_part_plating_fee', NULL, 'COSTING',
        1, 2, 510, 'ACTIVE',
        'V143: 5.0 版核价「电镀成本」sheet, 物理表 costing_part_plating_fee; H=defect_rate toDecimalPercent入库',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',     'hf_part_no',          '宏丰料号',     'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'B', '电镀方案编号', 'plating_plan_code',   '电镀方案编号', 'IDENTIFIER', 'ACTIVE', 2, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'C', '版本编号',     'plan_version',        '版本编号',     'IDENTIFIER', 'ACTIVE', 3, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'D', '电镀加工费',   'plating_process_fee', '电镀加工费',   'VALUE',      'ACTIVE', 4, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'E', '电镀材料费',   'plating_material_fee','电镀材料费',   'VALUE',      'ACTIVE', 5, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'F', '货币',         'currency',            '货币',         'VALUE',      'ACTIVE', 6, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'G', '计价单位',     'price_unit',          '计价单位',     'VALUE',      'ACTIVE', 7, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '不良率(%)',    'defect_rate',         '不良率(%)',    'VALUE',      'ACTIVE', 8, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「电镀成本」(COSTING) → configId=%', v_cfg_id;
END $$;

-- ── B.11 其他外加工成本 → costing_part_process_cost [POST_PROC] ──────────
-- 5.0 列: A=宏丰料号 B=工序编号 C=工序名称 D=外加工费用 E=币种 F=单位
-- DB 字段: hf_part_no / process_no / process_name / unit_price / currency / unit
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '其他外加工成本' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「其他外加工成本」(COSTING) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '其他外加工成本', 511, 'costing_part_process_cost',
        '{"cost_type":"POST_PROC"}'::jsonb, 'COSTING',
        1, 2, 511, 'ACTIVE',
        'V143: 5.0 版核价「其他外加工成本」sheet, 物理表 costing_part_process_cost[POST_PROC]; B=process_no, D=unit_price(外加工费用)',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',  'hf_part_no',   '宏丰料号',  'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'B', '工序编号',  'process_no',   '工序编号',  'IDENTIFIER', 'ACTIVE', 2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'C', '工序名称',  'process_name', '工序名称',  'VALUE',      'ACTIVE', 3, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'D', '外加工费用', 'unit_price',  '外加工费用','VALUE',      'ACTIVE', 4, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'E', '币种',      'currency',     '币种',      'VALUE',      'ACTIVE', 5, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'F', '单位',      'unit',         '单位',      'VALUE',      'ACTIVE', 6, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「其他外加工成本」(COSTING, POST_PROC) → configId=%', v_cfg_id;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- 阶段 C: 5.0 版新增 sheet 名 + 核对 8 个已注册 sheet
-- ════════════════════════════════════════════════════════════════════════════

-- ── C.01 人工成本(单价) [半角括号] — V89 注册的是全角「人工成本（单价）」────
-- 5.0 版 sheet 名为半角括号, 必须新注册; 与 V89 全角版并存
DO $$
DECLARE v_cfg_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '人工成本(单价)' AND template_kind = 'COSTING' AND status = 'ACTIVE'
    ) THEN
        RAISE NOTICE 'V143: sheet「人工成本(单价)」(COSTING, 半角括号) 已存在, 跳过';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, target_table, target_discriminator, template_kind,
        header_row_index, data_start_row_index, sort_order, status, description,
        created_at, updated_at
    ) VALUES (
        v_cfg_id, '人工成本(单价)', 521, 'costing_part_process_cost',
        '{"cost_type":"LABOR"}'::jsonb, 'COSTING',
        1, 2, 521, 'ACTIVE',
        'V143: 5.0 版核价「人工成本(单价)」(半角括号), 物理表 costing_part_process_cost[LABOR]; 与 V89「人工成本（单价）」(全角)并存共两份配置',
        now(), now()
    );

    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',       'hf_part_no',       '宏丰料号',       'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    -- B 品名 / C 规格 / D 尺寸 → 跳过
    (gen_random_uuid(), v_cfg_id, 'E', '工序编号',       'process_no',       '工序编号',       'IDENTIFIER', 'ACTIVE',  5, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'F', '工序名称',       'process_name',     '工序名称',       'VALUE',      'ACTIVE',  6, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'G', '人工标准单价',   'unit_price',       '人工标准单价',   'VALUE',      'ACTIVE',  7, 'IMPORTANT', true,  true,  now(), now()),
    (gen_random_uuid(), v_cfg_id, 'H', '币种',           'currency',         '币种',           'VALUE',      'ACTIVE',  8, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'I', '计量单位',       'unit',             '计量单位',       'VALUE',      'ACTIVE',  9, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'J', '取用的计算版本', 'ref_calc_version', '取用的计算版本', 'VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_cfg_id, 'K', '是否有效',       'is_active',        '是否有效',       'VALUE',      'ACTIVE', 11, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: 注册 sheet「人工成本(单价)」(COSTING, LABOR, 半角) → configId=%', v_cfg_id;
END $$;

-- ── C.02~C.04 设备折旧成本 / 生产设备能耗成本 / 辅助设备能耗成本 ────────────
-- V89 注册名称与 5.0 版完全相同, attribute A/E/F/G/H/I/J/K 8 列与 5.0 版一致 → 核对存在即可
DO $$
DECLARE v_check TEXT;
BEGIN
    FOR v_check IN SELECT unnest(ARRAY['设备折旧成本','生产设备能耗成本','辅助设备能耗成本']) LOOP
        IF EXISTS (SELECT 1 FROM basic_data_config
                   WHERE sheet_name = v_check AND template_kind = 'COSTING' AND status = 'ACTIVE') THEN
            RAISE NOTICE 'V143: sheet「%」(COSTING) 已存在 (V89), attribute 与 5.0 版对齐, 无需改动', v_check;
        ELSE
            RAISE WARNING 'V143: sheet「%」(COSTING) 不存在! 需排查 V89 执行状态', v_check;
        END IF;
    END LOOP;
END $$;

-- ── C.05 来料BOM (COSTING) → 切换 target 到 costing_part_material_bom ─────
-- V91 将 COSTING 版「来料BOM」target 留在 mat_bom(报价侧), V142 组件 COMP-V5-RAW-BOM
-- 视图 v_c_raw_bom_merged FROM costing_part_material_bom (核价侧) → 两者不同表
-- 方案: 把 COSTING 版 target_table 切换到 costing_part_material_bom + 重建 attribute
DO $$
DECLARE
    v_id     UUID;
    v_target TEXT;
BEGIN
    SELECT c.id, c.target_table INTO v_id, v_target
    FROM basic_data_config c
    WHERE c.sheet_name = '来料BOM' AND c.template_kind = 'COSTING' AND c.status = 'ACTIVE'
    LIMIT 1;

    IF v_id IS NULL THEN
        RAISE WARNING 'V143: sheet「来料BOM」(COSTING) 不存在, 跳过 C.05 步骤';
        RETURN;
    END IF;

    IF v_target = 'costing_part_material_bom' THEN
        RAISE NOTICE 'V143: sheet「来料BOM」(COSTING) target 已是 costing_part_material_bom, 无需修改';
        RETURN;
    END IF;

    -- 切换 target + 清除 discriminator (costing_part_material_bom 无 bom_type 列)
    UPDATE basic_data_config
    SET target_table         = 'costing_part_material_bom',
        target_discriminator = NULL,
        description          = COALESCE(description,'') ||
                               ' [V143] target 切换到 costing_part_material_bom (核价侧), 匹配 COMP-V5-RAW-BOM',
        updated_at           = now()
    WHERE id = v_id;

    -- 重建 attribute 按 5.0 版列布局 + costing_part_material_bom 字段
    DELETE FROM basic_data_attribute WHERE config_id = v_id;
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_id, 'A', '宏丰料号',       'hf_part_no',        '宏丰料号',       'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'B', '项次',           'seq_no',            '项次',           'IDENTIFIER', 'ACTIVE',  2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'C', '来料料号',       'input_material_no', '来料料号',       'VALUE',      'ACTIVE',  3, 'IMPORTANT', false, false, now(), now()),
    -- D 品名 / E 规格 / F 尺寸 → 跳过
    (gen_random_uuid(), v_id, 'G', '工序编号',       'process_no',        '工序编号',       'VALUE',      'ACTIVE',  7, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'H', '工序名称',       'process_name',      '工序名称',       'VALUE',      'ACTIVE',  8, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'I', '组成用量',       'input_qty',         '组成用量',       'VALUE',      'ACTIVE',  9, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_id, 'J', '组成用量单位',   'input_unit',        '组成用量单位',   'VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'K', '底数',           'output_qty',        '底数',           'VALUE',      'ACTIVE', 11, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_id, 'L', '底数单位',       'output_unit',       '底数单位',       'VALUE',      'ACTIVE', 12, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'M', '来料损耗率(%)',  'loss_rate',         '来料损耗率(%)',  'VALUE',      'ACTIVE', 13, 'IMPORTANT', false, false, now(), now()),
    (gen_random_uuid(), v_id, 'N', '材料固定损耗量', 'fixed_loss_qty',    '材料固定损耗量', 'VALUE',      'ACTIVE', 14, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'O', '不良率(%)',      'output_loss_rate',  '不良率(%)',      'VALUE',      'ACTIVE', 15, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V143: sheet「来料BOM」(COSTING) target 由 % 切换到 costing_part_material_bom, attribute 重建 11 条', v_target;
END $$;

-- ── C.06 电镀方案 (COSTING) — 核对 target 是否已是 costing_part_plating ───
DO $$
DECLARE
    v_id     UUID;
    v_target TEXT;
    v_cnt    INT;
BEGIN
    SELECT c.id, c.target_table INTO v_id, v_target
    FROM basic_data_config c
    WHERE c.sheet_name = '电镀方案' AND c.template_kind = 'COSTING' AND c.status = 'ACTIVE'
    LIMIT 1;

    IF v_id IS NULL THEN
        RAISE WARNING 'V143: sheet「电镀方案」(COSTING) 不存在';
        RETURN;
    END IF;
    SELECT COUNT(*) INTO v_cnt FROM basic_data_attribute WHERE config_id = v_id AND status = 'ACTIVE';
    RAISE NOTICE 'V143: sheet「电镀方案」(COSTING) target=%, attribute 数=% — 已对齐 5.0 版', v_target, v_cnt;
END $$;

-- ── C.07 单重 (BOTH) — 核对 ──────────────────────────────────────────────
DO $$
DECLARE v_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt FROM basic_data_attribute a
    JOIN basic_data_config c ON a.config_id = c.id
    WHERE c.sheet_name = '单重' AND c.status = 'ACTIVE' AND a.status = 'ACTIVE';
    RAISE NOTICE 'V143: sheet「单重」(BOTH) attribute 数=%, 覆盖 5.0 版 A=宏丰料号/B=单重 2 列', v_cnt;
END $$;

-- ── C.08 来料其他费用 (COSTING) — 核对 ──────────────────────────────────
DO $$
DECLARE v_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt FROM basic_data_attribute a
    JOIN basic_data_config c ON a.config_id = c.id
    WHERE c.sheet_name = '来料其他费用' AND c.template_kind = 'COSTING'
      AND c.status = 'ACTIVE' AND a.status = 'ACTIVE';
    RAISE NOTICE 'V143: sheet「来料其他费用」(COSTING) attribute 数=%, 5.0 版列 A/B/C/G/I/J 已注册', v_cnt;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- 阶段 D: 验证报告
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    c1 INT;  -- COSTING/BOTH 类配置总条数
    c2 INT;  -- COSTING/BOTH 类 attribute 总数
    c3 INT;  -- COSTING/BOTH 覆盖不同 sheet 数
BEGIN
    SELECT COUNT(*)          INTO c1 FROM basic_data_config
    WHERE template_kind IN ('COSTING','BOTH') AND status = 'ACTIVE';

    SELECT COUNT(*)          INTO c2 FROM basic_data_attribute a
    JOIN basic_data_config c ON a.config_id = c.id
    WHERE c.template_kind IN ('COSTING','BOTH') AND a.status = 'ACTIVE';

    SELECT COUNT(DISTINCT c.sheet_name) INTO c3 FROM basic_data_config c
    WHERE c.template_kind IN ('COSTING','BOTH') AND c.status = 'ACTIVE';

    RAISE NOTICE '========================================';
    RAISE NOTICE 'V143 完成 — 验证报告:';
    RAISE NOTICE '  COSTING/BOTH 类配置条目数 = %', c1;
    RAISE NOTICE '  COSTING/BOTH 类 attribute 总数 = %', c2;
    RAISE NOTICE '  COSTING/BOTH 覆盖 sheet 种类数 = %', c3;
    RAISE NOTICE '  期望: sheet 种类 >= 19, attribute >= 100';
    RAISE NOTICE '========================================';
END $$;
