-- V70: mat_fee 唯一性继续扩到 dim_* 维度 + 数据修复
--
-- 背景（详见 docs/反模式.md AP-15）：
--   V69 已经把唯一约束从 (customer_id, hf_part_no, fee_type) 扩成
--   (customer_id, hf_part_no, fee_type, seq_no)，但 Excel 的"成品其他费用 / 来料其他费用 /
--   组装加工费年降"等 sheet 业务上允许同一 (fee_type, seq_no) 下多行——
--   通过 dim_input_material_no / dim_input_material_name / dim_element_name /
--   dim_assembly_process / dim_sub_seq_no 区分（典型："来料 H85 + 包装费 / 材料管理费 /
--   回收费" 三行都是 INCOMING_OTHER seq_no=2）。
--   导入时这 3 行连续覆盖同一个业务键，is_current 永远只剩最后一条；用户复导入
--   全部采纳新值后差异仍然回弹，无法收敛。
--
-- 修复：
--   A. 重建唯一约束：覆盖 (customer_id, hf_part_no, fee_type, seq_no, 5 个 dim_*)。
--      NULL 维度用 COALESCE 归一化，保证 NULL 也是稳定键。
--   B. 数据修复：对每个完整键的最新版本设回 is_current=true，先清掉所有现有 current
--      避免 UNIQUE 冲突；再以 DISTINCT ON 取最大 version 行重置。
--
-- 配套代码（同 PR）：
--   - VersionedWriter mat_fee TableMeta 业务键加 dim_*。
--   - BasicDataImportServiceV5.writePhysicalTables mat_fee 写库时 bk 加 dim_*。
--   - BasicDataImportServiceV5.detectCustomerDataConflicts 的 dbMap key 与 rowKey 加 dim_*。

-- ── A. 重建唯一约束 ────────────────────────────────────────────────────
DROP INDEX IF EXISTS uq_mat_fee_current;
ALTER TABLE mat_fee DROP CONSTRAINT IF EXISTS uq_mat_fee_current;

CREATE UNIQUE INDEX uq_mat_fee_current
    ON mat_fee (
        customer_id, hf_part_no, fee_type, seq_no,
        COALESCE(dim_input_material_no, ''),
        COALESCE(dim_input_material_name, ''),
        COALESCE(dim_element_name, ''),
        COALESCE(dim_assembly_process, ''),
        COALESCE(dim_sub_seq_no, -1)
    )
    WHERE is_current = true;

-- ── B. 数据修复：每个完整键的最新版本设回 current ───────────────────
UPDATE mat_fee SET is_current = false WHERE is_current = true;

UPDATE mat_fee m
   SET is_current = true
  FROM (
        SELECT DISTINCT ON (
                customer_id, hf_part_no, fee_type, seq_no,
                COALESCE(dim_input_material_no, ''),
                COALESCE(dim_input_material_name, ''),
                COALESCE(dim_element_name, ''),
                COALESCE(dim_assembly_process, ''),
                COALESCE(dim_sub_seq_no, -1)
               ) id
          FROM mat_fee
         ORDER BY customer_id, hf_part_no, fee_type, seq_no,
                  COALESCE(dim_input_material_no, ''),
                  COALESCE(dim_input_material_name, ''),
                  COALESCE(dim_element_name, ''),
                  COALESCE(dim_assembly_process, ''),
                  COALESCE(dim_sub_seq_no, -1),
                  version DESC
       ) latest
 WHERE m.id = latest.id;
