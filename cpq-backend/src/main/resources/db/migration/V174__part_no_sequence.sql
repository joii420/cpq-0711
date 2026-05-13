-- V174__part_no_sequence.sql
-- 选配料号自增计数器(Phase 2 的 AutoAllocatePartNoProvider 使用)
-- 命名规则: CFG-{symbol}-{6 位 0 填充流水}
--   独立产品:    CFG-AgCu-000001 / CFG-AgNi-000007 / ...
--   组合产品父:   CFG-COMBO-000001 / ...

CREATE TABLE IF NOT EXISTS part_no_sequence (
    prefix   VARCHAR(32) PRIMARY KEY,
    next_val BIGINT      NOT NULL DEFAULT 1
);

COMMENT ON TABLE part_no_sequence IS '选配料号自增计数器';
COMMENT ON COLUMN part_no_sequence.prefix IS '前缀(含尾连字符,如 CFG-AgCu-)';
COMMENT ON COLUMN part_no_sequence.next_val IS '下一个流水号;Provider 取后 +1';

INSERT INTO part_no_sequence (prefix, next_val) VALUES
  ('CFG-AgCu-', 1),
  ('CFG-AgNi-', 1),
  ('CFG-AgSnO₂-', 1),
  ('CFG-AgCdO-', 1),
  ('CFG-AgW-', 1),
  ('CFG-CuCr-', 1),
  ('CFG-AgPd-', 1),
  ('CFG-AuAg-', 1),
  ('CFG-COMBO-', 1)
ON CONFLICT (prefix) DO NOTHING;
