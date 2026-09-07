-- V418__task260907_semantic_graph_customer_part_and_annual.sql
-- 🤖 由 scratchpad/gen_v418.py 生成（uuid5 确定性 id，可重放得同一组 id）。
-- task-260907 · B-1 / B-2（F-1 / F-4；AC-1 / AC-2 / AC-6 / AC-11）
--
-- 本迁移**只 INSERT，不删不改任何既有行** —— 与 V413（整块重灌）不同，它是增量补图：
--   B-1 客户料号 ds_quote_customer_part 接入语义图，以 AUX 挂在 QUOTE/主件 上，
--       经 MATERIAL --LOOKUP--> CUSTOMER_PART 编译成 LEFT JOIN（物料为主，AC-2①）。
--   B-2 年降 3 张各自成为**独立数据源**（tab_type='费用类' 的 3 个新变体），
--       🚫 不挂成物料的 AUX：与物料是 1:N（discount_seq 档次），挂上去行数放大（AC-11④）。
--
-- 🚨 它推翻 V413 头注释里的 N-18（年降 3 张不进图）/ N-19（客户料号不进图）两条决策 ——
--    用户 2026-09-07 裁决：配置器里必须能拖到这 4 张表。
--    ⇒ V9TestBase.NOT_IN_GRAPH 与 V9SemanticGraphSeedTest 的『费用类变体 QUOTE=8』
--      两处断言随之失效，需由主线裁决如何更新（本迁移不动测试）。
--
-- 🚫 一行 V413/V417 的既有数据都不动（不撞 §3.2「契约销毁」红线）。

-- ============ 1. 节点（4 个，全部 dialect='QUOTE'） ============
INSERT INTO semantic_node (id,node_key,display_name,short_name,node_kind,physical_table,scope,anchor_expr,grain_columns,fixed_predicate,func_signature,discriminator,source_handler,dialect,note,created_by) VALUES
('613b7811-0156-5ec9-b851-40a1579d0734','CUSTOMER_PART','客户料号','客户料号','SHEET','ds_quote_customer_part','NONE',NULL,'{}',NULL,NULL,NULL,NULL,'QUOTE','task-260907 B-1（F-1）：客户料号表。不作为页签锚点，只以 AUX 挂在 QUOTE/主件 上，经 MATERIAL 的 LOOKUP 边 LEFT JOIN 带出（物料为主，用户 2026-09-07 裁决）。','seed'),
('95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4','ANNUAL_DISCOUNT','年降系数','年降系数','SHEET','ds_quote_annual_discount','FULL','dqad.material_no',ARRAY['discount_seq']::TEXT[],NULL,NULL,NULL,NULL,'QUOTE','task-260907 B-2（F-4）：独立数据源。🚫 不挂成「物料」的 AUX —— 与物料是 1:N（带 discount_seq 档次），挂上去会把物料 47 行放大成 47×N（AC-11④）。','seed'),
('9901a057-2883-5893-9e89-c6232a0997fe','ASSEMBLY_FEE_ANNUAL','组装加工费年降','组装加工费年降','SHEET','ds_quote_assembly_fee_annual','FULL','dqafa.material_no',ARRAY['assembly_operation','discount_seq']::TEXT[],NULL,NULL,NULL,NULL,'QUOTE','task-260907 B-2（F-4）：独立数据源，同上 1:N 理由。','seed'),
('817fe5f7-66df-5551-b85c-762cf908281b','INCOMING_ANNUAL','来料年降','来料年降','SHEET','ds_quote_incoming_annual','FULL','dqia.material_no',ARRAY['input_material_no','discount_seq']::TEXT[],NULL,NULL,NULL,NULL,'QUOTE','task-260907 B-2（F-4）：独立数据源，同上 1:N 理由。','seed');

