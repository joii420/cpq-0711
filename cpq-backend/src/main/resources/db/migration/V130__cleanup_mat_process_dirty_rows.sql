-- V130: 清理 mat_process 中"非组成件"的脏数据
-- 起因: 历史导入时 sheet config 误把 fee 类 sheet 指向 mat_process, 现在 config 已修正
--       但残留行让前端「组成件」tab 显示中文 process_code (如「成品固定加工费」/「来料固定加工费」/「组装加工费」/「电镀费用」/「电镀方案」等).
-- 特征: assembly_process IS NULL AND component_name IS NULL AND
--       process_code 是中文 sheet 名而非工序编号 ("Z" + 数字).
-- 安全策略: 仅删除同时满足 (component_name IS NULL AND assembly_process IS NULL) 的行,
--           这种行不可能是合法 mat_process 数据 (BOM 必有 assembly_process 和 component_name).

DO $$
DECLARE
    v_before INT;
    v_after INT;
BEGIN
    SELECT COUNT(*) INTO v_before FROM mat_process WHERE is_current = true;

    DELETE FROM mat_process
    WHERE is_current = true
      AND component_name IS NULL
      AND assembly_process IS NULL;

    SELECT COUNT(*) INTO v_after FROM mat_process WHERE is_current = true;
    RAISE NOTICE 'V130: mat_process is_current rows: % → % (deleted % dirty rows)',
                 v_before, v_after, (v_before - v_after);
END $$;

-- 同步清理这些脏行对应的非 current 历史版本 (避免 versioning chain 断裂)
DO $$
DECLARE v_hist INT;
BEGIN
    DELETE FROM mat_process
    WHERE is_current = false
      AND component_name IS NULL
      AND assembly_process IS NULL;
    GET DIAGNOSTICS v_hist = ROW_COUNT;
    RAISE NOTICE 'V130: also deleted % non-current dirty rows', v_hist;
END $$;
