-- V394__task260819_self_process_material_recipe_coalesce.sql
-- 零件「料件」列缺材质库回退（主线裁决，2026-08-21，AC-38 golden 复验第二轮抓出）。
--
-- 基准 jg_view：COALESCE(mm.material_name, mr.name) AS _料件（物料主档 + 材质库双源）。
-- 配置器产物：只有 mm.material_name（单源）——根因是 SELF_PROCESS 的 LOOKUP 边缺一条
-- →材质库(LOOKUP_MATERIAL_RECIPE)，且现有 →物料主档 边没有 coalesce_group，编译器的多源
-- COALESCE 展开逻辑靠 coalesce_group 分组，没有组就只会走单源分支。
--
-- 对照材质元素那组（ebi_name 组：→材质库 fb=0、→物料主档 fb=1）：这里顺序相反——
-- jg_view 是 COALESCE(mm.material_name, mr.name)，物料主档在前 fb=0，材质库在后 fb=1，
-- 不能照抄材质元素的顺序。

-- ① 补边：SELF_PROCESS → LOOKUP_MATERIAL_RECIPE
--    连接键对照 jg_view 实际 SQL：LEFT JOIN material_recipe mr ON mr.code = up.code
INSERT INTO semantic_edge
  (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group,
   assert_status, assert_sample_rows, note, created_by)
VALUES
  ('b14346b2-62df-46f9-8be9-1b12ea12553c',
   '339b23d8-0ae8-5c0b-912b-b0a8a521d1ee',   -- SELF_PROCESS
   '3fa2be85-aa65-5f24-bfc6-8a772f30b3f3',   -- LOOKUP_MATERIAL_RECIPE
   'LOOKUP', 'MANY_TO_ONE', 1, 'jg_name', 'NA', NULL,
   'D-45 复验②：补种子遗漏——对照 jg_view 实际 SQL（LEFT JOIN material_recipe mr ON mr.code=up.code），'
   '与→物料主档边同组 jg_name，fallback_order=1（物料主档 fb=0 在前，材质库 fb=1 兜底在后，顺序对照 '
   'COALESCE(mm.material_name, mr.name)，与材质元素 ebi_name 组的顺序相反，不可颠倒）',
   'seed');

INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
VALUES ('53503a9e-f60a-4674-b299-f2e599359d62', 'b14346b2-62df-46f9-8be9-1b12ea12553c', 'code', 'code', 0);

-- ② 给已有的 SELF_PROCESS → LOOKUP_MATERIAL_MASTER 边补 coalesce_group='jg_name'，
--    fallback_order 已是 0（物料主档在前），不用改。
-- ⚠️ 只改这一条边的 coalesce_group，绝不动 →工序库(LOOKUP_PROCESS_MASTER) 那条边的
--    fallback_order/coalesce_group——它是独立查名列，不属于本 COALESCE 组，这正是
--    V388 时期 uq_edge_fallback 索引范围写错踩过的坑（教训见 V388 注释）。
UPDATE semantic_edge
   SET coalesce_group = 'jg_name'
 WHERE id = '6f5509b4-2739-56d9-b546-778cbb6017a2';  -- SELF_PROCESS -> LOOKUP_MATERIAL_MASTER
