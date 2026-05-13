-- V171__seed_material_recipes.sql
-- 12 个材质配方 + 元素含量 seed (对齐 docs/html/添加产品.html 原型 mats 数组)
-- NOTE: V170 已被 seed_b_formulas_for_excel_template 占用，本脚本顺延至 V171

-- Safety guard: 若表中已有数据则全部跳过 (ON CONFLICT DO NOTHING)
INSERT INTO material_recipe (code, symbol, name, spec_label, recipe_type, sort_order) VALUES
  ('AgCu85',  'AgCu',   '银铜合金',   '85/15', 'locked',   10),
  ('AgCu90',  'AgCu',   '银铜合金',   '90/10', 'locked',   20),
  ('AgNi90',  'AgNi',   '银镍合金',   '90/10', 'editable', 30),
  ('AgNi95',  'AgNi',   '银镍合金',   '95/5',  'editable', 40),
  ('AgSnO2',  'AgSnO₂', '银氧化锡',   '88/12', 'partial',  50),
  ('AgSnO2b', 'AgSnO₂', '银氧化锡',   '85/15', 'partial',  60),
  ('AgCdO',   'AgCdO',  '银氧化镉',   '85/15', 'locked',   70),
  ('AgW60',   'AgW',    '银钨合金',   '60/40', 'editable', 80),
  ('AgW72',   'AgW',    '银钨合金',   '72/28', 'editable', 90),
  ('CuCr',    'CuCr',   '铜铬合金',   '99/1',  'partial',  100),
  ('AgPd',    'AgPd',   '银钯合金',   '70/30', 'locked',   110),
  ('AuAg',    'AuAg',   '金银合金',   '75/25', 'locked',   120)
ON CONFLICT (code) DO NOTHING;

-- locked 类: 全锁定 (no min/max, is_locked=true)
-- Locked recipes: AgCu85, AgCu90, AgCdO, AgPd, AuAg  => 10 element rows
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, is_locked, sort_order)
SELECT id, 'Ag',  '银',     85.0, true, 1 FROM material_recipe WHERE code='AgCu85'
UNION ALL SELECT id, 'Cu',  '铜',     15.0, true, 2 FROM material_recipe WHERE code='AgCu85'
UNION ALL SELECT id, 'Ag',  '银',     90.0, true, 1 FROM material_recipe WHERE code='AgCu90'
UNION ALL SELECT id, 'Cu',  '铜',     10.0, true, 2 FROM material_recipe WHERE code='AgCu90'
UNION ALL SELECT id, 'Ag',  '银',     85.0, true, 1 FROM material_recipe WHERE code='AgCdO'
UNION ALL SELECT id, 'CdO', '氧化镉', 15.0, true, 2 FROM material_recipe WHERE code='AgCdO'
UNION ALL SELECT id, 'Ag',  '银',     70.0, true, 1 FROM material_recipe WHERE code='AgPd'
UNION ALL SELECT id, 'Pd',  '钯',     30.0, true, 2 FROM material_recipe WHERE code='AgPd'
UNION ALL SELECT id, 'Au',  '金',     75.0, true, 1 FROM material_recipe WHERE code='AuAg'
UNION ALL SELECT id, 'Ag',  '银',     25.0, true, 2 FROM material_recipe WHERE code='AuAg'
ON CONFLICT (recipe_id, element_code) DO NOTHING;

-- editable 类: 全可调 (min/max 均有值, is_locked=false)
-- Editable recipes: AgNi90, AgNi95, AgW60, AgW72  => 8 element rows
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order)
SELECT id, 'Ag', '银',  90.0, 85.0, 95.0, false, 1 FROM material_recipe WHERE code='AgNi90'
UNION ALL SELECT id, 'Ni', '镍',  10.0,  5.0, 15.0, false, 2 FROM material_recipe WHERE code='AgNi90'
UNION ALL SELECT id, 'Ag', '银',  95.0, 90.0, 98.0, false, 1 FROM material_recipe WHERE code='AgNi95'
UNION ALL SELECT id, 'Ni', '镍',   5.0,  2.0, 10.0, false, 2 FROM material_recipe WHERE code='AgNi95'
UNION ALL SELECT id, 'Ag', '银',  60.0, 50.0, 70.0, false, 1 FROM material_recipe WHERE code='AgW60'
UNION ALL SELECT id, 'W',  '钨',  40.0, 30.0, 50.0, false, 2 FROM material_recipe WHERE code='AgW60'
UNION ALL SELECT id, 'Ag', '银',  72.0, 65.0, 80.0, false, 1 FROM material_recipe WHERE code='AgW72'
UNION ALL SELECT id, 'W',  '钨',  28.0, 20.0, 35.0, false, 2 FROM material_recipe WHERE code='AgW72'
ON CONFLICT (recipe_id, element_code) DO NOTHING;

-- partial 类: 主元素锁定, 次元素可调  => 9 element rows (3+3+3)

-- AgSnO2 (88/12): Ag 锁定, SnO2+In2O3 可调
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, is_locked, sort_order)
SELECT id, 'Ag', '银', 88.0, true, 1 FROM material_recipe WHERE code='AgSnO2'
ON CONFLICT (recipe_id, element_code) DO NOTHING;

INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order)
SELECT id, 'SnO2',  '氧化锡',  12.0,  8.0, 16.0, false, 2 FROM material_recipe WHERE code='AgSnO2'
UNION ALL SELECT id, 'In2O3', '氧化铟',   0.5,  0.0,  1.5, false, 3 FROM material_recipe WHERE code='AgSnO2'
ON CONFLICT (recipe_id, element_code) DO NOTHING;

-- AgSnO2b (85/15): Ag 锁定, SnO2+In2O3 可调
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, is_locked, sort_order)
SELECT id, 'Ag', '银', 85.0, true, 1 FROM material_recipe WHERE code='AgSnO2b'
ON CONFLICT (recipe_id, element_code) DO NOTHING;

INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order)
SELECT id, 'SnO2',  '氧化锡',  15.0, 12.0, 18.0, false, 2 FROM material_recipe WHERE code='AgSnO2b'
UNION ALL SELECT id, 'In2O3', '氧化铟',   0.0,  0.0,  1.0, false, 3 FROM material_recipe WHERE code='AgSnO2b'
ON CONFLICT (recipe_id, element_code) DO NOTHING;

-- CuCr (99/1): Cu 锁定, Cr+Zr 可调
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, is_locked, sort_order)
SELECT id, 'Cu', '铜', 99.0, true, 1 FROM material_recipe WHERE code='CuCr'
ON CONFLICT (recipe_id, element_code) DO NOTHING;

INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order)
SELECT id, 'Cr', '铬', 0.8, 0.3, 1.5, false, 2 FROM material_recipe WHERE code='CuCr'
UNION ALL SELECT id, 'Zr', '锆', 0.2, 0.0, 0.5, false, 3 FROM material_recipe WHERE code='CuCr'
ON CONFLICT (recipe_id, element_code) DO NOTHING;
