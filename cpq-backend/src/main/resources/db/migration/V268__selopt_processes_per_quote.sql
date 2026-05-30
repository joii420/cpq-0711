-- V268: 选配-工序列表 改为 per-quote(按报价行隔离),不再混入导入工序
--
-- 背景(用户反馈 + 业务确认):选配产品选的工序应"替换"展示、且"只影响当前报价单"(per-quote)、
-- "本行没配就空"。此前 Phase 2 把选配工序写进共享 material_bom_item(customer×material),
-- 而工序 mirror 按 customer+料号捞 → 导入工序(子件耦合行)与选配工序一起显示 = 报告的 bug。
--
-- 修法:
--  1) 选配工序改存报价行专属 quotation_line_process(line_item × process,后端 ConfigureProductService
--     在行创建后写入;saveDraft 已维护此表)。
--  2) composite_child_processes_mirror 改为读 quotation_line_process,按 :lineItemId 过滤
--     (:lineItemId 由 RuntimeContext.toNamedParams 暴露,DataLoader 已从 driver hint 注入 ctx.lineItem.id;
--      无行上下文 → :lineItemId 降级为 NULL → 0 行 = 本行没配则空)。
--     视图按名实时解析,模板快照只存视图名,故无需刷模板快照。
--  3) 清理上阶段写进 material_bom_item 的"选配工序污染行"(component_no IS NULL + operation_no)。
--
-- 注:本视图被 选配-组合工艺 跨组件借用(仅作行驱动,其字段读 mat_composite_process),
--     组合工艺 V6 承载是独立遗留问题(COMPOSITE 专属),不在本次范围。

-- 1) 清理 material_bom_item 选配工序污染行(导入行 component_no 必非空,故只删选配写的)
DELETE FROM material_bom_item
WHERE system_type = 'QUOTE'
  AND characteristic = 'ASSEMBLY'
  AND operation_no IS NOT NULL
  AND component_no IS NULL;

-- 2) 重写 composite_child_processes_mirror → 读 quotation_line_process(per-line)
UPDATE component_sql_view
SET sql_template = $V6$SELECT
    qli.product_part_no_snapshot AS hf_part_no,
    NULL::varchar AS child_hf_part_no,
    NULL::varchar AS child_part_name,
    0 AS child_seq,
    ROW_NUMBER() OVER (ORDER BY COALESCE(pr.sort_order, 999999), pr.code) AS seq_no,
    pr.code AS process_code,
    COALESCE(pm.process_name, pr.name, pr.code) AS assembly_process,
    q.customer_id AS customer_id
FROM quotation_line_process qlp
JOIN quotation_line_item qli ON qli.id = qlp.line_item_id
JOIN quotation q ON q.id = qli.quotation_id
JOIN process pr ON pr.id = qlp.process_id
LEFT JOIN process_master pm ON pm.process_no = pr.code
WHERE qlp.line_item_id = :lineItemId$V6$,
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_processes_mirror';
