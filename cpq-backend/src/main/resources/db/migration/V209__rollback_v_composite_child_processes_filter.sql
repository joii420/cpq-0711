-- V209: 回滚 V208 的 IS NULL 过滤 — 恢复 V207 视图形态
--
-- 背景: V208 向两个 UNION ALL 分支各加了 quotation_line_item_id IS NULL 过滤,
--       意图把视图语义固定为"主数据层"。但该过滤过激:
--       COMPOSITE 子件专属工序行 (quotation_line_item_id IS NOT NULL) 全部被排除,
--       导致 ConfigureProductService 通过 lineItemId 查子件工序时视图返 0 行 →
--       COMPOSITE 报价卡渲染工序 Tab 全空。
--
-- 用户决策 (2026-05-21): 回滚 V208, 视图回到 V207 形态。
--   - 含所有行 (IS NULL + IS NOT NULL 的 quotation_line_item_id 均保留)
--   - quotation_line_item_id 列保留 (ImplicitJoinRewriter 能感知并注入谓词)
--   - 当调用方携带 lineItemId 时, ImplicitJoinRewriter 注入等值谓词,
--     只返回该 lineItem 专属工序行 (预期 1 行)
--   - 当调用方不携带 lineItemId (详情页 ReadonlyProductCard) 时, 谓词不注入,
--     视图返全量行 → 详情页工序 Tab 行数可能偏多 (已知副作用, 用户接受)
--
-- 不变量:
--   - Bug B (SIMPLE 独立产品 mat_process driver 直接路径) 不受此视图影响
--   - v_composite_child_materials / v_composite_child_elements 不改动
--   - COMPOSITE spec composite-product-flow 回到 V207 时的 PASS 状态

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
