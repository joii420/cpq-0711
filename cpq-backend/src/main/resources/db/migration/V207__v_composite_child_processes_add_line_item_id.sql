-- V207: 修复 Bug B — v_composite_child_processes 视图暴露 quotation_line_item_id 列
--
-- 根因: ImplicitJoinRewriter 向视图注入 quotation_line_item_id 谓词时,
--       先查 information_schema.columns 确认视图是否含该列。
--       旧视图 SELECT 列表里没有 quotation_line_item_id → 谓词不注入 →
--       查出全量主数据(52行) → 两个独立产品工序 Tab 互相串。
--
-- 修法: 在两个 UNION ALL 分支都 SELECT proc.quotation_line_item_id,
--       视图投影增加该列后, ImplicitJoinRewriter 会正确注入
--       AND quotation_line_item_id = '<UUID>' 谓词,PG 把谓词推入
--       mat_process 物理表过滤, 只返回该 lineItem 专属工序行(1行)。
--
-- 兼容性: lineItemId = null 时 ImplicitJoinRewriter 不注入该谓词,
--         行为与旧版完全等价 (IS NULL 行 + IS NOT NULL 行都返回 → 聚合全量数据)。
--         lineItemId 非空且专属行存在时只返回专属行。

CREATE OR REPLACE VIEW v_composite_child_processes AS
SELECT
    asy.hf_part_no,
    COALESCE(asy.child_part_no, asy.input_material_no) AS child_hf_part_no,
    COALESCE(mp_child.part_name, asy.input_material_name, asy.child_part_no, asy.input_material_no) AS child_part_name,
    asy.seq_no AS child_seq,
    proc.seq_no,
    proc.process_code,
    proc.assembly_process,
    proc.customer_id,
    proc.quotation_line_item_id
FROM mat_bom asy
JOIN mat_process proc
    ON proc.hf_part_no::text = COALESCE(asy.child_part_no, asy.input_material_no)::text
    AND proc.is_current = true
LEFT JOIN mat_part mp_child
    ON mp_child.part_no::text = COALESCE(asy.child_part_no, asy.input_material_no)::text
WHERE asy.bom_type::text = 'ASSEMBLY'::text

UNION ALL

SELECT
    proc.hf_part_no,
    proc.hf_part_no AS child_hf_part_no,
    COALESCE(mp.part_name, proc.hf_part_no) AS child_part_name,
    0 AS child_seq,
    proc.seq_no,
    proc.process_code,
    proc.assembly_process,
    proc.customer_id,
    proc.quotation_line_item_id
FROM mat_process proc
LEFT JOIN mat_part mp ON mp.part_no::text = proc.hf_part_no::text
WHERE proc.is_current = true
  AND NOT (EXISTS (
      SELECT 1
      FROM mat_bom
      WHERE mat_bom.hf_part_no::text = proc.hf_part_no::text
        AND mat_bom.bom_type::text = 'ASSEMBLY'::text
  ));
