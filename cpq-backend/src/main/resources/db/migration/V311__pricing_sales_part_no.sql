-- V311: 核价销售料号维度 —— 11 表加列 + 10 uq 末尾追加 COALESCE(sales_part_no,'')
-- 权威规格 docs/superpowers/specs/2026-07-07-核价销售料号维度落库-design.md §1

-- ========== 1. 加列（11 表，VARCHAR(32) NULL）==========
ALTER TABLE unit_price            ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);
ALTER TABLE material_bom          ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);
ALTER TABLE material_bom_item     ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);
ALTER TABLE element_bom           ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);
ALTER TABLE element_bom_item      ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);
ALTER TABLE capacity              ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);
ALTER TABLE labor_rate            ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);
ALTER TABLE production_energy     ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);
ALTER TABLE auxiliary_energy      ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);
ALTER TABLE tooling_cost          ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);
ALTER TABLE material_customer_map ADD COLUMN IF NOT EXISTS sales_part_no VARCHAR(32);

-- ========== 2. uq 重建（末尾追加 COALESCE(sales_part_no,'')，其余列逐字照抄现役）==========
DROP INDEX IF EXISTS uq_unit_price;
CREATE UNIQUE INDEX uq_unit_price ON unit_price(
    system_type, price_type, COALESCE(cost_type, ''), version_no, code,
    COALESCE(customer_no, ''), COALESCE(supplier_no, ''), COALESCE(finished_material_no, ''),
    COALESCE(operation_no, ''), COALESCE(seq_no, 0), COALESCE(discount_order, 0),
    COALESCE(item_seq, 0), COALESCE(effective_date, DATE '1900-01-01'),
    COALESCE(sales_part_no, ''));

DROP INDEX IF EXISTS uq_material_bom_v6;
CREATE UNIQUE INDEX uq_material_bom_v6 ON material_bom(
    system_type, customer_no, material_no, bom_version, COALESCE(characteristic, ''),
    COALESCE(sales_part_no, ''));

DROP INDEX IF EXISTS uq_material_bom_item;
CREATE UNIQUE INDEX uq_material_bom_item ON material_bom_item(
    system_type, customer_no, material_no, COALESCE(characteristic, ''),
    COALESCE(bom_version, ''), COALESCE(seq_no, 0), COALESCE(component_no, ''),
    COALESCE(part_no, ''), COALESCE(sales_part_no, ''));

DROP INDEX IF EXISTS uq_element_bom_v6;
CREATE UNIQUE INDEX uq_element_bom_v6 ON element_bom(
    system_type, customer_no, material_no, characteristic, COALESCE(sales_part_no, ''));

DROP INDEX IF EXISTS uq_element_bom_item;
CREATE UNIQUE INDEX uq_element_bom_item ON element_bom_item(
    system_type, customer_no, material_no, characteristic, COALESCE(seq_no, 0),
    COALESCE(component_no, ''), COALESCE(part_no, ''), COALESCE(sales_part_no, ''));

DROP INDEX IF EXISTS uq_capacity;
CREATE UNIQUE INDEX uq_capacity ON capacity(
    system_type, material_no, process_no, resource_group_no, COALESCE(calc_version, ''),
    COALESCE(sales_part_no, ''));

DROP INDEX IF EXISTS uq_labor_rate;
CREATE UNIQUE INDEX uq_labor_rate ON labor_rate(
    version_no, process_no, COALESCE(material_no, ''), COALESCE(labor_grade, ''),
    COALESCE(sales_part_no, ''));

DROP INDEX IF EXISTS uq_production_energy;
CREATE UNIQUE INDEX uq_production_energy ON production_energy(
    material_no, process_no, COALESCE(equipment_no, ''), COALESCE(calc_version, ''),
    COALESCE(sales_part_no, ''));

DROP INDEX IF EXISTS uq_auxiliary_energy;
CREATE UNIQUE INDEX uq_auxiliary_energy ON auxiliary_energy(
    material_no, process_no, COALESCE(calc_version, ''), COALESCE(sales_part_no, ''));

DROP INDEX IF EXISTS uq_tooling_cost;
CREATE UNIQUE INDEX uq_tooling_cost ON tooling_cost(
    material_no, process_no, seq_no, tooling_no, COALESCE(sales_part_no, ''));

COMMENT ON COLUMN unit_price.sales_part_no IS 'V311 销售料号(报价料号)维度；费用行必填(handler 校验)，P01/P02 全局行 NULL';
