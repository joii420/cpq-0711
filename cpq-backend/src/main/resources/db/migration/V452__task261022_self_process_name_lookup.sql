-- =====================================================================================
-- task-261022 · 自制加工费增加可选字段「工序名称」
--
-- 字段来源：process_master.process_name
-- 连接关系：ds_quote_self_process_fee.operation_no = process_master.process_no
--
-- 本迁移只追加一个瘦 LOOKUP 节点（一列）、一条 LEFT JOIN 语义边及其连接键；
-- 不修改既有组件配置，用户选中「工序名称」后由 SemanticCompiler 按需生成关联查询。
-- =====================================================================================

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

-- 查名节点只声明 process_name：若声明 process_no，字段面板会额外暴露重复的工序编号。
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

-- 落库即自检，避免种子缺失时迁移成功但字段静默不可用。
DO $$
DECLARE
    bad_count integer;
BEGIN
    SELECT count(*) INTO bad_count
    FROM semantic_node n
    JOIN semantic_node_column c ON c.node_id = n.id
    WHERE n.node_key = 'PROCESS_NAME_LK' AND n.dialect = 'QUOTE' AND n.status = 'ACTIVE'
      AND n.node_kind = 'LOOKUP' AND n.physical_table = 'process_master'
      AND c.db_column = 'process_name' AND c.display_name = '工序名称'
      AND c.is_code = false AND c.status = 'ACTIVE';
    IF bad_count <> 1 THEN
        RAISE EXCEPTION 'V452 自检失败：工序名称查名节点/字段合格数=%（期望 1）', bad_count;
    END IF;

    SELECT count(*) INTO bad_count
    FROM semantic_edge e
    JOIN semantic_node source ON source.id = e.from_node_id
    JOIN semantic_node target ON target.id = e.to_node_id
    WHERE source.node_key = 'SELF_PROCESS_FEE' AND source.dialect = 'QUOTE'
      AND target.node_key = 'PROCESS_NAME_LK' AND target.dialect = 'QUOTE'
      AND e.edge_kind = 'LOOKUP' AND e.cardinality = 'MANY_TO_ONE'
      AND e.coalesce_group IS NULL AND e.fallback_to_join_key = false
      AND e.status = 'ACTIVE'
      AND (SELECT count(*) FROM semantic_edge_key k WHERE k.edge_id = e.id) = 1
      AND EXISTS (
          SELECT 1 FROM semantic_edge_key k
          WHERE k.edge_id = e.id AND k.seq = 0
            AND k.left_column = 'operation_no' AND k.right_column = 'process_no'
      );
    IF bad_count <> 1 THEN
        RAISE EXCEPTION 'V452 自检失败：自制加工费工序查名边/连接键合格数=%（期望 1）', bad_count;
    END IF;
END $$;
