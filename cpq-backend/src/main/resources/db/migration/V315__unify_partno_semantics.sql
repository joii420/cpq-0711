-- V315: 统一报价/核价料号语义 —— material_no=销售料号, 新增 production_no=生产料号,
--       element_bom/element_bom_item 新增 material_part_no(材质料号)并纳入唯一键;
--       反做 V311 的 sales_part_no 反向设计(去唯一索引后缀 + DROP 列)。
-- 幂等自洽: 对"已应用 V311 的 dev DB"与"全新库"产出同一终态。
-- 反做以实测 DB 现状(uq_* 索引)为准, 非未跟踪的 V311 文件(ux_* 那版≠实际应用到库)。

-- ============ A. 加 production_no 描述列(不进唯一键) ============
ALTER TABLE unit_price        ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE material_bom      ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE material_bom_item ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE element_bom       ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);  -- 留空(该Sheet无生产料号列)
ALTER TABLE element_bom_item  ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);  -- 留空
ALTER TABLE capacity          ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE labor_rate        ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE production_energy ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE auxiliary_energy  ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
ALTER TABLE tooling_cost      ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
-- material_customer_map.production_no 由 V308 建, 不重复加。

-- ============ B. element_bom / element_bom_item 加 material_part_no(材质料号) ============
ALTER TABLE element_bom      ADD COLUMN IF NOT EXISTS material_part_no VARCHAR(32);
ALTER TABLE element_bom_item ADD COLUMN IF NOT EXISTS material_part_no VARCHAR(32);

-- ============ C. 逐表: 去唯一索引 sales 后缀 + DROP sales_part_no 列 ============
-- 手法: DROP INDEX(旧含sales / 或全新库原索引) -> DROP COLUMN sales_part_no ->
--       CREATE 无sales(element表额外含material_part_no)的唯一索引。

-- 1. unit_price
DROP INDEX IF EXISTS uq_unit_price;
ALTER TABLE unit_price DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_unit_price ON unit_price
  (system_type, price_type, COALESCE(cost_type,''::varchar), version_no, code,
   COALESCE(customer_no,''::varchar), COALESCE(supplier_no,''::varchar),
   COALESCE(finished_material_no,''::varchar), COALESCE(operation_no,''::varchar),
   COALESCE(seq_no,0), COALESCE(discount_order,0), COALESCE(item_seq,0),
   COALESCE(effective_date,'1900-01-01'::date));

-- 2. material_bom
DROP INDEX IF EXISTS uq_material_bom_v6;
ALTER TABLE material_bom DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_material_bom_v6 ON material_bom
  (system_type, customer_no, material_no, bom_version, COALESCE(characteristic,''::varchar));

-- 3. material_bom_item
DROP INDEX IF EXISTS uq_material_bom_item;
ALTER TABLE material_bom_item DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_material_bom_item ON material_bom_item
  (system_type, customer_no, material_no, COALESCE(characteristic,''::varchar),
   COALESCE(bom_version,''::varchar), COALESCE(seq_no,0),
   COALESCE(component_no,''::varchar), COALESCE(part_no,''::varchar));

-- 4. element_bom(唯一键纳入 material_part_no)
DROP INDEX IF EXISTS uq_element_bom_v6;
ALTER TABLE element_bom DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_element_bom_v6 ON element_bom
  (system_type, customer_no, material_no, COALESCE(material_part_no,''::varchar), characteristic);

-- 5. element_bom_item(唯一键纳入 material_part_no)
DROP INDEX IF EXISTS uq_element_bom_item;
ALTER TABLE element_bom_item DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_element_bom_item ON element_bom_item
  (system_type, customer_no, material_no, COALESCE(material_part_no,''::varchar), characteristic,
   COALESCE(seq_no,0), COALESCE(component_no,''::varchar), COALESCE(part_no,''::varchar));

-- 6. capacity
DROP INDEX IF EXISTS uq_capacity;
ALTER TABLE capacity DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_capacity ON capacity
  (system_type, material_no, process_no, resource_group_no, COALESCE(calc_version,''::varchar));

-- 7. labor_rate
DROP INDEX IF EXISTS uq_labor_rate;
ALTER TABLE labor_rate DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_labor_rate ON labor_rate
  (version_no, process_no, COALESCE(material_no,''::varchar), COALESCE(labor_grade,''::varchar));

-- 8. production_energy
DROP INDEX IF EXISTS uq_production_energy;
ALTER TABLE production_energy DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_production_energy ON production_energy
  (material_no, process_no, COALESCE(equipment_no,''::varchar), COALESCE(calc_version,''::varchar));

-- 9. auxiliary_energy
DROP INDEX IF EXISTS uq_auxiliary_energy;
ALTER TABLE auxiliary_energy DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_auxiliary_energy ON auxiliary_energy
  (material_no, process_no, COALESCE(calc_version,''::varchar));

-- 10. tooling_cost
DROP INDEX IF EXISTS uq_tooling_cost;
ALTER TABLE tooling_cost DROP COLUMN IF EXISTS sales_part_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_tooling_cost ON tooling_cost
  (material_no, process_no, seq_no, tooling_no);

-- 11. material_customer_map(无 sales 索引, 仅 DROP 列)
ALTER TABLE material_customer_map DROP COLUMN IF EXISTS sales_part_no;
