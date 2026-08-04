-- task-0801 公式计算精度优化 — B7：12 个金额列 numeric(18,4) → numeric(20,6)
-- 放大转换，存量值自动补零，无数据丢失。整数位由 14 位保持为 14 位（18-4=14 → 20-6=14）。
-- 已提交/已冻结的存量报价单不重算（快照数据结构不变，本迁移只放宽列 scale，不改任何数值）。

ALTER TABLE quotation                     ALTER COLUMN total_amount          TYPE numeric(20,6);
ALTER TABLE quotation                     ALTER COLUMN original_amount       TYPE numeric(20,6);
ALTER TABLE quotation                     ALTER COLUMN tax_amount            TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN subtotal              TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN discount_base_amount  TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN line_unit_price       TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN line_final_price      TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN line_discount_amount  TYPE numeric(20,6);
ALTER TABLE quotation_line_item           ALTER COLUMN line_total_amount     TYPE numeric(20,6);
ALTER TABLE quotation_line_component_data ALTER COLUMN subtotal              TYPE numeric(20,6);
ALTER TABLE costing_order                 ALTER COLUMN total_amount          TYPE numeric(20,6);
ALTER TABLE costing_order                 ALTER COLUMN costing_total_amount  TYPE numeric(20,6);
