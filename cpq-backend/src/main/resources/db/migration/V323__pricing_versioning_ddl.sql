-- 核价导入版本升级 DDL（tesk-0709）。测试环境采「甲·清空重导」（需求 C14），不写存量拆分。

-- 1) production_energy 重构（C11）：加类型列 + 合并单价列
ALTER TABLE production_energy ADD COLUMN IF NOT EXISTS price_type  VARCHAR(24);
ALTER TABLE production_energy ADD COLUMN IF NOT EXISTS system_type VARCHAR(16) NOT NULL DEFAULT 'PRICING';
ALTER TABLE production_energy ADD COLUMN IF NOT EXISTS unit_price  DECIMAL(18,6);
TRUNCATE TABLE production_energy;
ALTER TABLE production_energy DROP COLUMN IF EXISTS depreciation_unit_price;
ALTER TABLE production_energy DROP COLUMN IF EXISTS energy_unit_price;
COMMENT ON COLUMN production_energy.price_type IS 'ENERGY=能耗 / DEPRECIATION=折旧;与 unit_price 配合区分来源 sheet';
COMMENT ON COLUMN production_energy.unit_price IS '单价(按 price_type 区分类型);与同名表 unit_price 不同命名空间';

-- 2) tooling_cost 加版本列 + system_type
ALTER TABLE tooling_cost ADD COLUMN IF NOT EXISTS calc_version VARCHAR(20);
ALTER TABLE tooling_cost ADD COLUMN IF NOT EXISTS system_type  VARCHAR(16) NOT NULL DEFAULT 'PRICING';

-- 3) 其余专用表补 system_type
ALTER TABLE labor_rate       ADD COLUMN IF NOT EXISTS system_type VARCHAR(16) NOT NULL DEFAULT 'PRICING';
ALTER TABLE auxiliary_energy ADD COLUMN IF NOT EXISTS system_type VARCHAR(16) NOT NULL DEFAULT 'PRICING';
-- exchange_rate_v6：全局无 material_no，轴用 (base_currency, target_currency)，不加 system_type。
