-- V416__task260819_v9_bridge_narrow_form.sql
-- 🤖 由 dev-docs/task-260819-取数配置器/scripts/gen_v9_semantic_seed.py 生成，🚫 不要手改。
-- task-260819 · v9 · B-43 形态改造（用户 2026-09-04 裁决）
--
-- 【改什么】
--   ① 28 条料号桥边 edge_kind：LOOKUP → NARROW
--   ② 删掉 32 行桥的 AUX 挂载（semantic_tab_view_node）—— 桥不再是可拖的数据源
--
-- 【为什么】桥的用法整个反了。同一张表、同一列，用在 SELECT 里还是 WHERE 里，
--   差别就是扇出与不扇出：
--     ❌ 旧：LEFT JOIN ds_quote_material ON dqm.production_no = 锚点.production_no
--        方向 生产料号 → 销售料号。实测 45 行 / 24 个不同 production_no / 重复组 1
--        （TEST0813-P01-PROD 对应 TEST0813-P01 与 TEST0813-P01-BD1 两个销售料号）
--        ⇒ 锚点行被复制，核价行数与金额**静默翻倍**。端到端实测：1 行 → 2 行。
--     ✅ 新：WHERE 锚点.production_no IN (SELECT production_no FROM ds_quote_material
--                                        WHERE material_no = ANY(:total_material_no))
--        方向 销售料号 → 生产料号。实测 45 行 / 45 个不同 material_no / 重复组 0
--        ⇒ 半连接谓词，**永不扇出**。
--   业务口径（用户原话）：「核价页签上不用显示销售料号，销售料号在产品卡片上，
--   是产品卡片的基础属性」「核价是按照报价侧提供的 产品卡片销售料号 → 找到对应的
--   生产料号 → 展示该生产料号的 BOM」⇒ 销售料号是**入参**，不是展示列。
--
-- 【为什么必须新造 edge_kind】现有四种边**都表达不了**这件事（2026-09-04 逐点核实）：
--   LOOKUP/JOIN → 产 FROM 项（LEFT/INNER JOIN），会扇出；
--   SUB         → 产的是相关标量子查询，是**列表达式**不是 WHERE 谓词；
--   GRAIN       → 按定义就是展开行数。
--   而 semantic_node.fixed_predicate 只在**边的目标节点**上被消费
--   （SemanticCompiler 第 328 / 499 行，都是拼进 JOIN 的 ON 子句），锚点侧不读。
--   ⇒ 契约新增取值 'NARROW'：拿 from 表的键解析出 to 表的键，用结果收窄 from 表；
--     产物是 WHERE 半连接谓词，🚫 不产 FROM 项、🚫 不产显示列、🚫 不进字段面板。
--     semantic_edge 的**列结构不动**，只是多一个取值。
--     编译器侧的 case "NARROW" 分支由后端 #1 并行实现；本迁移只负责边的数据。
--
-- 📌 V414（把这 28 条边改成 ONE_TO_MANY）自此**无实际意义**（没有 JOIN 就没有基数问题），
--   但它已应用共享库，🚫 不撤销、不改号，留着即可。

