-- ============================================================
-- V246: composite_child_elements_mirror 加「仅取最新 characteristic」过滤
--
-- 背景：Q04ElementBomHandler 设计为「同一主件元素组成变更时 characteristic 递增 +1
--   (2000 → 2001 → 2002 ...)」。但 V245 mirror SQL 直接查 element_bom_item 全表，
--   导致同一料号多个版本叠加返回 (3120012574 返 20 行 = 5 版本 × 4 元素)。
-- 修复：在 WHERE 加子查询「characteristic = MAX(characteristic) per (customer, material)」。
-- 同步更新 v_composite_child_elements PG view 保持一致。
-- ============================================================

UPDATE component_sql_view SET
    sql_template = $V6$
SELECT
    ebi.hf_part_no   AS hf_part_no,
    ebi.material_no  AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0                AS child_seq,
    ebi.seq_no       AS seq_no,
    ebi.component_no AS element_name,
    ebi.content      AS composition_pct
FROM element_bom_item ebi
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
WHERE ebi.system_type = 'QUOTE'
  AND ebi.hf_part_no IS NOT NULL
  AND ebi.characteristic = (
      SELECT MAX(ebi2.characteristic) FROM element_bom_item ebi2
      WHERE ebi2.system_type = ebi.system_type
        AND ebi2.customer_no = ebi.customer_no
        AND ebi2.material_no = ebi.material_no
  )
$V6$,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_elements_mirror';

-- 同步 PG view
DROP VIEW IF EXISTS v_composite_child_elements CASCADE;

CREATE VIEW v_composite_child_elements AS
SELECT
    ebi.hf_part_no   AS hf_part_no,
    ebi.material_no  AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0                AS child_seq,
    ebi.seq_no       AS seq_no,
    ebi.component_no AS element_name,
    ebi.content      AS composition_pct,
    c.id             AS customer_id,
    NULL::uuid       AS quotation_line_item_id
FROM element_bom_item ebi
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
LEFT JOIN customer         c ON c.code         = ebi.customer_no
WHERE ebi.system_type = 'QUOTE'
  AND ebi.hf_part_no IS NOT NULL
  AND ebi.characteristic = (
      SELECT MAX(ebi2.characteristic) FROM element_bom_item ebi2
      WHERE ebi2.system_type = ebi.system_type
        AND ebi2.customer_no = ebi.customer_no
        AND ebi2.material_no = ebi.material_no
  );

COMMENT ON VIEW v_composite_child_elements IS
    'V246: 加 characteristic=MAX 过滤，避免多版本叠加返回。';
