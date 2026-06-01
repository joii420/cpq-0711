-- V281: 报价侧配置 SQL 模板（component_sql_view / template_sql_view）加 is_current 过滤（Task 9c-1）
--
-- 背景：这些 sql_template 运行时 BNF 解释执行，读版本化 QUOTE 行。升版后若不过滤 is_current 会返重复行。
-- 范围（worklist 必需项）：仅读「多版本保留」表的模板——element_bom_item / unit_price / plating_scheme。
--   material_bom_item 的防御性过滤**有意省略**：Q03/Q12 的 deleteNonCurrent 不变量保证它只剩 is_current=true
--   行，加过滤可证为 no-op；不对运行时模板做零收益正则替换（降风险）。如需字面合规设计 §6 可后续补。
-- 机制：UPDATE ... regexp_replace（\s+ 弹性匹配 CRLF/换行；每锚点 dry-run count=1 验证过）。
-- 部署：落 db/migration/ 后 touch java 触发 Quarkus 重启 → Flyway + BnfTableMetaSyncer 重新同步（清缓存）。

-- ① gx_view：unit_price up（QUOTE）必需
UPDATE component_sql_view
SET sql_template = regexp_replace(sql_template, 'up\.system_type\s*=\s*''QUOTE''',
                                  'up.system_type = ''QUOTE'' AND up.is_current = true', 'g'),
    updated_at = NOW()
WHERE sql_view_name = 'gx_view';

-- ② zcj_view：unit_price u（QUOTE）必需（asy=material_bom_item 防御性，省略）
UPDATE component_sql_view
SET sql_template = regexp_replace(sql_template, 'u\.system_type\s*=\s*''QUOTE''',
                                  'u.system_type = ''QUOTE'' AND u.is_current = true', 'g'),
    updated_at = NOW()
WHERE sql_view_name = 'zcj_view';

-- ③ v12_plating_scheme：plating_scheme ps（全局版本化）必需 —— 原无 WHERE，追加
UPDATE component_sql_view
SET sql_template = regexp_replace(sql_template, 'FROM\s+plating_scheme\s+ps',
                                  'FROM plating_scheme ps WHERE ps.is_current = true', 'g'),
    updated_at = NOW()
WHERE sql_view_name = 'v12_plating_scheme';

-- ④ composite_child_elements_mirror：element_bom_item ebi（QUOTE）必需
UPDATE component_sql_view
SET sql_template = regexp_replace(sql_template, 'ebi\.system_type\s*=\s*''QUOTE''',
                                  'ebi.system_type = ''QUOTE'' AND ebi.is_current = true', 'g'),
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_elements_mirror';

-- ⑤ v12_raw_element_bom：element_bom_item ebi（QUOTE）必需
UPDATE component_sql_view
SET sql_template = regexp_replace(sql_template, 'ebi\.system_type\s*=\s*''QUOTE''',
                                  'ebi.system_type = ''QUOTE'' AND ebi.is_current = true', 'g'),
    updated_at = NOW()
WHERE sql_view_name = 'v12_raw_element_bom';

-- ⑥ ys_view：element_bom_item ebi + 关联子查询 element_bom_item c（均 QUOTE 多版本）必需
UPDATE component_sql_view
SET sql_template = regexp_replace(
                     regexp_replace(sql_template, 'ebi\.system_type\s*=\s*''QUOTE''',
                                    'ebi.system_type = ''QUOTE'' AND ebi.is_current = true', 'g'),
                     'c\.system_type\s*=\s*ebi\.system_type',
                     'c.system_type = ebi.system_type AND c.is_current = true', 'g'),
    updated_at = NOW()
WHERE sql_view_name = 'ys_view';

-- ⑦ summary_material（template_sql_view）：element_bom_item ebi + 裸 element_bom_item（均 QUOTE）必需；
--    unit_price 部分是 PRICING（4 处），保留不动
UPDATE template_sql_view
SET sql_template = regexp_replace(
                     regexp_replace(sql_template, 'ebi\.system_type\s*=\s*''QUOTE''',
                                    'ebi.system_type = ''QUOTE'' AND ebi.is_current = true', 'g'),
                     'element_bom_item\s+WHERE\s+system_type\s*=\s*''QUOTE''',
                     'element_bom_item WHERE system_type = ''QUOTE'' AND is_current = true', 'g'),
    updated_at = NOW()
WHERE sql_view_name = 'summary_material';
