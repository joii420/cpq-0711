-- V89: 注册核价基础数据 4.0 版 Excel 中 5 个原拆分 sheet 的导入映射
--
-- 来源: data/template/核价系统功能基础数据功能结构所需字段（4.0版）.xlsx
--
-- 背景:
--   4.0 版 Excel 把"工序成本"按业务类型拆成 4 个独立 sheet (人工/折旧/生产能耗/辅助能耗),
--   再加 1 个「耗材与包装材料」sheet。但系统现有的注册是"核价-工序成本(8类)" 1 个合并 sheet,
--   要求用户在 Excel 里手动加 cost_type 列来区分——不符合 4.0 版分 sheet 的设计。
--
-- 解决:
--   注册 5 个新 sheet, 都指向同一张物理表 costing_part_process_cost,
--   靠 target_discriminator 注入 cost_type, 用户的 Excel 不需要 cost_type 列。
--
-- 物理表 costing_part_process_cost (V76 创建) cost_type 枚举:
--   LABOR             - 人工成本
--   DEPRECIATION      - 设备折旧
--   ENERGY_DEDICATED  - 关联设备能耗 (= 生产能耗)
--   ENERGY_SHARED     - 共享设备能耗 (= 辅助/非生产能耗)
--   CONSUMABLE        - 耗材包装材料
--   ... (还有 MATERIAL_PROC / SEMI_FINISHED_PROC / POST_PROC 3 类暂未在 4.0 Excel 中)
--
-- 列结构 (5 个 sheet 共享, 仅列 G 标题随业务变):
--   A 宏丰料号        → hf_part_no       [必填]
--   E 工序编号        → process_no       [必填]
--   F 工序名称        → process_name
--   G 单价 (业务名)   → unit_price       [必填]
--   H 币种            → currency
--   I 计量单位        → unit
--   J 取用版本        → ref_calc_version
--   K 是否有效        → is_active
--   (B 品名 / C 规格 / D 尺寸 跳过, 只是给用户参考, 不入库)
--
-- 4.0 版另两个未注册 sheet:
--   * 「核价版本」(行: 元素价格版本=2000 等) → 涉及版本号→version_id (UUID) 的复杂查找,
--     超出 BDC 配置能力, 建议在后端 BasicDataImportServiceV5 里加专项处理, 不在 V89 范围
--   * 「汇总」是只读视图(v_costing_summary_full), 不需要导入

DO $$
DECLARE
    v_kind TEXT := 'COSTING';
    -- sheet 描述模板
    rec RECORD;
    v_config_id UUID;
BEGIN
    -- 5 个 sheet 的元数据 (sheet_name, cost_type, unit_price 列标题, ref_calc 列标题, sheet_index)
    FOR rec IN SELECT * FROM (VALUES
        ('人工成本（单价）',     'LABOR',            '人工标准单价', '取用的计算版本', 301),
        ('设备折旧成本',         'DEPRECIATION',     '折旧单价',     '取用的计算版本', 302),
        ('生产设备能耗成本',     'ENERGY_DEDICATED', '生产能耗单价', '取用的计算版本', 303),
        ('辅助设备能耗成本',     'ENERGY_SHARED',    '非生产能耗单价','取用的计算版本', 304),
        ('耗材与包装材料',       'CONSUMABLE',       '耗材成本单价', '取用的耗材版本', 305)
    ) AS t(sheet_name, cost_type, price_title, version_title, sheet_idx)
    LOOP
        -- 跳过已存在的同名 sheet
        IF EXISTS (SELECT 1 FROM basic_data_config
                   WHERE sheet_name = rec.sheet_name AND status = 'ACTIVE') THEN
            RAISE NOTICE 'V89: sheet「%」已存在, 跳过', rec.sheet_name;
            CONTINUE;
        END IF;

        v_config_id := gen_random_uuid();
        INSERT INTO basic_data_config (
            id, sheet_name, sheet_index, header_row_index, data_start_row_index,
            description, parent_config_id, join_columns, sort_order, status,
            target_table, target_discriminator, template_kind, created_at, updated_at
        ) VALUES (
            v_config_id, rec.sheet_name, rec.sheet_idx, 1, 2,
            format('核价基础数据 4.0 版「%s」sheet 导入映射。物理表 costing_part_process_cost, 鉴别 cost_type=%s。',
                   rec.sheet_name, rec.cost_type),
            NULL, '[]'::jsonb, rec.sheet_idx, 'ACTIVE',
            'costing_part_process_cost',
            jsonb_build_object('cost_type', rec.cost_type),
            v_kind, now(), now()
        );

        -- 列定义: A/E/F/G/H/I/J/K (B 品名 / C 规格 / D 尺寸 跳过)
        INSERT INTO basic_data_attribute (
            id, config_id, column_letter, column_title, variable_code, variable_label,
            data_type, status, sort_order, importance_level, affects_calculation, is_required,
            created_at, updated_at
        ) VALUES
        (gen_random_uuid(), v_config_id, 'A', '宏丰料号',     'hf_part_no',       '宏丰料号',     'IDENTIFIER', 'ACTIVE', 1, 'IMPORTANT',   true,  true,  now(), now()),
        (gen_random_uuid(), v_config_id, 'E', '工序编号',     'process_no',       '工序编号',     'IDENTIFIER', 'ACTIVE', 5, 'IMPORTANT',   true,  true,  now(), now()),
        (gen_random_uuid(), v_config_id, 'F', '工序名称',     'process_name',     '工序名称',     'VALUE',      'ACTIVE', 6, 'NORMAL', false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'G', rec.price_title, 'unit_price',      rec.price_title,'VALUE',      'ACTIVE', 7, 'IMPORTANT',   true,  true,  now(), now()),
        (gen_random_uuid(), v_config_id, 'H', '币种',         'currency',         '币种',         'VALUE',      'ACTIVE', 8, 'NORMAL', false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'I', '计量单位',     'unit',             '计量单位',     'VALUE',      'ACTIVE', 9, 'NORMAL', false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'J', rec.version_title, 'ref_calc_version', rec.version_title, 'VALUE', 'ACTIVE', 10, 'NORMAL', false, false, now(), now()),
        (gen_random_uuid(), v_config_id, 'K', '是否有效',     'is_active',        '是否有效',     'VALUE',      'ACTIVE', 11, 'NORMAL', false, false, now(), now());

        RAISE NOTICE 'V89: 已注册 sheet「%」(cost_type=%, configId=%)',
                     rec.sheet_name, rec.cost_type, v_config_id;
    END LOOP;
END $$;
