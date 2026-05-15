-- ============================================================
-- mat_bom 重复数据清理 — 处理 "input_material_no 空 vs 非空" 重复行
--
-- 背景:
--   mat_bom 写库用 UPDATE-then-INSERT 模式, WHERE 子句含
--     COALESCE(input_material_no,'') = COALESCE(:imno,'')
--   用户同一料号同项次先后两次导入, 一次填了 input_material_no
--   一次留空 → UPDATE 不命中 → INSERT 新行 → DB 多 1 行重复.
--
-- 用法 (两阶段):
--   阶段 1: 直接执行此脚本 → 输出 RAISE NOTICE 诊断 (重复组数量 + 详情)
--   阶段 2: 看清诊断结果后, 取消"清理 DELETE"段的注释, 重跑此脚本
--
-- 安全策略 (清理段):
--   仅删除 "input_material_no 为空 (NULL 或 '') 但同组有非空" 的行
--   即保留有数据的, 删除空的 (空数据是导入错误产物)
-- ============================================================

BEGIN;

-- ============================================================
-- 阶段 1: 诊断 — 列出重复组
-- ============================================================
DO $$
DECLARE
    v_dup_group_count INT;
    v_dup_row_total INT;
    r RECORD;
BEGIN
    -- 重复组定义: (bom_type, hf_part_no, seq_no, element_name) 相同但有 >=2 行
    SELECT COUNT(*), COALESCE(SUM(c), 0)
    INTO v_dup_group_count, v_dup_row_total
    FROM (
        SELECT bom_type, hf_part_no, seq_no, COALESCE(element_name,'') AS en, COUNT(*) AS c
        FROM mat_bom
        GROUP BY bom_type, hf_part_no, seq_no, COALESCE(element_name,'')
        HAVING COUNT(*) > 1
    ) t;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'mat_bom 重复组诊断';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '  重复组数: %', v_dup_group_count;
    RAISE NOTICE '  涉及行数: %', v_dup_row_total;

    IF v_dup_group_count > 0 THEN
        RAISE NOTICE '────────────────────────────────────────────';
        RAISE NOTICE '前 10 个重复组详情 (bom_type/hf_part_no/seq_no/element_name → input_material_no 集合):';
        FOR r IN
            SELECT bom_type, hf_part_no, seq_no, COALESCE(element_name,'(null)') AS en,
                   string_agg(COALESCE(input_material_no,'(null)'), ',' ORDER BY input_material_no NULLS LAST) AS imnos,
                   COUNT(*) AS c
            FROM mat_bom
            GROUP BY bom_type, hf_part_no, seq_no, COALESCE(element_name,'')
            HAVING COUNT(*) > 1
            ORDER BY hf_part_no, seq_no
            LIMIT 10
        LOOP
            RAISE NOTICE '  [%]%/seq=%/elem=% → imno集合=[%] (%行)',
                r.bom_type, r.hf_part_no, r.seq_no, r.en, r.imnos, r.c;
        END LOOP;
    END IF;
END $$;

-- ============================================================
-- 阶段 2: 清理 DELETE (默认注释禁用, 阅读诊断后取消注释执行)
--
-- 清理策略: 删除 "空 input_material_no 但同组有非空" 的行
-- ============================================================

/* 取消下方 /* 和 */ 即启用清理:
DO $$
DECLARE
    v_deleted INT;
BEGIN
    DELETE FROM mat_bom mb
    WHERE (mb.input_material_no IS NULL OR mb.input_material_no = '')
      AND EXISTS (
          SELECT 1 FROM mat_bom mb2
          WHERE mb2.bom_type = mb.bom_type
            AND mb2.hf_part_no = mb.hf_part_no
            AND mb2.seq_no = mb.seq_no
            AND COALESCE(mb2.element_name,'') = COALESCE(mb.element_name,'')
            AND mb2.input_material_no IS NOT NULL
            AND mb2.input_material_no != ''
            AND mb2.id != mb.id
      );
    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '清理完成: 删除 % 行 (空 input_material_no 但同组有非空)', v_deleted;
    RAISE NOTICE '════════════════════════════════════════════';
END $$;
*/

COMMIT;
