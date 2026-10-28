-- CPQ database increment: V452 -> V454
-- Source migration: V454__task261026_material_profit.sql
-- Generated: 2026-10-26
-- Adds nullable quote material profit and the QUOTE/MATERIAL semantic field.
-- V453 is not part of this code baseline; any pending V453 migration must be
-- renumbered above V454 before it is merged or deployed.

BEGIN;

ALTER TABLE ds_quote_material
    ADD COLUMN IF NOT EXISTS profit numeric(26,12);

INSERT INTO semantic_node_column (
    id, node_id, db_column, display_name, data_type, is_code, roles, sort_order,
    created_by, created_at, updated_at, status)
SELECT
    '26102602-0000-4000-8000-000000000001'::uuid,
    n.id, 'profit', '利润', 'NUMBER', false, '{}', 9,
    'task-261026', now(), now(), 'ACTIVE'
FROM semantic_node n
WHERE n.node_key = 'MATERIAL'
  AND n.dialect = 'QUOTE'
ON CONFLICT DO NOTHING;

UPDATE flyway_schema_history
SET version = '454', script = '<< Flyway Baseline >>'
WHERE installed_rank = 1;

COMMIT;

-- Self-check: each query must return the expected value shown in its alias.
SELECT count(*) AS profit_column_expected_1
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name = 'ds_quote_material'
  AND column_name = 'profit'
  AND data_type = 'numeric'
  AND numeric_precision = 26
  AND numeric_scale = 12
  AND is_nullable = 'YES';

SELECT count(*) AS costing_profit_column_expected_0
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name IN ('ds_cost_basic_material', 'ds_cost_detail_material')
  AND column_name = 'profit';

SELECT count(*) AS quote_material_profit_semantic_expected_1
FROM semantic_node n
JOIN semantic_node_column c ON c.node_id = n.id
WHERE n.node_key = 'MATERIAL'
  AND n.dialect = 'QUOTE'
  AND n.status = 'ACTIVE'
  AND n.physical_table = 'ds_quote_material'
  AND c.db_column = 'profit'
  AND c.display_name = '利润'
  AND c.data_type = 'NUMBER'
  AND c.is_code = false
  AND c.roles = '{}'::text[]
  AND c.sort_order = 9
  AND c.status = 'ACTIVE';

SELECT version AS flyway_baseline_expected_454
FROM flyway_schema_history
WHERE installed_rank = 1;
