-- V390__task260819_restore_e02_grain_edge.sql
-- 补回被误删的 E02 GRAIN 边（主线 D-40 裁决 + 根因确认）。
--
-- 根因：本轮验 AC-15（GRAIN 成品其他费用分支）时，backend-engineer 用
-- PRODUCT_MASTER→FINISHED_OTHER 这对节点做 AC-52 边基数攻击测试，测完调
-- DELETE /edges/by-nodes 清理——该端点按 (from_node_id, to_node_id) 全删，不区分
-- edge_kind、不区分"种子边"还是"测试临时边"，把种子里本来就有的 E02（GRAIN）一并删了。
-- Flyway checksum 验证不出这类问题（迁移文件本身没变，是运行时业务数据被删）。
--
-- 主线已裁决（D-40）：① 本迁移补回该行；② DELETE /edges/by-nodes 端点本身随后下线。
--
-- 数据取自 V388 原始声明（保留原 id，逐字对齐 AC-51"种子迁移与原声明逐项等值"）：
--   from = 物料主档/单重 PRODUCT_MASTER (aa084d67-9972-5824-b64b-09430339cc5f)
--   to   = 成品其他费用 FINISHED_OTHER (71e481c2-8049-50f0-b3b7-b0d253f7ca8c)
--   edge_kind = GRAIN, cardinality = ONE_TO_MANY, fallback_order = NULL, coalesce_group = NULL
--   连接键：left=material_no(锚点自身列) / right=code(目标列)，seq=0 —— fo.code = mm.material_no

INSERT INTO semantic_edge
  (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group,
   assert_status, assert_sample_rows, note, created_by)
VALUES
  ('e7fb81ad-61d2-553b-8ffb-49a965db80b6',
   'aa084d67-9972-5824-b64b-09430339cc5f',
   '71e481c2-8049-50f0-b3b7-b0d253f7ca8c',
   'GRAIN', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, NULL, 'seed')
ON CONFLICT (from_node_id, to_node_id, edge_kind) DO NOTHING;

INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
VALUES
  ('85153d24-fe55-5620-84e1-62feecee730b',
   'e7fb81ad-61d2-553b-8ffb-49a965db80b6',
   'material_no', 'code', 0)
ON CONFLICT (edge_id, seq) DO NOTHING;
