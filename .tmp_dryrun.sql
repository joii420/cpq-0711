-- Dry-run V216 in a transaction (ROLLBACK at end)
BEGIN;

\i cpq-backend/src/main/resources/db/migration/V216__quotation_std_excel_v1_7.sql

\echo '---- 最终核对 (在 ROLLBACK 前) ----'

SELECT t.id, t.name, t.version, t.status,
       jsonb_array_length(t.components_snapshot) AS tabs,
       jsonb_array_length(t.subtotal_formula)    AS subtotal_n,
       jsonb_array_length(t.excel_view_config)   AS evc_n
  FROM template t
 WHERE t.version = 'v1.7' AND t.template_series_id = '66eb97f0-ec24-4455-933b-c8c26a3190f6';

SELECT 'INPUT_NUMBER cnt' AS metric, COUNT(*) AS v
  FROM template t,
       jsonb_array_elements(t.components_snapshot) AS arr(tab),
       jsonb_array_elements(arr.tab->'fields') AS f
 WHERE t.version='v1.7' AND t.template_series_id='66eb97f0-ec24-4455-933b-c8c26a3190f6'
   AND f->>'field_type' = 'INPUT_NUMBER';

SELECT 'is_subtotal=true cnt' AS metric, COUNT(*) AS v
  FROM template t,
       jsonb_array_elements(t.components_snapshot) AS arr(tab),
       jsonb_array_elements(arr.tab->'fields') AS f
 WHERE t.version='v1.7' AND t.template_series_id='66eb97f0-ec24-4455-933b-c8c26a3190f6'
   AND (f->>'is_subtotal')::boolean = true;

SELECT 'tabs per name' AS metric, arr.tab->>'tabName' AS tab_name, jsonb_array_length(arr.tab->'fields') AS field_cnt
  FROM template t,
       jsonb_array_elements(t.components_snapshot) WITH ORDINALITY AS arr(tab, idx)
 WHERE t.version='v1.7' AND t.template_series_id='66eb97f0-ec24-4455-933b-c8c26a3190f6'
 ORDER BY idx;

SELECT 'legacy bc0a5dc6 status' AS metric, status FROM template WHERE id = 'bc0a5dc6-8da6-4a90-8c72-333a389fb890';

ROLLBACK;

\echo '---- Dry-run done, all changes rolled back ----'
