\pset format aligned
\echo '== golden baseline: components_snapshot / sql_views_snapshot md5 per PUBLISHED+ARCHIVED template (pre-V382) =='
SELECT id, name, version, status, template_kind,
       md5(components_snapshot::text)        AS cs_md5,
       md5(COALESCE(sql_views_snapshot::text,'')) AS svs_md5,
       jsonb_array_length(components_snapshot) AS cs_len
FROM template
WHERE status IN ('PUBLISHED','ARCHIVED')
ORDER BY name, version;

\echo '== template_component row count per template (== expected snapshot row count, AC-1/AC-9) =='
SELECT t.id, t.name, t.version, count(tc.id) AS tc_count
FROM template t JOIN template_component tc ON tc.template_id = t.id
WHERE t.status IN ('PUBLISHED','ARCHIVED')
GROUP BY t.id, t.name, t.version
ORDER BY t.name, t.version;

\echo '== total tc rows (should == 149 per requirement doc) =='
SELECT count(*) FROM template_component tc
JOIN template t ON t.id = tc.template_id
WHERE t.status IN ('PUBLISHED','ARCHIVED');

\echo '== per-tc-row golden md5 (component content fields) keyed by template_id+sort_order, for AC-1/AC-3/AC-9 row-level diff =='
SELECT tc.template_id, tc.sort_order, tc.id AS tc_id, c.code,
       md5(
         COALESCE(tc.fields_override::text, c.fields::text) ||
         '|' || c.formulas::text ||
         '|' || COALESCE(tc.data_driver_path_override, c.data_driver_path, '') ||
         '|' || COALESCE(c.row_key_fields::text,'') ||
         '|' || COALESCE(c.sort_field,'') ||
         '|' || COALESCE(c.element_code_field,'') ||
         '|' || COALESCE(c.element_price_field,'') ||
         '|' || COALESCE(c.element_currency_field,'') ||
         '|' || c.column_count::text ||
         '|' || COALESCE(c.tree_config::text,'') ||
         '|' || c.bom_recursive_expand::text ||
         '|' || COALESCE(c.tab_type,'') ||
         '|' || COALESCE(c.part_no_field,'') ||
         '|' || COALESCE(c.part_name_field,'') ||
         '|' || c.excel_columns::text
       ) AS row_md5
FROM template_component tc
JOIN component c ON c.id = tc.component_id
JOIN template t ON t.id = tc.template_id
WHERE t.status IN ('PUBLISHED','ARCHIVED')
ORDER BY tc.template_id, tc.sort_order;
