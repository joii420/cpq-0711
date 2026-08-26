-- V395__task260819_element_bom_to_incoming_fixed_grain_edge.sql
-- D-68（主线裁决，2026-08-25，AC-19 校验落地的前置种子缺口）。

-- ============ 补边：ELEMENT_BOM_ITEM（材质元素锚点）→ INCOMING_FIXED（来料固定加工费）============
-- 根因：材质元素 tab_view（d3bac52f-64fe-5439-8078-1328e1516375）只挂了 ELEMENT_RECOVERY/
-- MATERIAL_BOM 两个 AUX 节点（V388:365/366），INCOMING_FIXED 从未作为该页签可选的附属源，
-- 锚点也没有到它的声明边——AC-19「材质元素页签拖入附属源『来料加工费』」编译期直接
-- COMPILE_PATH_NOT_FOUND（"锚点「物料与元素BOM」没有到「来料固定加工费」的声明边"）。
--
-- 边类型选 GRAIN（而非 LOOKUP）：INCOMING_FIXED 是一张独立展开维度的表（grain_columns=['code']，
-- 对照 PRODUCT_MASTER→ASSEMBLY_FEE / PRODUCT_MASTER→FINISHED_OTHER 同款 GRAIN 边），
-- 不是查名用的标量 LOOKUP——一个「归属料号」下可能有多条来料固定加工费行（不同投入料号/工序/要素）。
--
-- 连接键：ebi.material_no（元素BOM「归属料号」列）＝ up.finished_material_no（INCOMING_FIXED
-- 物理列，unit_price 表实测存在，但未在 semantic_node_column 里登记——GRAIN/LOOKUP 边的连接键
-- 是编译器直接拼物理列名，不经 semantic_node_column 校验，与既有 GRAIN 边写法一致）。
--
-- （不用 \gset：Flyway 走 JDBC 直接执行 SQL 文本，元命令不可用——固定字面量 UUID。）

INSERT INTO semantic_edge
  (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group,
   assert_status, assert_sample_rows, note, created_by)
VALUES
  ('c1a9e2b4-7f3d-5a6e-9c8b-2d4e6f8a0b1c',
   '6bfd2a0c-0cda-5c99-acd0-a14aefe10b46',   -- ELEMENT_BOM_ITEM（材质元素锚点）
   '546538fb-d097-5f89-b027-b3e786f75930',   -- INCOMING_FIXED（来料固定加工费）
   'GRAIN', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL,
   'D-68：材质元素页签补挂附属源『来料加工费』——AC-19 校验需要先能编译通过，才谈得上校验小计阻断',
   'seed');

INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
VALUES ('d2b0f3c5-8e4f-5b7f-0d9c-3e5f7a9b1c2d', 'c1a9e2b4-7f3d-5a6e-9c8b-2d4e6f8a0b1c', 'material_no', 'finished_material_no', 0);

-- ============ 补挂：材质元素 tab_view 的可选节点（AUX，用户在字段面板真能拖到）============
INSERT INTO semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by)
VALUES
  ('e3c1a4d6-9f5a-5c8a-1e0d-4f6a8b0c2d3e', 'd3bac52f-64fe-5439-8078-1328e1516375',
   '546538fb-d097-5f89-b027-b3e786f75930', 'AUX', '{}', 3, 'seed');
