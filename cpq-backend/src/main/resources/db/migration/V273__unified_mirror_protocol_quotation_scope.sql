-- V273: 统一渲染协议 — 所有 mirror 视图改用 :quotationId + :customerCode + 外层 :hfPartNos
--
-- 背景:之前 composite_child_processes_mirror 用 :lineItemId 标量,导致它无法跟材质/元素
-- 走"产品卡片维度合桶"的同一逻辑,也违背了"SQL 视图同一渲染协议"的原则。
-- ConfigureSnapshotService 之前对 composite_child_* 类视图做"按子件聚合"应用层兜底,产生
-- COMPOSITE 父行快照"14 行子件物料"(QT-1469 bug)。
--
-- 本次:① composite_child_processes_mirror 改用 :quotationId,UNION ALL 处理 SIMPLE/PART 自展开
-- + COMPOSITE 父展开子件聚合;② composite_child_elements_mirror 加 UNION ALL 的 COMPOSITE 分支;
-- ③ 两个视图都输出 line_item_id 列,供后续 Bug B(同 partNo 多卡)按 (hf_part_no, line_item_id)
-- 元组分发使用。
--
-- 配套 Java 改动(同一 commit):
-- - 新增 QuotationIdContext (ThreadLocal),DataLoader 把 :quotationId 绑到 RuntimeContext;
-- - ComponentResource.batchExpand + ConfigureSnapshotService 包 QuotationIdContext.set/clear;
-- - ConfigureSnapshotService 删除 composite_child_* 按子件聚合分支,所有 (line × component) 统一调用;
-- - BatchExpandDriverRequest.Task + 前端 useDriverExpansions 加 quotationId 字段透传。
--
-- 部署后须 refresh-snapshot 受影响的 COMPOSITE 父行,把旧聚合快照重算成新协议的结果。

-- ========================================================================
-- 1) composite_child_processes_mirror:用 :quotationId,UNION ALL(SIMPLE/PART + COMPOSITE)
-- ========================================================================
UPDATE component_sql_view
SET sql_template = $V6$SELECT
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
  AND parent_qli.composite_type = 'COMPOSITE'$V6$,
    declared_columns = '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"line_item_id","dataType":"uuid","nullable":true},{"name":"child_hf_part_no","dataType":"varchar","nullable":true},{"name":"child_part_name","dataType":"varchar","nullable":true},{"name":"child_seq","dataType":"int4","nullable":true},{"name":"seq_no","dataType":"int8","nullable":true},{"name":"process_code","dataType":"varchar","nullable":true},{"name":"assembly_process","dataType":"varchar","nullable":true},{"name":"customer_id","dataType":"uuid","nullable":true}]'::jsonb,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_processes_mirror';

-- ========================================================================
-- 2) composite_child_elements_mirror:加 UNION ALL 的 COMPOSITE 分支,输出 line_item_id (NULL)
-- ========================================================================
UPDATE component_sql_view
SET sql_template = $V6$SELECT
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
JOIN element_bom_item ebi ON ebi.material_no = parent.component_no
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
  )$V6$,
    declared_columns = '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"line_item_id","dataType":"uuid","nullable":true},{"name":"child_hf_part_no","dataType":"varchar","nullable":true},{"name":"child_part_name","dataType":"varchar","nullable":true},{"name":"child_seq","dataType":"int4","nullable":true},{"name":"seq_no","dataType":"int4","nullable":true},{"name":"element_name","dataType":"varchar","nullable":true},{"name":"composition_pct","dataType":"numeric","nullable":true}]'::jsonb,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_elements_mirror';
