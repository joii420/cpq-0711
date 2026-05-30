-- V274: composite_child_processes_mirror 补 BOM fallback 分支
--
-- 背景:V273 把 COMPOSITE 聚合改成视图内 UNION ALL,但只用 parent_line_item_id JOIN child。
-- 实际场景:configure saveDraft 全量重建后子件行 parent_line_item_id 留 NULL(tempParentIndex 二阶段未接上),
-- 删除前的 ConfigureSnapshotService.resolveCompositeChildren 也有同样的两段语义:
--   (1) parent_line_item_id 直连;  (2) 失败回退 BOM (material_bom_item ASSEMBLY material_no→component_no)。
-- 本迁移给 processes mirror 也加上第 (2) 段,与 elements mirror 的两段语义对齐。
--
-- 防双计:BOM fallback 分支限定 child_qli.parent_line_item_id IS NULL,与直连分支正交。

UPDATE component_sql_view
SET sql_template = $SQL$
SELECT
    qli.product_part_no_snapshot AS hf_part_no,
    qli.id                       AS line_item_id,
    NULL::varchar                AS child_hf_part_no,
    NULL::varchar                AS child_part_name,
    0                            AS child_seq,
    ROW_NUMBER() OVER (PARTITION BY qli.id ORDER BY COALESCE(pr.sort_order, 999999), pr.code) AS seq_no,
    pr.code                      AS process_code,
    COALESCE(pm.process_name, pr.name, pr.code) AS assembly_process,
    q.customer_id                AS customer_id
FROM quotation_line_process qlp
JOIN quotation_line_item qli ON qli.id = qlp.line_item_id
JOIN quotation q ON q.id = qli.quotation_id
JOIN process pr ON pr.id = qlp.process_id
LEFT JOIN process_master pm ON pm.process_no = pr.code
WHERE qli.quotation_id = :quotationId
  AND qli.composite_type IN ('SIMPLE','PART')

UNION ALL

SELECT
    parent_qli.product_part_no_snapshot AS hf_part_no,
    parent_qli.id                       AS line_item_id,
    child_qli.product_part_no_snapshot  AS child_hf_part_no,
    child_qli.product_part_no_snapshot  AS child_part_name,
    0                                   AS child_seq,
    ROW_NUMBER() OVER (PARTITION BY parent_qli.id ORDER BY child_qli.sort_order, COALESCE(pr.sort_order, 999999), pr.code) AS seq_no,
    pr.code                             AS process_code,
    COALESCE(pm.process_name, pr.name, pr.code) AS assembly_process,
    q.customer_id                       AS customer_id
FROM quotation_line_item parent_qli
JOIN quotation_line_item child_qli ON child_qli.parent_line_item_id = parent_qli.id
JOIN quotation q ON q.id = parent_qli.quotation_id
JOIN quotation_line_process qlp ON qlp.line_item_id = child_qli.id
JOIN process pr ON pr.id = qlp.process_id
LEFT JOIN process_master pm ON pm.process_no = pr.code
WHERE parent_qli.quotation_id = :quotationId
  AND parent_qli.composite_type = 'COMPOSITE'

UNION ALL

SELECT
    parent_qli.product_part_no_snapshot AS hf_part_no,
    parent_qli.id                       AS line_item_id,
    child_qli.product_part_no_snapshot  AS child_hf_part_no,
    child_qli.product_part_no_snapshot  AS child_part_name,
    0                                   AS child_seq,
    ROW_NUMBER() OVER (PARTITION BY parent_qli.id ORDER BY bom.seq_no, COALESCE(pr.sort_order, 999999), pr.code) AS seq_no,
    pr.code                             AS process_code,
    COALESCE(pm.process_name, pr.name, pr.code) AS assembly_process,
    q.customer_id                       AS customer_id
FROM quotation_line_item parent_qli
JOIN quotation q ON q.id = parent_qli.quotation_id
JOIN customer cu ON cu.id = q.customer_id
JOIN material_bom_item bom ON bom.material_no = parent_qli.product_part_no_snapshot
    AND bom.system_type = 'QUOTE'
    AND bom.characteristic = 'ASSEMBLY'
    AND bom.customer_no = cu.code
    AND bom.component_no IS NOT NULL
JOIN quotation_line_item child_qli ON child_qli.product_part_no_snapshot = bom.component_no
    AND child_qli.quotation_id = parent_qli.quotation_id
    AND child_qli.composite_type = 'PART'
    AND child_qli.parent_line_item_id IS NULL
JOIN quotation_line_process qlp ON qlp.line_item_id = child_qli.id
JOIN process pr ON pr.id = qlp.process_id
LEFT JOIN process_master pm ON pm.process_no = pr.code
WHERE parent_qli.quotation_id = :quotationId
  AND parent_qli.composite_type = 'COMPOSITE'
$SQL$,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_processes_mirror';