-- ============ 2. 节点列声明（30 列；与 information_schema 双向无差集，两侧排除 8 个系统列） ============
-- 系统列（不声明）：id / version_no / row_fingerprint / source / created_at / created_by / updated_at / updated_by
INSERT INTO semantic_node_column (id,node_id,db_column,display_name,data_type,is_code,roles,sort_order,created_by) VALUES
('72d3b838-096a-56f1-8cfd-fe72a9c689ef','613b7811-0156-5ec9-b851-40a1579d0734','customer_no','客户编号','TEXT',TRUE,'{}',0,'seed'),
('9284c444-6fa2-52f9-890c-f78bf2028893','613b7811-0156-5ec9-b851-40a1579d0734','customer_part_name','客户料号名称','TEXT',FALSE,'{}',1,'seed'),
('b781bb7d-c65b-5215-9fc6-cfb6a521881a','613b7811-0156-5ec9-b851-40a1579d0734','customer_product_no','客户产品编号','TEXT',TRUE,'{}',2,'seed'),
('d5f3e1db-2333-5f81-a79a-5e2dd94065c9','613b7811-0156-5ec9-b851-40a1579d0734','customer_drawing_no','客户图号','TEXT',FALSE,'{}',3,'seed'),
('89c52cb1-cf40-56a5-bcfa-f19bf581baca','613b7811-0156-5ec9-b851-40a1579d0734','material_no','销售料号','TEXT',TRUE,'{}',4,'seed'),
('223c8f1e-b4af-5b49-8800-7deab6b84f08','95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4','material_no','销售料号','TEXT',TRUE,ARRAY['PART_NO','ROW_KEY']::TEXT[],0,'seed'),
('321d07b8-1151-54b5-b58a-51ef14989109','95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4','discount_seq','年降顺序','NUMBER',FALSE,ARRAY['ROW_KEY']::TEXT[],1,'seed'),
('6415984d-16d3-5aac-82c5-323880fe1b35','95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4','discount_rate','年降系数（%/年）','NUMBER',FALSE,'{}',2,'seed'),
('f8b1b2fa-5b88-5cbf-955a-53e534114078','95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4','fixed_discount_value','单次固定年降金额','NUMBER',FALSE,'{}',3,'seed'),
('9ccac41e-d914-5649-9610-d07ab3e41277','95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4','currency','货币','TEXT',FALSE,'{}',4,'seed'),
('12c457f5-7eec-549e-a78e-9cf69eea694b','95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4','pricing_unit','计价单位','TEXT',FALSE,'{}',5,'seed'),
('07642560-908c-54ac-9ea9-24aa3897aff6','95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4','discount_times','降价次数','NUMBER',FALSE,'{}',6,'seed'),
('18c7b822-b28f-543e-983a-b34a2a2bbde9','9901a057-2883-5893-9e89-c6232a0997fe','material_no','销售料号','TEXT',TRUE,ARRAY['PART_NO','ROW_KEY']::TEXT[],0,'seed'),
('bea226b4-16dc-5315-823a-2b35053a6115','9901a057-2883-5893-9e89-c6232a0997fe','item_seq','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],1,'seed'),
('d7354193-1286-550f-a3ed-1cbdab52efc8','9901a057-2883-5893-9e89-c6232a0997fe','assembly_operation','组装工序','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],2,'seed'),
('ae2d5779-f796-5862-9571-73fb56a2bc8f','9901a057-2883-5893-9e89-c6232a0997fe','discount_seq','年降顺序','NUMBER',FALSE,ARRAY['ROW_KEY']::TEXT[],3,'seed'),
('a1be9f5a-c036-55a5-b9f2-631867f6d2b9','9901a057-2883-5893-9e89-c6232a0997fe','discount_rate','年降系数（%）','NUMBER',FALSE,'{}',4,'seed'),
('56cff7de-b5ac-549c-a22d-2f8948b12a58','9901a057-2883-5893-9e89-c6232a0997fe','fixed_discount_value','单次固定年降值','NUMBER',FALSE,'{}',5,'seed'),
('c6cb4378-5507-5302-84e6-161cd30e7bf6','9901a057-2883-5893-9e89-c6232a0997fe','currency','货币','TEXT',FALSE,'{}',6,'seed'),
('88856aab-76a4-5b34-b64c-99316e304c59','9901a057-2883-5893-9e89-c6232a0997fe','pricing_unit','计价单位','TEXT',FALSE,'{}',7,'seed'),
('5d3ec16d-d9ca-587e-ad92-9797b4731a89','9901a057-2883-5893-9e89-c6232a0997fe','discount_times','降价次数','NUMBER',FALSE,'{}',8,'seed'),
('dff33dc3-3781-5b63-bb32-ce0b6026843c','817fe5f7-66df-5551-b85c-762cf908281b','material_no','销售料号','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],0,'seed'),
('308a55a2-eb8f-5e99-a405-203b087e1e63','817fe5f7-66df-5551-b85c-762cf908281b','item_seq','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],1,'seed'),
('baf24fad-ef31-5e3c-b566-583bf7340f1b','817fe5f7-66df-5551-b85c-762cf908281b','input_material_no','投入料号','TEXT',TRUE,ARRAY['PART_NO','ROW_KEY']::TEXT[],2,'seed'),
('93f1b572-a72a-5416-8070-d1630ef062e2','817fe5f7-66df-5551-b85c-762cf908281b','discount_seq','年降顺序','NUMBER',FALSE,ARRAY['ROW_KEY']::TEXT[],3,'seed'),
('ac27a3fd-85f4-5f66-977c-142d6f21c8fc','817fe5f7-66df-5551-b85c-762cf908281b','discount_rate','年降系数（%）','NUMBER',FALSE,'{}',4,'seed'),
('c80e533f-049c-5192-b22a-7fc477be80d7','817fe5f7-66df-5551-b85c-762cf908281b','fixed_discount_value','单次固定年降值','NUMBER',FALSE,'{}',5,'seed'),
('cd0dc13c-c8a5-5560-a4d8-656bf43d7ded','817fe5f7-66df-5551-b85c-762cf908281b','currency','货币','TEXT',FALSE,'{}',6,'seed'),
('e2a60b10-6e94-55a8-b382-82eb1877c318','817fe5f7-66df-5551-b85c-762cf908281b','pricing_unit','计价单位','TEXT',FALSE,'{}',7,'seed'),
('e0831a7c-ae66-50e8-ad0c-1d8eacda112a','817fe5f7-66df-5551-b85c-762cf908281b','discount_times','降价次数','NUMBER',FALSE,'{}',8,'seed');

-- ============ 3. 边：MATERIAL --LOOKUP--> CUSTOMER_PART（B-1，AC-2③） ============
-- 🔑 为什么是 LOOKUP 而不是 JOIN：SemanticCompiler 里 **只有 LOOKUP 编译成 LEFT JOIN**
--    （ensureLeftJoin）；edge_kind='JOIN' 走 emitMandatoryJoin，出的是 INNER JOIN ——
--    那会把 47 个物料里没有客户料号的 28 个整行丢掉（实测 ds_quote_customer_part 19 行 /
--    19 个不同 material_no，ds_quote_material 47 行），直接违反 AC-2①②。
-- 🔑 基数 MANY_TO_ONE：实测『一个 material_no 对多行 = 0 条』⇒ 当前确为 1:1，不放大行数（AC-2④）。
INSERT INTO semantic_edge (id,from_node_id,to_node_id,edge_kind,cardinality,fallback_order,coalesce_group,assert_status,assert_sample_rows,note,created_by) VALUES
('e61dde42-6ab3-53d2-b566-dd01e0ed32f4',(SELECT id FROM semantic_node WHERE node_key='MATERIAL' AND dialect='QUOTE'),'613b7811-0156-5ec9-b851-40a1579d0734','LOOKUP','MANY_TO_ONE',NULL,NULL,'NA',NULL,'B-1：物料 → 客户料号，按 material_no 左连。物料为主（用户 2026-09-07 裁决，推翻早前「以客户料号作为主表」）。没有客户料号的物料行照常出现、客户料号列为空（AC-6①）。','seed');

INSERT INTO semantic_edge_key (id,edge_id,left_column,right_column,seq) VALUES
('5cf48966-d02d-5968-8659-7bce73965d01','e61dde42-6ab3-53d2-b566-dd01e0ed32f4','material_no','material_no',0);

-- ============ 4. 页签视图：年降 3 张各自成为一个数据源（B-2，AC-11①） ============
-- 🚦 tab_type 只能取 ComponentService.VALID_TAB_TYPES 里的值 —— 启动期
--    SemanticGraphKeyValueSelfCheck 对这一列硬校验，越界直接让服务起不来。
--    ⇒ 三张年降表复用既有的『费用类』+ 新 variant_key（与另外 8 个费用类变体同形），
--      🚫 不新造 tab_type（那要改 ALL_TAB_TYPES / VALID_TAB_TYPES，撞 backtask 红线）。
--    用户可见的是数据源名（= 锚点节点 display_name），页签类型早已不再由用户手选（task-260904）。
INSERT INTO semantic_tab_view (id,tab_type,variant_key,variant_label,anchor_node_id,switches,dialect,created_by) VALUES
('f9034845-1358-5009-9fc7-b6ed32c6b608','费用类','ANNUAL_DISCOUNT','年降系数','95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4','{}','QUOTE','seed'),
('7b9409fa-8bc2-574e-9a0b-c632006c2cad','费用类','ASSEMBLY_FEE_ANNUAL','组装加工费年降','9901a057-2883-5893-9e89-c6232a0997fe','{}','QUOTE','seed'),
('0b55d63e-f1ac-5264-9a20-ac91794af055','费用类','INCOMING_ANNUAL','来料年降','817fe5f7-66df-5551-b85c-762cf908281b','{}','QUOTE','seed');

-- ============ 5. 页签节点挂载 ============
-- ① 年降 3 张各自 MAIN（一页签一表）
-- ② 客户料号以 AUX 挂 QUOTE/主件 —— 与 V413 里 FUNC_ELEMENT_PRICE 挂法同形（role='AUX', sort_order=1）。
--    挂上它同时也让 FieldTreeBuilder 的 B-49 判据生效：CUSTOMER_PART『已自成一组』⇒
--    syntheticLookupFields 不会再把它的列内联进 MAIN 组（否则同一批字段在面板上出现两次）。
INSERT INTO semantic_tab_view_node (id,view_id,node_id,role,add_dims,sort_order,created_by) VALUES
('cf8001e2-80b8-5aab-b573-8957af8ec650','f9034845-1358-5009-9fc7-b6ed32c6b608','95d3a9e1-e9c2-5fa5-b4c7-e3ca63faa2c4','MAIN','{}',0,'seed'),
('28bf1913-8699-5a05-bf77-3dc27f9ed05f','7b9409fa-8bc2-574e-9a0b-c632006c2cad','9901a057-2883-5893-9e89-c6232a0997fe','MAIN','{}',0,'seed'),
('8b4096eb-31e4-5d4b-84cd-f6c4dd146855','0b55d63e-f1ac-5264-9a20-ac91794af055','817fe5f7-66df-5551-b85c-762cf908281b','MAIN','{}',0,'seed'),
('3301945e-a403-561f-b0b2-8f19d86df343',(SELECT id FROM semantic_tab_view WHERE tab_type='主件' AND variant_key='' AND dialect='QUOTE'),'613b7811-0156-5ec9-b851-40a1579d0734','AUX','{}',1,'seed');

-- ============ 统计（生成时计算，供人工复核） ============
--   新增节点 4 / 节点列 30 / 边 1 / 边键 1 / 页签视图 3 / 页签节点 4
--   ⇒ QUOTE 侧 availableSources 由 11 增至 14（api.md §1.2）；
--     QUOTE/主件 的 groups 由 1 组增至 2 组（api.md §1.3）。
