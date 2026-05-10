-- V69: mat_fee 唯一性扩到 seq_no + 数据修复 + COMP-0011 driver_path
--
-- 问题（详见 docs/反模式.md AP-13）：
--   1) uq_mat_fee_current = (customer_id, hf_part_no, fee_type) WHERE is_current=true
--      —— 同 part 同 fee_type 只允许 1 条 current。
--   2) VersionedWriter mat_fee 业务键 = [fee_type]，seq_no 进了 dataColumns。
--   3) Excel「成品其他费用」等 sheet 同 fee_type 下有多个 seq_no 行（财务/回收/材料/包装…）。
--      每次导入写新 seq_no 都把上一个 seq_no 标 is_current=false。
--      149 / 87 个历史版本里只剩最后一个 seq_no 的行 is_current=true。
--   4) COMP-0011 (其他费用) 的 data_driver_path 是空的，前端 driver 展开直接 skip。
--
-- 修复：
--   A. 替换唯一约束：(customer_id, hf_part_no, fee_type, seq_no) WHERE is_current=true。
--   B. 数据修复：每个 (customer_id, hf_part_no, fee_type, seq_no) 取最新版本设 is_current=true，
--      同 tuple 的旧版本设 is_current=false。
--   C. 设置 COMP-0011 (其他费用) 的 data_driver_path = mat_fee[fee_type='FINISHED_OTHER']。
--
-- 配套代码改动（同 PR 内）：
--   - VersionedWriter.java mat_fee TableMeta 业务键加 seq_no、dataColumns 去掉 seq_no。
--   - PathToSqlGenerator 对 mat_fee/mat_process/plating_fee 注入 is_current=true 默认过滤。

-- ── A. 重建唯一约束 ────────────────────────────────────────────────────
ALTER TABLE mat_fee DROP CONSTRAINT IF EXISTS uq_mat_fee_current;
DROP INDEX IF EXISTS uq_mat_fee_current;

CREATE UNIQUE INDEX uq_mat_fee_current
    ON mat_fee (customer_id, hf_part_no, fee_type, seq_no)
    WHERE is_current = true;

-- ── B. 数据修复：每个唯一元组的最新版本设为 current ────────────────────
-- 先全部清掉 is_current 标记
UPDATE mat_fee SET is_current = false WHERE is_current = true;

-- 再对每个 (customer_id, hf_part_no, fee_type, seq_no) 元组找最大 version 设回 current
UPDATE mat_fee m
   SET is_current = true
  FROM (
        SELECT DISTINCT ON (customer_id, hf_part_no, fee_type, seq_no)
               id
          FROM mat_fee
         ORDER BY customer_id, hf_part_no, fee_type, seq_no, version DESC
       ) latest
 WHERE m.id = latest.id;

-- ── C. 设置 COMP-0011 数据驱动路径 ────────────────────────────────────
UPDATE component
   SET data_driver_path = 'mat_fee[fee_type=''FINISHED_OTHER'']',
       updated_at = now()
 WHERE id = 'c5ffdd8c-0927-4125-92b9-c52b083c053b'
   AND (data_driver_path IS NULL OR data_driver_path = '');
