-- Source migration: V452__task261022_self_process_name_lookup.sql
-- Generated: 2026-10-23
-- Add the optional process name lookup for the self-process-fee builder source.
-- Idempotent for an already-upgraded database; Navicat compatible (no psql meta commands / dollar quoting).

BEGIN;

INSERT INTO semantic_node (
    id, node_key, display_name, short_name, node_kind, physical_table, scope,
    anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator,
    source_handler, dialect, note, created_by, created_at, updated_at, status)
VALUES (
    '26102201-0000-4000-8000-000000000001'::uuid,
    'PROCESS_NAME_LK', '工序（查名）', '工序', 'LOOKUP', 'process_master', 'NONE',
    NULL, '{}', NULL, NULL, NULL, NULL, 'QUOTE',
    'task-261022：自制加工费按工序编号查询工序名称（瘦节点）', 'task-261022',
    now(), now(), 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO semantic_node_column (
    id, node_id, db_column, display_name, data_type, is_code, roles, sort_order,
    created_by, created_at, updated_at, status)
SELECT
    '26102202-0000-4000-8000-000000000001'::uuid,
    n.id, 'process_name', '工序名称', 'TEXT', false, '{}', 0,
    'task-261022', now(), now(), 'ACTIVE'
FROM semantic_node n
WHERE n.node_key = 'PROCESS_NAME_LK' AND n.dialect = 'QUOTE'
ON CONFLICT DO NOTHING;

INSERT INTO semantic_edge (
    id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order,
    coalesce_group, fallback_to_join_key, assert_status, assert_sample_rows,
    note, created_by, created_at, updated_at, status)
SELECT
    '26102203-0000-4000-8000-000000000001'::uuid,
    source.id, target.id, 'LOOKUP', 'MANY_TO_ONE', NULL,
    NULL, false, 'NA', NULL,
    'task-261022：SELF_PROCESS_FEE.operation_no → process_master.process_no',
    'task-261022', now(), now(), 'ACTIVE'
FROM semantic_node source
JOIN semantic_node target
  ON target.node_key = 'PROCESS_NAME_LK' AND target.dialect = 'QUOTE'
WHERE source.node_key = 'SELF_PROCESS_FEE' AND source.dialect = 'QUOTE'
ON CONFLICT DO NOTHING;

INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
SELECT
    '26102204-0000-4000-8000-000000000001'::uuid,
    edge.id, 'operation_no', 'process_no', 0
FROM semantic_edge edge
JOIN semantic_node source ON source.id = edge.from_node_id
JOIN semantic_node target ON target.id = edge.to_node_id
WHERE source.node_key = 'SELF_PROCESS_FEE' AND source.dialect = 'QUOTE'
  AND target.node_key = 'PROCESS_NAME_LK' AND target.dialect = 'QUOTE'
  AND edge.edge_kind = 'LOOKUP'
ON CONFLICT DO NOTHING;

-- Keep the manual-deployment baseline aligned with the application migration.
UPDATE flyway_schema_history
   SET version = '452',
       script = '<< Flyway Baseline >>'
 WHERE installed_rank = 1
   AND version ~ '^[0-9]+$'
   AND version::integer < 452;

-- Self-check 1: expected result = 1.
SELECT 1 / CASE WHEN count(*) = 1 THEN 1 ELSE 0 END AS process_name_lookup_check_expected_1
FROM semantic_node n
JOIN semantic_node_column c ON c.node_id = n.id
WHERE n.node_key = 'PROCESS_NAME_LK'
  AND n.dialect = 'QUOTE'
  AND n.status = 'ACTIVE'
  AND n.node_kind = 'LOOKUP'
  AND n.physical_table = 'process_master'
  AND c.db_column = 'process_name'
  AND c.display_name = '工序名称'
  AND c.is_code = false
  AND c.status = 'ACTIVE';

-- Self-check 2: expected result = 1.
SELECT 1 / CASE WHEN count(*) = 1 THEN 1 ELSE 0 END AS self_process_lookup_edge_check_expected_1
FROM semantic_edge e
JOIN semantic_node source ON source.id = e.from_node_id
JOIN semantic_node target ON target.id = e.to_node_id
WHERE source.node_key = 'SELF_PROCESS_FEE'
  AND source.dialect = 'QUOTE'
  AND target.node_key = 'PROCESS_NAME_LK'
  AND target.dialect = 'QUOTE'
  AND e.edge_kind = 'LOOKUP'
  AND e.cardinality = 'MANY_TO_ONE'
  AND e.coalesce_group IS NULL
  AND e.fallback_to_join_key = false
  AND e.status = 'ACTIVE'
  AND (SELECT count(*) FROM semantic_edge_key k WHERE k.edge_id = e.id) = 1
  AND EXISTS (
      SELECT 1
      FROM semantic_edge_key k
      WHERE k.edge_id = e.id
        AND k.seq = 0
        AND k.left_column = 'operation_no'
        AND k.right_column = 'process_no'
  );

-- Self-check 3: expected result = 452 on manual-baseline databases.
SELECT version AS flyway_baseline_expected_452, script
FROM flyway_schema_history
WHERE installed_rank = 1;

COMMIT;
