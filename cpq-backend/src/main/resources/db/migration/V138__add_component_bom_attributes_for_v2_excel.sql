-- V138: 给 sheet「组成件BOM」补 attribute 配置 (适配 2.0 版 Excel 拆分)
--
-- 起因: 2.0 版 Excel 把 1.0 版的「组成件BOM及单价」(15 列含供应商/单价/运费) 拆成两个 sheet:
--       - 「组成件BOM」(9 列, 仅基础 BOM 工序信息)         ← 写 mat_process
--       - 「组成件其他费用」(15 列, 工序级费用项)          ← 需新建 fee_type=COMPONENT_OTHER (单独立项)
--
--       basic_data_config 中「组成件BOM」sheet 已注册 (target=mat_process), 但 attribute 表
--       count=0 → V58 parseExcel 命中 if(attrs.isEmpty()) skip 分支 → 整个 sheet 被静默跳过 →
--       3120012580 的 mat_process 数据 0 写入 → 报价单「组成件」tab 空。
--
-- 实施: 给 sheetName='组成件BOM' (kind=BOTH, target=mat_process) 的现有 config 补 9 条 attribute,
--       对应 2.0 版 Excel 9 列。其他 5 列 (供应商/单价/运费/货币/单位) 留待「组成件其他费用」单独立项。

DO $$
DECLARE
    v_cfg_id UUID;
    v_existing INT;
BEGIN
    -- 找到「组成件BOM」(注意: 不是「组成件BOM及单价」) 的 config_id
    SELECT id INTO v_cfg_id FROM basic_data_config
     WHERE sheet_name = '组成件BOM'
       AND status = 'ACTIVE'
       AND target_table = 'mat_process'
     LIMIT 1;

    IF v_cfg_id IS NULL THEN
        RAISE EXCEPTION 'V138: 未找到 sheet「组成件BOM」的 ACTIVE config (target=mat_process)';
    END IF;

    -- 检查是否已经有 attribute (幂等保护)
    SELECT COUNT(*) INTO v_existing FROM basic_data_attribute
     WHERE config_id = v_cfg_id AND status = 'ACTIVE';
    IF v_existing > 0 THEN
        RAISE NOTICE 'V138: sheet「组成件BOM」已有 % 条 attribute, 跳过插入', v_existing;
        RETURN;
    END IF;

    -- 插入 9 条 attribute (对应 2.0 版 Excel 列结构)
    INSERT INTO basic_data_attribute (
        id, config_id, column_letter, column_title, variable_code, variable_label,
        data_type, status, sort_order,
        importance_level, affects_calculation, is_required,
        created_at, updated_at
    ) VALUES
        (gen_random_uuid(), v_cfg_id, 'A', '宏丰料号',     'hf_part_no',         '宏丰料号',     'IDENTIFIER', 'ACTIVE', 1, 'CRITICAL',  false, true,  now(), now()),
        (gen_random_uuid(), v_cfg_id, 'B', '项次',         'seq_no',             '项次',         'IDENTIFIER', 'ACTIVE', 2, 'IMPORTANT', false, true,  now(), now()),
        (gen_random_uuid(), v_cfg_id, 'C', '工序编号',     'process_code',       '工序编号',     'VALUE',      'ACTIVE', 3, 'IMPORTANT', false, false, now(), now()),
        (gen_random_uuid(), v_cfg_id, 'D', '组装工序',     'assembly_process',   '组装工序',     'VALUE',      'ACTIVE', 4, 'IMPORTANT', false, false, now(), now()),
        (gen_random_uuid(), v_cfg_id, 'E', '项次(2)',      'sub_seq_no',         '子项次',       'IDENTIFIER', 'ACTIVE', 5, 'IMPORTANT', false, false, now(), now()),
        (gen_random_uuid(), v_cfg_id, 'F', '组成件料号',   'component_part_no',  '组成件料号',   'VALUE',      'ACTIVE', 6, 'NORMAL',    false, false, now(), now()),
        (gen_random_uuid(), v_cfg_id, 'G', '组成件名称',   'component_name',     '组成件名称',   'VALUE',      'ACTIVE', 7, 'NORMAL',    false, false, now(), now()),
        (gen_random_uuid(), v_cfg_id, 'H', '组成数量',     'quantity',           '组成数量',     'VALUE',      'ACTIVE', 8, 'NORMAL',    true,  false, now(), now()),
        (gen_random_uuid(), v_cfg_id, 'I', '组成单位',     'quantity_unit',      '组成单位',     'VALUE',      'ACTIVE', 9, 'NORMAL',    false, false, now(), now());

    RAISE NOTICE 'V138: 给 sheet「组成件BOM」补 9 条 attribute 完成 (config_id=%)', v_cfg_id;
END $$;
