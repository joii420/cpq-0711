-- ============================================================
-- V234: v_composite_child_elements / composite_child_elements_mirror 元素 JOIN 路径修复
--
-- 问题: V6 element_bom_item.material_no = 投入料号 (如 9996)，而非父料号 (3120012574)
--       原视图 JOIN ASSEMBLY material_bom_item.component_no (= 8881~8885 组成件)
--       与元素 BOM 主件 9996 无交集 → 元素列表 0 行
--
-- 修复: 父级使用 material_bom_item characteristic IS NULL (Q03 物料BOM 子表)
--       它的 component_no = 投入料号 (9996/9997)，与 element_bom_item.material_no 匹配
-- ============================================================

-- 1. PG view
DROP VIEW IF EXISTS v_composite_child_elements CASCADE;

CREATE VIEW v_composite_child_elements AS
SELECT
    parent.material_no  AS hf_part_no,             -- 成品料号 3120012574
    ebi.material_no     AS child_hf_part_no,       -- 投入料号 9996
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    parent.seq_no       AS child_seq,
    ebi.seq_no          AS seq_no,
    ebi.component_no    AS element_name,           -- 元素 Ag/Ni
    ebi.content         AS composition_pct,
    c.id                AS customer_id,
    NULL::uuid          AS quotation_line_item_id
FROM material_bom_item parent                       -- Q03 物料BOM 子表 (characteristic IS NULL)
JOIN element_bom_item  ebi
  ON ebi.system_type   = 'QUOTE'
 AND ebi.customer_no   = parent.customer_no
 AND ebi.material_no   = parent.component_no        -- 投入料号匹配
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
LEFT JOIN customer         c ON c.code         = parent.customer_no
WHERE parent.system_type   = 'QUOTE'
  AND parent.characteristic IS NULL                 -- 只取物料BOM 主表，不取 ASSEMBLY
UNION ALL
-- 当料号自身就是元素BOM 主件时（无父级物料BOM）
SELECT
    ebi.material_no  AS hf_part_no,
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
  AND NOT EXISTS (
      SELECT 1 FROM material_bom_item parent2
      WHERE parent2.system_type = 'QUOTE'
        AND parent2.characteristic IS NULL
        AND parent2.customer_no = ebi.customer_no
        AND parent2.component_no = ebi.material_no
  );

-- 2. mirror SQL 视图同步（component_sql_view）
UPDATE component_sql_view SET
    sql_template = $V6$
SELECT
    parent.material_no  AS hf_part_no,
    ebi.material_no     AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    parent.seq_no       AS child_seq,
    ebi.seq_no          AS seq_no,
    ebi.component_no    AS element_name,
    ebi.content         AS composition_pct
FROM material_bom_item parent
JOIN element_bom_item  ebi
  ON ebi.system_type = 'QUOTE'
 AND ebi.customer_no = parent.customer_no
 AND ebi.material_no = parent.component_no
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
WHERE parent.system_type   = 'QUOTE'
  AND parent.characteristic IS NULL
UNION ALL
SELECT
    ebi.material_no  AS hf_part_no,
    ebi.material_no  AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0                AS child_seq,
    ebi.seq_no       AS seq_no,
    ebi.component_no AS element_name,
    ebi.content      AS composition_pct
FROM element_bom_item ebi
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
WHERE ebi.system_type = 'QUOTE'
  AND NOT EXISTS (
      SELECT 1 FROM material_bom_item parent2
      WHERE parent2.system_type = 'QUOTE'
        AND parent2.characteristic IS NULL
        AND parent2.customer_no = ebi.customer_no
        AND parent2.component_no = ebi.material_no
  )
$V6$,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_elements_mirror';