-- ① 边形态：按主键逐条更新（28 个确定性 UUIDv5，非无 WHERE 的全表 UPDATE）
UPDATE semantic_edge SET edge_kind = 'NARROW', updated_by = 'seed', updated_at = now(),
       assert_status = 'NA', assert_sample_rows = NULL,
       note = '料号桥（输入收窄）：销售料号 → 生产料号。编译成 WHERE 半连接谓词，不产 JOIN、不产显示列。桥里查不到对应生产料号时收窄结果为空 ⇒ 0 行，不报错。'
 WHERE edge_kind = 'LOOKUP'
   AND id IN (
     '1a0b8b69-23d6-593a-8924-cf7774dd228c', -- COST_BASIC.MATERIAL
     '7cb23abf-828c-58b1-a0c5-22523a5dc339', -- COST_BASIC.MATERIAL_BOM
     '8686698f-d890-5e15-97b9-d8a18b76efb4', -- COST_BASIC.ELEMENT_BOM
     '47e07ef5-7485-56c2-8f9c-8c07f43e0711', -- COST_BASIC.INCOMING_PROCESS_FEE
     '9a96a06d-a39d-5fd7-ac3c-f4d1c238d678', -- COST_BASIC.INCOMING_OTHER_FEE
     '3da99248-fafc-5aee-8929-492d4520eb13', -- COST_BASIC.INCOMING_OTHER_FIXED_FEE
     '01f12c99-1ee4-5347-a0de-1a05007159e6', -- COST_BASIC.PROCESS_ASSEMBLY_FEE
     'c3925dda-d8a8-5175-b939-fc95ea2b769a', -- COST_BASIC.OUTSOURCED_PROCESS
     '1faa9af2-05ca-5e4b-8daa-40670c45f338', -- COST_BASIC.FINISHED_RATIO_FEE
     '00ad3374-9fb7-5683-96c2-cb46640e99a3', -- COST_BASIC.FINISHED_FIXED_FEE
     '18efe81e-efb7-5eb0-846a-978e24585409', -- COST_DETAIL.MATERIAL
     '903b6985-6447-55a2-ac67-6781a26ea82b', -- COST_DETAIL.MATERIAL_BOM
     '2a905de6-7194-5553-9280-d606c9db65a3', -- COST_DETAIL.ELEMENT_BOM
     '9a4cbfdc-7243-56db-bcdb-210c9b5e9387', -- COST_DETAIL.CAPACITY
     '9e8646a8-106a-56f1-b5f2-af96190f9e9a', -- COST_DETAIL.DEPRECIATION
     '92e6247a-2870-508e-a0f6-cc9e60dc5174', -- COST_DETAIL.PRODUCTION_ENERGY
     'afdb3ad4-d177-5d64-800c-9fcd94d011d3', -- COST_DETAIL.AUXILIARY_ENERGY
     'c00ff53b-6662-50cb-b251-c61ff025efa3', -- COST_DETAIL.TOOLING
     '11a21a3f-c77e-5a0e-842e-3fab1271b5a1', -- COST_DETAIL.CONSUMABLE
     '4bced0a0-af25-52c1-8cf9-b09eee85216e', -- COST_DETAIL.PACKAGING
     '347dfc1e-d428-5996-85e1-d2d475cf69b4', -- COST_DETAIL.INCOMING_PROCESS_FEE
     '334a93b6-9095-500b-9b5b-d7bb9e4d6233', -- COST_DETAIL.INCOMING_OTHER_FEE
     '2b92049a-1899-544b-94e5-adfe5e6ac64a', -- COST_DETAIL.INCOMING_OTHER_FIXED_FEE
     '85224486-bf5e-5488-bad7-3781eb3dbda7', -- COST_DETAIL.PROCESS_ASSEMBLY_FEE
     '8fe61eec-f1c1-5dea-bb72-99e3b70fecc4', -- COST_DETAIL.OUTSOURCED_PROCESS
     '9577a41b-e34c-5f31-9c6d-598911fbdb72', -- COST_DETAIL.PLATING_COST
     'ab6cdf06-54d7-5fca-9857-f788b5d5baa0', -- COST_DETAIL.FINISHED_RATIO_FEE
     'b891f7cf-be63-56f4-b1e2-f7263f6ae3fa' -- COST_DETAIL.FINISHED_FIXED_FEE
   );

