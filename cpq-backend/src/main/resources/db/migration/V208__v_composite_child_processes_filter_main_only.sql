-- V208: 修复退化 — v_composite_child_processes 无 lineItemId 上下文时行数膨胀
--
-- 根因: V207 视图两个 UNION ALL 分支未过滤 quotation_line_item_id IS NULL,
--       当调用方不携带 lineItemId 上下文（详情页 ReadonlyProductCard、
--       COMPOSITE 父级聚合渲染）时, ImplicitJoinRewriter 不注入等值谓词,
--       视图返回全量 mat_process 行（含历史各 lineItemId 专属行）→ 行数膨胀。
--
-- 具体症状:
--   - COMPOSITE spec: 选配-工序列表渲染 13 行（应为主数据 IS NULL 行数）
--   - bug-c spec: 工序 Tab 详情 14 行 vs 编辑 6 行（详情页无 lineItem.id → 全量返回）
--
-- 修法: 两个 UNION ALL 分支均加 mp.quotation_line_item_id IS NULL 过滤。
--       视图语义固定为"主数据层", quotation_line_item_id 列恒为 NULL。
--       当 ImplicitJoinRewriter 注入 quotation_line_item_id = :lid 谓词时,
--       NULL = :lid 为 false → 0 行（COMPOSITE 父级走主数据不应有专属工序,
--       专属工序由各子件 hf_part_no 直接查 mat_process 处理）。
--       当调用方不注入谓词时（详情页 / 聚合渲染）只返主数据 IS NULL 行, 符合预期。
--
-- 不变量:
--   - Bug B (SIMPLE 独立产品 mat_process driver 直接路径) 不受此视图影响
--   - v_composite_child_materials / v_composite_child_elements 不改动
--   - V207 暴露的 quotation_line_item_id 列保留 (ImplicitJoinRewriter 仍能感知该列)

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
    AND proc.quotation_line_item_id IS NULL
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
  AND proc.quotation_line_item_id IS NULL
  AND NOT (EXISTS (
      SELECT 1
      FROM mat_bom
      WHERE mat_bom.hf_part_no::text = proc.hf_part_no::text
        AND mat_bom.bom_type::text = 'ASSEMBLY'::text
  ));
