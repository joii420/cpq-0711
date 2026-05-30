-- V275: composite_child_elements_mirror 修正 COMPOSITE 分支 JOIN 方向
--
-- 背景:V273 给 elements_mirror 加 COMPOSITE 分支时 JOIN 写成
--   element_bom_item ebi ON ebi.material_no = parent.component_no
-- 但 element_bom_item 的索引语义是 (hf_part_no = 子件 partNo, material_no = 实际原料码如 9995/9996)。
-- 因此 SIMPLE 分支匹配是 ebi.hf_part_no = qli partNo,COMPOSITE 也应同向:
--   ebi.hf_part_no = parent.component_no(parent ASSEMBLY 行的 component_no 即子件 partNo)。
-- 结果验证:QT-1469 CFG-COMBO-000005 修前 [选配-元素含量]=0 行,修后期望=8 行(2 子件 × 4 元素)。

UPDATE component_sql_view
SET sql_template = $SQL$
SELECT
    ebi.hf_part_no                              AS hf_part_no,
    NULL::uuid                                  AS line_item_id,
    ebi.material_no                             AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0                                           AS child_seq,
    ebi.seq_no                                  AS seq_no,
    ebi.component_no                            AS element_name,
    ebi.content                                 AS composition_pct
FROM element_bom_item ebi
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
WHERE ebi.system_type = 'QUOTE'
  AND ebi.hf_part_no IS NOT NULL
  AND ebi.customer_no = :customerCode
  AND ebi.characteristic = (
      SELECT MAX(ebi2.characteristic) FROM element_bom_item ebi2
      WHERE ebi2.system_type = ebi.system_type
        AND ebi2.customer_no = ebi.customer_no
        AND ebi2.material_no = ebi.material_no
        AND ebi2.hf_part_no = ebi.hf_part_no
  )

UNION ALL

SELECT
    parent.material_no                          AS hf_part_no,
    NULL::uuid                                  AS line_item_id,
    ebi.material_no                             AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0                                           AS child_seq,
    ebi.seq_no                                  AS seq_no,
    ebi.component_no                            AS element_name,
    ebi.content                                 AS composition_pct
FROM material_bom_item parent
JOIN element_bom_item ebi ON ebi.hf_part_no = parent.component_no
                          AND ebi.customer_no = parent.customer_no
                          AND ebi.system_type = parent.system_type
                          AND ebi.hf_part_no IS NOT NULL
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
WHERE parent.system_type = 'QUOTE'
  AND parent.characteristic = 'ASSEMBLY'
  AND parent.customer_no = :customerCode
  AND ebi.characteristic = (
      SELECT MAX(ebi2.characteristic) FROM element_bom_item ebi2
      WHERE ebi2.system_type = ebi.system_type
        AND ebi2.customer_no = ebi.customer_no
        AND ebi2.material_no = ebi.material_no
        AND ebi2.hf_part_no = ebi.hf_part_no
  )
$SQL$,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_elements_mirror';
