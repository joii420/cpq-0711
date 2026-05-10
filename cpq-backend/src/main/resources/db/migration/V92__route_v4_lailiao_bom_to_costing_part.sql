-- V92: 把「来料BOM」sheet 路由到 costing_part_material_bom (核价路径), 不再走 mat_bom (报价单路径)
--
-- 问题: V91 把「来料BOM」 sheet 的列字母对齐到 v4 Excel, 但 target_table 仍是 mat_bom。
--       v4 数据语义是核价 BOM(组成用量可负 = 边角料/回收), 与 mat_bom 假设的"毛>净"冲突,
--       触发 BV-04 净用量(10) > 毛用量(-10.7) 阻塞。
--
-- 修复: 改 sheet 元数据指向 costing_part_material_bom (V76 创建, 字段含 input_qty/output_qty/
--       loss_rate/fixed_loss_qty 等, 与 v4 列布局对应), 同时重写 attributes 用核价表字段名。
--
-- 影响: 同名 sheet「来料BOM」如果有 v3 报价单基础数据格式在用, 改后将进核价表 → 数据错。
--       但 mat_bom 报价单路径还有「BOM清单」「元素BOM」等同义 sheet 可继续用。
--       v4 是用户当前主推的核价模板格式, 这个改动是正确的方向。
--
-- 列映射:
--   A 宏丰料号        → hf_part_no
--   B 项次            → seq_no
--   C 来料料号        → input_material_no
--   D 品名            → (skip, 表无此字段)
--   G 工序编号        → process_no
--   H 工序名称        → process_name
--   I 组成用量        → input_qty (可负数, 表示回收/边角料)
--   J 组成用量单位    → input_unit
--   K 底数            → output_qty (产出)
--   L 底数单位        → output_unit
--   M 来料损耗率(%)   → loss_rate
--   N 材料固定损耗量  → fixed_loss_qty
--   O 不良率(%)       → (skip, 表无此字段)
--   P 计算类型        → (skip, 表无此字段)

DO $$
DECLARE v_id UUID;
BEGIN
    SELECT id INTO v_id FROM basic_data_config
    WHERE sheet_name = '来料BOM' AND status = 'ACTIVE' LIMIT 1;
    IF v_id IS NULL THEN RAISE NOTICE 'V92: 来料BOM 不存在, 跳过'; RETURN; END IF;

    -- 改 sheet 元数据
    UPDATE basic_data_config
    SET target_table = 'costing_part_material_bom',
        target_discriminator = NULL,           -- 清掉 bom_type=INCOMING (核价表无此字段)
        template_kind = 'COSTING',             -- 限定到核价导入路径
        description = '核价基础数据 4.0 版「来料BOM」sheet 导入映射 (V92 改路由到 costing_part_material_bom)。'
                   || '组成用量可为负数(边角料/回收)。',
        updated_at = now()
    WHERE id = v_id;

    -- 重写 attributes (变量码改为 costing_part_material_bom 字段名)
    DELETE FROM basic_data_attribute WHERE config_id = v_id;
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order, importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
    (gen_random_uuid(), v_id, 'A', '宏丰料号',     'hf_part_no',          '宏丰料号',         'IDENTIFIER', 'ACTIVE',  1, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'B', '项次',         'seq_no',              '项次',             'IDENTIFIER', 'ACTIVE',  2, 'CRITICAL',  true,  true,  now(), now()),
    (gen_random_uuid(), v_id, 'C', '来料料号',     'input_material_no',   '来料料号',         'IDENTIFIER', 'ACTIVE',  3, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'G', '工序编号',     'process_no',          '工序编号',         'VALUE',      'ACTIVE',  7, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'H', '工序名称',     'process_name',        '工序名称',         'VALUE',      'ACTIVE',  8, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'I', '组成用量',     'input_qty',           '组成用量(可负)',   'VALUE',      'ACTIVE',  9, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'J', '组成用量单位', 'input_unit',          '组成用量单位',     'VALUE',      'ACTIVE', 10, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'K', '底数',         'output_qty',          '底数(产出)',       'VALUE',      'ACTIVE', 11, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'L', '底数单位',     'output_unit',         '底数单位',         'VALUE',      'ACTIVE', 12, 'NORMAL',    false, false, now(), now()),
    (gen_random_uuid(), v_id, 'M', '来料损耗率(%)','loss_rate',           '来料损耗率(%)',    'VALUE',      'ACTIVE', 13, 'IMPORTANT', true,  false, now(), now()),
    (gen_random_uuid(), v_id, 'N', '材料固定损耗量','fixed_loss_qty',     '材料固定损耗量',   'VALUE',      'ACTIVE', 14, 'NORMAL',    false, false, now(), now());
    RAISE NOTICE 'V92: 来料BOM 路由改为 costing_part_material_bom + 11 列重写';
END $$;
