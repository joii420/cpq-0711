-- v1.7 DRAFT 总览
SELECT t.id, t.name, t.version, t.status,
       jsonb_array_length(t.components_snapshot) AS tabs,
       jsonb_array_length(t.subtotal_formula)    AS subtotal_n,
       jsonb_array_length(t.excel_view_config)   AS evc_n,
       t.is_default
  FROM template t
 WHERE t.version = 'v1.7' AND t.template_series_id = '66eb97f0-ec24-4455-933b-c8c26a3190f6';

\echo '---- INPUT_NUMBER 15 字段 ----'
SELECT arr.tab->>'tabName' AS tab_name,
       f->>'name' AS field_name,
       f->>'field_type' AS ft,
       f->>'content' AS content,
       f->'default_source'->>'type' AS ds_type,
       f->'default_source'->>'bnf_path' AS ds_path
  FROM template t,
       jsonb_array_elements(t.components_snapshot) AS arr(tab),
       jsonb_array_elements(arr.tab->'fields') AS f
 WHERE t.version='v1.7' AND t.template_series_id='66eb97f0-ec24-4455-933b-c8c26a3190f6'
   AND f->>'field_type' = 'INPUT_NUMBER'
 ORDER BY (arr.tab->>'sortOrder')::int, f->>'name';

\echo '---- is_subtotal=true 5 字段 ----'
SELECT arr.tab->>'tabName' AS tab_name, f->>'name', f->>'field_type', f->>'is_amount'
  FROM template t,
       jsonb_array_elements(t.components_snapshot) AS arr(tab),
       jsonb_array_elements(arr.tab->'fields') AS f
 WHERE t.version='v1.7' AND t.template_series_id='66eb97f0-ec24-4455-933b-c8c26a3190f6'
   AND (f->>'is_subtotal')::boolean = true
 ORDER BY (arr.tab->>'sortOrder')::int;

\echo '---- 模板 subtotal_formula ----'
SELECT jsonb_pretty(t.subtotal_formula)
  FROM template t
 WHERE t.version='v1.7' AND t.template_series_id='66eb97f0-ec24-4455-933b-c8c26a3190f6';

\echo '---- excel_view_config 22 列 ----'
SELECT col->>'col_key' AS col_key, col->>'col_name' AS col_name,
       col->>'source_type' AS src, col->>'variable_path' AS vpath, col->>'formula' AS formula,
       col->>'visible' AS visible
  FROM template t,
       jsonb_array_elements(t.excel_view_config) WITH ORDINALITY AS arr(col, idx)
 WHERE t.version='v1.7' AND t.template_series_id='66eb97f0-ec24-4455-933b-c8c26a3190f6'
 ORDER BY idx;

\echo '---- 遗留 DRAFT bc0a5dc6 状态 ----'
SELECT id, version, status FROM template WHERE id = 'bc0a5dc6-8da6-4a90-8c72-333a389fb890';

\echo '---- 同 series 全清单 ----'
SELECT id, version, status, created_at FROM template WHERE template_series_id='66eb97f0-ec24-4455-933b-c8c26a3190f6' ORDER BY created_at DESC;
