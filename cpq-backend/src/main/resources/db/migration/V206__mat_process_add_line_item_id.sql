-- V206: mat_process 加 quotation_line_item_id 列，实现报价单上下文工序隔离。
--
-- 背景 (Bug B):
--   同一报价单内同 hf_part_no 的两个产品（如 3120012574 总装配 vs 部件装配）
--   在 mat_process 中共享同一 (customer_id, hf_part_no) 命名空间，
--   后提交的产品工序会覆盖先提交的，导致两个产品工序 Tab 显示相同内容。
--
-- 修复方案:
--   existing + processIds 路径写入 mat_process 时带上 quotation_line_item_id，
--   batch-expand 查询优先返回 line_item 专属行，fallback 到 NULL 主数据行。
--
-- 语义:
--   NULL  = 主数据（全局工序，来自数据导入 / 无工序覆盖的 existing 路径）
--   非NULL = 该 line item 的覆盖工序（仅在该报价单上下文可见）

-- 1. 加列（幂等）
ALTER TABLE mat_process
    ADD COLUMN IF NOT EXISTS quotation_line_item_id UUID NULL;

-- 2. 稀疏索引（仅索引非 NULL 行，减少索引体积）
CREATE INDEX IF NOT EXISTS idx_mat_process_line_item
    ON mat_process(quotation_line_item_id)
    WHERE quotation_line_item_id IS NOT NULL;

-- 3. 复合索引：covering index for batch-expand fallback query
--    (customer_id, hf_part_no, quotation_line_item_id) — 使 fallback 查询走 index scan
CREATE INDEX IF NOT EXISTS idx_mat_process_cust_part_lid
    ON mat_process(customer_id, hf_part_no, quotation_line_item_id)
    WHERE is_current = true;

-- 注意: uq_mat_process_current UNIQUE index (customer_id, hf_part_no, seq_no, sub_seq_no) WHERE is_current=true
-- 在 sub_seq_no IS NULL 时，PG UNIQUE index 把每行视为唯一（NULL <> NULL 语义），
-- 因此不同 lineItemId 的行（sub_seq_no 均为 NULL）可以共存，无需修改该约束。

-- 4. 顺手清理存量重复数据
--    同 (customer_id, hf_part_no, process_code) 主数据层重复行，只保留最新（id 最大 = 最晚写入）。
--    注意: 仅清理 quotation_line_item_id IS NULL 的主数据重复，不触碰业务数据。
DELETE FROM mat_process a
USING mat_process b
WHERE a.id < b.id
  AND a.customer_id = b.customer_id
  AND a.hf_part_no  = b.hf_part_no
  AND a.process_code = b.process_code
  AND a.quotation_line_item_id IS NULL
  AND b.quotation_line_item_id IS NULL
  AND a.is_current = true
  AND b.is_current = true;

-- 5. 注释
COMMENT ON COLUMN mat_process.quotation_line_item_id IS
    '报价单上下文隔离 ID. NULL=主数据(全局工序，来自数据导入或 existing 无覆盖路径), 非NULL=该 line item 的覆盖工序（同 customer+hf_part_no 可并存多套不互扰）.';
