-- V277: 给所有「带版本的 V6 基础表」统一新增生效标志 is_current。
-- 设计方案 §5.2。已有 is_effective 的表(capacity/tooling_cost/material_version_mgmt)保留该列不改名，
-- 但后续生效判定统一以 is_current 为权威。material_bom_item 无版本列，加列以跟随主表生效翻转(§10#1)。
-- 幂等：ADD COLUMN IF NOT EXISTS；存量行 DEFAULT TRUE 即视为当前生效。

ALTER TABLE unit_price            ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE capacity              ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE material_bom          ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE material_bom_item     ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE element_bom           ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE element_bom_item      ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE plating_scheme        ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE production_energy     ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE auxiliary_energy      ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tooling_cost          ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE production_consumable ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE packaging_consumable  ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE electricity_price     ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE labor_rate            ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE exchange_rate_v6      ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE fee_config            ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE material_version_mgmt ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;

-- 部分索引：加速「分组键 + 当前生效」查询（版本化写入工具按此过滤）
CREATE INDEX IF NOT EXISTS idx_unit_price_current
  ON unit_price (finished_material_no, operation_no) WHERE is_current = TRUE;
CREATE INDEX IF NOT EXISTS idx_capacity_current
  ON capacity (material_no, process_no, resource_group_no) WHERE is_current = TRUE;