-- ② 摘掉桥的 AUX 挂载：字段面板据此列出可拖数据源，删掉它 = 销售料号不再出现在核价页签
DELETE FROM semantic_tab_view_node WHERE id IN (
     '14523630-8cdf-52a2-a5b2-85128501632b', -- COST_BASIC.MATERIAL
     '886c5a58-2bfd-5abc-ae3b-bf5a2ae02f44', -- COST_BASIC.MATERIAL_BOM
     'ea8442b5-7769-5d9a-93da-c9cf52bd1866', -- COST_BASIC.MATERIAL_BOM
     '686300c6-1f2e-5bc0-9711-667b3f5966fa', -- COST_BASIC.MATERIAL_BOM
     'fb3cde21-352f-5a64-9af0-4f75c5b38642', -- COST_BASIC.ELEMENT_BOM
     '25e6bf5a-4aea-57da-85a2-6c237a9b71ce', -- COST_BASIC.INCOMING_PROCESS_FEE
     'b8875674-c93d-51d7-8283-e71a08fb115b', -- COST_BASIC.INCOMING_OTHER_FEE
     '62e1404b-828b-5777-a0cb-9c548486301b', -- COST_BASIC.INCOMING_OTHER_FIXED_FEE
     '320ad6e3-d02d-503c-ba1a-e7941df3a531', -- COST_BASIC.PROCESS_ASSEMBLY_FEE
     '4b1fee2e-22d4-5696-813b-11e70f3d9be9', -- COST_BASIC.OUTSOURCED_PROCESS
     'db91d37c-d361-548a-8871-da804090eff6', -- COST_BASIC.FINISHED_RATIO_FEE
     '65f286d9-6573-50c8-92fc-2caebcf14072', -- COST_BASIC.FINISHED_FIXED_FEE
     'd3cd7e2b-c0ef-5f86-aabe-d9b948fa77b3', -- COST_DETAIL.MATERIAL
     '5e26c2f2-de54-5ea0-858e-bf7baf4bb6a1', -- COST_DETAIL.MATERIAL_BOM
     '063ba252-a0ad-5eca-b23d-6888940a8ab5', -- COST_DETAIL.MATERIAL_BOM
     'cae45acb-4e6f-5242-9103-3956a64044ce', -- COST_DETAIL.MATERIAL_BOM
     '884e8406-4871-51f9-be79-f2209b96d45d', -- COST_DETAIL.ELEMENT_BOM
     '5904fec1-72f2-5107-a789-2da16f5bb994', -- COST_DETAIL.CAPACITY
     '53e7b16a-395d-5e95-a1f4-1885e7913634', -- COST_DETAIL.DEPRECIATION
     '1c45ba76-c042-5cde-8567-6c0e5ccc69ad', -- COST_DETAIL.PRODUCTION_ENERGY
     '498f52af-d4a5-5a60-acae-bede8405c624', -- COST_DETAIL.AUXILIARY_ENERGY
     '18d8b961-397d-560d-9cd8-2443217e2052', -- COST_DETAIL.TOOLING
     '4537756a-c023-52ba-80b3-82e18a75d943', -- COST_DETAIL.CONSUMABLE
     '85af561e-ac77-5e99-bb3b-d0bf83a5b810', -- COST_DETAIL.PACKAGING
     '29ce6e35-4809-5a33-a147-0de1540c69a4', -- COST_DETAIL.INCOMING_PROCESS_FEE
     'c5119662-3c38-53c2-9434-40f2e910c78c', -- COST_DETAIL.INCOMING_OTHER_FEE
     '54a1321d-5bbd-5135-bf34-bb4cae975274', -- COST_DETAIL.INCOMING_OTHER_FIXED_FEE
     '766e1011-6fb9-57a4-885f-c3bd27a0df95', -- COST_DETAIL.PROCESS_ASSEMBLY_FEE
     '016f5edd-f666-5597-bcbc-c9a21540d118', -- COST_DETAIL.OUTSOURCED_PROCESS
     '6d983ced-eb1d-5681-8e1e-aa571f1b0e4e', -- COST_DETAIL.PLATING_COST
     'cd9bf5b6-bf5e-53f9-9d0b-6b35c3471d5f', -- COST_DETAIL.FINISHED_RATIO_FEE
     '2fb51a11-7e63-5763-8888-9d68519e9bed' -- COST_DETAIL.FINISHED_FIXED_FEE
   );

-- 落地守卫：对不上就**报错中止**，而不是静默更新/删除 0 行。
DO $$
DECLARE e_ok int; e_bad int; aux_left int;
BEGIN
  SELECT count(*) INTO e_ok  FROM semantic_edge e JOIN semantic_node t ON t.id = e.to_node_id
   WHERE t.node_key = 'QUOTE_MATERIAL_BRIDGE' AND e.edge_kind = 'NARROW';
  SELECT count(*) INTO e_bad FROM semantic_edge e JOIN semantic_node t ON t.id = e.to_node_id
   WHERE t.node_key = 'QUOTE_MATERIAL_BRIDGE' AND e.edge_kind <> 'NARROW';
  SELECT count(*) INTO aux_left FROM semantic_tab_view_node tvn
    JOIN semantic_node n ON n.id = tvn.node_id
   WHERE n.node_key = 'QUOTE_MATERIAL_BRIDGE';
  IF e_ok <> 28 OR e_bad <> 0 OR aux_left <> 0 THEN
    RAISE EXCEPTION '料号桥形态改造未落全：期望 % 条 NARROW 边 / 0 条其它 / 0 行 AUX 挂载，实得 % / % / %', 28, e_ok, e_bad, aux_left;
  END IF;
END $$;
