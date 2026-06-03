-- V294: 9 个读 material_bom_item 的组件配置 SQL 补 is_current（material_bom_item 多版本化后防 AP-22 重复行）
-- 范围仅 component_sql_view；锚点见 spec §9.1。'g' 全局；多处出现的 ys_view/weights 一并覆盖。

-- 1) v12_raw_bom (bi)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'bi\.system_type\s*=\s*''QUOTE''', 'bi.system_type = ''QUOTE'' AND bi.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'v12_raw_bom';

-- 2) zcj_bom (asy)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'asy\.system_type\s*=\s*''QUOTE''', 'asy.system_type = ''QUOTE'' AND asy.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'zcj_bom';

-- 3) zcj_view (asy)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'asy\.system_type\s*=\s*''QUOTE''', 'asy.system_type = ''QUOTE'' AND asy.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'zcj_view';

-- 4) v12_raw_element_bom (mbi)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'mbi\.system_type\s*=\s*''QUOTE''', 'mbi.system_type = ''QUOTE'' AND mbi.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'v12_raw_element_bom';

-- 5) ys_view (mbi 左连接处；mbt 子查询已自带 is_current 不动)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'mbi\.characteristic\s*=\s*''ASSEMBLY''', 'mbi.characteristic = ''ASSEMBLY'' AND mbi.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'ys_view';

-- 6) composite_child_materials_mirror (asy)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'asy\.system_type\s*=\s*''QUOTE''', 'asy.system_type = ''QUOTE'' AND asy.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'composite_child_materials_mirror';

-- 7) composite_child_processes_mirror (bom)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'bom\.system_type\s*=\s*''QUOTE''', 'bom.system_type = ''QUOTE'' AND bom.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'composite_child_processes_mirror';

-- 8) composite_child_weights_mirror (asy + asy2 两处)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'asy\.system_type\s*=\s*''QUOTE''', 'asy.system_type = ''QUOTE'' AND asy.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'composite_child_weights_mirror';
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'asy2\.system_type\s*=\s*''QUOTE''', 'asy2.system_type = ''QUOTE'' AND asy2.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'composite_child_weights_mirror';

-- 9) composite_child_elements_mirror (parent；ebi 已自带 is_current 不动)
UPDATE component_sql_view SET sql_template = regexp_replace(sql_template,
  'parent\.system_type\s*=\s*''QUOTE''', 'parent.system_type = ''QUOTE'' AND parent.is_current = true', 'g'),
  updated_at = NOW() WHERE sql_view_name = 'composite_child_elements_mirror';
