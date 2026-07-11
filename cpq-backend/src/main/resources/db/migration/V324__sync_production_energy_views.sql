-- 同步 production_energy 重构后的 component_sql_view 模板；活库当前 0 行命中（防御性，未来这些视图被创建时生效）。
-- 注意 replace 顺序：先替换含 "IS NOT NULL" 的长串（WHERE 子句），再替换裸列引用（SELECT 里的 pe.xxx_unit_price AS unit_price），
-- 避免短串替换提前吃掉长串匹配目标（V255 第 161-227 行原文核对过）。

UPDATE component_sql_view
   SET sql_template = replace(
         replace(sql_template, 'pe.depreciation_unit_price IS NOT NULL', $$pe.price_type = 'DEPRECIATION'$$),
         'pe.depreciation_unit_price', 'pe.unit_price')
 WHERE sql_view_name = 'v12_depreciation_cost';

UPDATE component_sql_view
   SET sql_template = replace(
         replace(sql_template, 'pe.energy_unit_price IS NOT NULL', $$pe.price_type = 'ENERGY'$$),
         'pe.energy_unit_price', 'pe.unit_price')
 WHERE sql_view_name = 'v12_energy_prod_cost';
