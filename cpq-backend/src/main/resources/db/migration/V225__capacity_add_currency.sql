-- ============================================================
-- V225: capacity 表补 currency 列
-- V220 建表时遗漏；Q14 组装加工费 Handler 写入此列报"字段不存在"
-- 方案文档 §14 显式有"货币"列对应 capacity.currency
-- ============================================================

ALTER TABLE capacity ADD COLUMN IF NOT EXISTS currency VARCHAR(10);

COMMENT ON COLUMN capacity.currency IS '货币（V225 补；组装加工费等场景用）';
