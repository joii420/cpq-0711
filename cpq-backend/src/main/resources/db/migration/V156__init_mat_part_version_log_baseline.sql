-- ============================================================
-- V156: 初始化 mat_part_version_log 基线 (version=2000)
--
-- 设计背景:
--   V152 建表 mat_part_version_log 后空表. 为让 S2 PartVersionService 上线时
--   能查到"基线版本", 这里给每个有效 (customer_product_no, hf_part_no) 写一条
--   version=2000 的记录.
--
--   content_hash 留 NULL: 此刻没有 PartVersionService.computeFingerprint 实现,
--                          S2 上线时补算并 UPDATE 回填.
--   diff_summary 留 NULL: 基线不是 diff 结果, 是"原点".
--
-- 排除:
--   - mat_customer_part_mapping 中 customer_product_no 或 hf_part_no 为 NULL 的行
--     (它们不参与版本管理, V151 已注释说明)
-- ============================================================

INSERT INTO mat_part_version_log
    (customer_product_no, hf_part_no, version, content_hash, diff_summary, source_excel, source_import_id, created_at, created_by)
SELECT DISTINCT
    m.customer_product_no,
    m.hf_part_no,
    2000          AS version,
    NULL::char(32) AS content_hash,
    NULL::jsonb    AS diff_summary,
    'V156-baseline' AS source_excel,
    NULL::uuid     AS source_import_id,
    now()          AS created_at,
    NULL::uuid     AS created_by
FROM mat_customer_part_mapping m
WHERE m.customer_product_no IS NOT NULL
  AND m.hf_part_no IS NOT NULL
ON CONFLICT (customer_product_no, hf_part_no, version) DO NOTHING;

-- ----- 校验输出 -----
DO $$
DECLARE
    v_log_cnt INT;
    v_mapping_cnt INT;
    v_diff INT;
BEGIN
    SELECT COUNT(*) INTO v_log_cnt FROM mat_part_version_log WHERE version = 2000;
    SELECT COUNT(DISTINCT (customer_product_no, hf_part_no)) INTO v_mapping_cnt
        FROM mat_customer_part_mapping
        WHERE customer_product_no IS NOT NULL AND hf_part_no IS NOT NULL;
    v_diff := v_mapping_cnt - v_log_cnt;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'V156 完成: 基线初始化';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '  mat_part_version_log 行 (version=2000): %', v_log_cnt;
    RAISE NOTICE '  mapping 中 (cpn,hf) 唯一对数: %', v_mapping_cnt;
    RAISE NOTICE '  差异 (应为 0): %', v_diff;

    IF v_diff <> 0 THEN
        RAISE WARNING 'V156: 基线行数 ≠ mapping 唯一对数, 请检查 ON CONFLICT 是否屏蔽了重复';
    END IF;
END $$;
