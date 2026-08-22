-- V393__task260819_customer_map_lookup_edge_and_fallback_switch.sql
-- D-45②③（主线裁决，2026-08-21，AC-38 golden 首跑抓出的两个种子/schema 缺口）。

-- ============ ② 补边：物料主档/单重 → LOOKUP_CUSTOMER_MAP（AC-38 主件 golden 3 列配不出）============
-- 根因：LOOKUP_CUSTOMER_MAP 节点存在、fixed_predicate 齐备，但入边数为 0——没有任何边连过去，
-- 编译期任何寻址到它的列都报 COMPILE_PATH_NOT_FOUND。对照基准 cp_view 实际 SQL：
--   LEFT JOIN material_customer_map mcm ON mcm.material_no = mm.material_no AND mcm.customer_no = :customerCode
-- 连接键：material_no（锚点自身列） = material_no（目标列），LOOKUP 边，MANY_TO_ONE。
-- （不用 \gset：Flyway 走 JDBC 直接执行 SQL 文本，不经过 psql，元命令不可用——固定字面量 UUID。）

INSERT INTO semantic_edge
  (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group,
   assert_status, assert_sample_rows, note, created_by)
VALUES
  ('a4e7dd72-cd7a-463e-abdc-944c0c700f3d',
   'aa084d67-9972-5824-b64b-09430339cc5f',   -- PRODUCT_MASTER
   '73f1dd06-dd79-5c87-a2b3-7adcfe3412d0',   -- LOOKUP_CUSTOMER_MAP
   'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL,
   'D-45②：补种子遗漏——对照 cp_view 实际 SQL（LEFT JOIN material_customer_map mcm ON mcm.material_no=mm.material_no AND mcm.customer_no=:customerCode）',
   'seed');

INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
VALUES ('ff3b4bae-ecfe-4a2e-af59-c9a87782aed3', 'a4e7dd72-cd7a-463e-abdc-944c0c700f3d', 'material_no', 'material_no', 0);

-- LOOKUP_CUSTOMER_MAP 当时只登记了 2 列（material_no / customer_material_name），
-- 但基准 cp_view 还用了 customer_product_no（客户产品编号）与 exchange_rate（汇率）——
-- 这两列在 CUSTOMER_MAP（SHEET 节点，同一张物理表）上已登记，此处补齐镜像，
-- 否则光补边还是配不出这两列（COMPILE_COLUMN_NOT_FOUND）。
INSERT INTO semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by)
VALUES
  ('2b6e6a1a-2f7a-4e4a-9a1a-9c9a1a2b6e6a', '73f1dd06-dd79-5c87-a2b3-7adcfe3412d0', 'customer_product_no', '客户产品编号', 'TEXT', FALSE, '{}', 2, 'seed'),
  ('3c7f7b2b-3a8b-4f5b-8b2b-8d8b2b3c7f7b', '73f1dd06-dd79-5c87-a2b3-7adcfe3412d0', 'exchange_rate', '汇率', 'NUMBER', FALSE, '{}', 3, 'seed');

-- ============ ③ fallback_to_join_key 开关（AC-38 零件 golden 工序列缺兜底）============
-- 基准 jg_view / ll_view 都用 COALESCE(pm.process_name, up.operation_no)——查名查不到时退回
-- 连接键左列（原始工序代码），且连接键左列恰好就是 operation_no。wg_view/mc_view 的同类查名
-- （COALESCE(mr.name, mm2.material_name)）则没有这个退回——是否退回是每条边的业务选择，
-- 所以做成开关而非默认行为。

ALTER TABLE semantic_edge ADD COLUMN fallback_to_join_key BOOLEAN NOT NULL DEFAULT false;

-- 只给真正需要的两条 →工序库(LOOKUP_PROCESS_MASTER) 边置 true（对照各自基准 SQL 实测确认）：
--   SELF_PROCESS(自制加工/零件)   → jg_view: COALESCE(pm.process_name, up.operation_no) ✅
--   INCOMING_FIXED(来料固定加工费) → ll_view: COALESCE(pm.process_name, up.operation_no) ✅
--   MATERIAL_BOM(外购件/BOM树)     → wg_view: 全文不引用 process_master，不适用 ❌ 保持 false
UPDATE semantic_edge SET fallback_to_join_key = true
 WHERE id IN (
   'e8f70040-5d6c-5394-a9f7-e17ad58541ca',  -- SELF_PROCESS -> LOOKUP_PROCESS_MASTER
   '583aa9e5-a9f6-5a7a-b580-d1e93efc0bcd'   -- INCOMING_FIXED -> LOOKUP_PROCESS_MASTER
 );
