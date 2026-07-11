-- production_energy 唯一索引补 price_type + system_type 维度（tesk-0709 Task 2 前置修复）。
-- 背景：V323 给 production_energy 加了 price_type/system_type/unit_price 列，但旧唯一索引
-- uq_production_energy 仍是 (material_no, process_no, equipment_no, calc_version)，不含 price_type。
-- P09(DEPRECIATION)/P10(ENERGY) 各自独立版本化写入后，同料号同工序在首次导入时 calc_version 都
-- 是系统生成的 '2000'，若不区分 price_type 会撞唯一索引（已用手工 INSERT 复现验证）。
-- 新索引维度对齐 VersionedV6Writer 的 SYSTEM_TYPE_SCOPED 契约：groupKey={system_type, material_no,
-- price_type}，process_no 属于 content 列（同一组内多行按 process_no 区分）。

DROP INDEX IF EXISTS uq_production_energy;

CREATE UNIQUE INDEX uq_production_energy ON production_energy (
    system_type,
    material_no,
    process_no,
    COALESCE(price_type, ''),
    COALESCE(equipment_no, ''),
    COALESCE(calc_version, '')
);
