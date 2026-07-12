-- V327: 主数据维护-核价基础数据维护（task-0712 · B1 / 需求 C11）。
-- 为需纳入"料号核价"维护的核价版本化表加 source 列，标记该版本来源：
--   IMPORT = Excel 导入（默认，存量行与导入 handler 不写此列 → 走 DEFAULT）
--   MANUAL = 维护页手工编辑保存
-- 语义：source 是"版本元信息"描述列，仅用于版本切换下拉展示（C11），
--       不参与 VersionedV6Writer 指纹比对（由 saveGroup 放 descriptorColumns，写入但不比对），
--       避免 IMPORT↔MANUAL 造成虚假升版。
-- 主从 BOM 的 source 落"主表"（material_bom / element_bom）即可，子表（*_item）不加。
-- 幂等：ADD COLUMN IF NOT EXISTS + 默认 'IMPORT'，对存量行安全；
--       unit_price 为 QUOTE/PRICING 共用表，加列默认 IMPORT 对报价侧无副作用（不改报价 handler）。

ALTER TABLE unit_price        ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'IMPORT';
ALTER TABLE capacity          ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'IMPORT';
ALTER TABLE labor_rate        ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'IMPORT';
ALTER TABLE production_energy ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'IMPORT';
ALTER TABLE auxiliary_energy  ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'IMPORT';
ALTER TABLE tooling_cost      ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'IMPORT';
ALTER TABLE material_bom      ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'IMPORT';
ALTER TABLE element_bom       ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'IMPORT';

COMMENT ON COLUMN unit_price.source        IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';
COMMENT ON COLUMN capacity.source          IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';
COMMENT ON COLUMN labor_rate.source        IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';
COMMENT ON COLUMN production_energy.source IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';
COMMENT ON COLUMN auxiliary_energy.source  IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';
COMMENT ON COLUMN tooling_cost.source      IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑 (task-0712 C11)';
COMMENT ON COLUMN material_bom.source      IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑; 主从 BOM 落主表 (task-0712 C11)';
COMMENT ON COLUMN element_bom.source       IS '版本来源: IMPORT=导入 / MANUAL=维护页手工编辑; 主从 BOM 落主表 (task-0712 C11)';
