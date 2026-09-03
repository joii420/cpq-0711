-- V402 (task-260902 · B-22): quotation_line_process 加顺序列 seq_no
--
-- 背景（评审 P2-14）：AC-11 断言「工序顺序回填」、AC-19④ 要显示第一次的顺序，而本表实查只有
--   id | line_item_id | process_id | process_no
-- 四列，**没有任何顺序列**。读出点（QuotationService 组装 ProcessDTO）过去按 `ORDER BY id`
-- 或不带 ORDER BY —— id 是 gen_random_uuid()，顺序即随机；堆表在 UPDATE 后物理顺序还会变。
-- ⇒ 不加这一列，AC-11 会「今天绿、下周红」。
--
-- 纯新增列（可空、无默认回填约束），非破坏性。

ALTER TABLE quotation_line_process ADD COLUMN IF NOT EXISTS seq_no integer;

-- 存量行按 id 稳定化一次，避免 ORDER BY seq_no NULLS 混序（存量顺序本就不可考，这里只求稳定）。
UPDATE quotation_line_process q
SET seq_no = s.rn
FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY line_item_id ORDER BY id) AS rn
      FROM quotation_line_process) s
WHERE q.id = s.id AND q.seq_no IS NULL;

CREATE INDEX IF NOT EXISTS idx_qlp_line_seq ON quotation_line_process (line_item_id, seq_no);

COMMENT ON COLUMN quotation_line_process.seq_no IS
    'task-260902 B-22：工艺顺序，写入时按请求 processNos 数组下标 +1 赋值；所有读出点必须 ORDER BY seq_no。';
