-- V414__task260819_v9_bridge_cardinality_fix.sql
-- 🤖 由 dev-docs/task-260819-取数配置器/scripts/gen_v9_semantic_seed.py 生成，🚫 不要手改。
-- task-260819 · v9 · B-43 修正（AC-111 / AC-112 / AC-121）
--
-- 【改什么】28 条料号桥边（核价锚点 → QUOTE_MATERIAL_BRIDGE）的 cardinality：
--     MANY_TO_ONE  →  ONE_TO_MANY
--
-- 【为什么】MANY_TO_ONE 在本项目的语义是「右侧连接键在目标表里唯一」——
--   SemanticGraphValidator#checkEdgeCardinality 就是按这个跑断言的
--   （GROUP BY 右键 HAVING count(*)>1，有重复即 FAIL，提示语原文：「改成 ONE_TO_MANY，
--   或补一组连接键把粒度收窄到唯一」）。而右键 ds_quote_material.production_no **不唯一**：
--     2026-09-04 在 cpq_db_0724 实测 —— 45 行 / 25 行 production_no 非空 / 24 个不同值 /
--     重复组 1 组：TEST0813-P01-PROD ×2（销售料号 TEST0813-P01 与 TEST0813-P01-BD1 共用）
--   🚦 用户裁决（2026-09-04）：**多个销售料号共用一个生产料号是合法业务**，
--      不是脏数据 ⇒ 不能靠清数据让断言变绿，只能把声明改对。
--
-- 【方向】边是「核价锚点 → 桥」。锚点侧一个 production_no，桥侧可能有 N 行 ⇒
--   从 from 看向 to 是一对多 ⇒ ONE_TO_MANY。改完这条边不再进 MANY_TO_ONE 断言，
--   assert_status 归 NA（recomputeAssertStatus 对非 MANY_TO_ONE 边一律标 NA，
--   刻意不标 PASS —— 标 PASS 才是虚假的绿）。
--
-- 🚨 【本迁移不解决扇出】—— 声明改对 ≠ 行为改对。
--   SemanticCompiler 全文没有一处读 cardinality（2026-09-04 grep 实证），LOOKUP 边恒编译成
--   裸 LEFT JOIN ⇒ 右键重复时**锚点行会被复制**，核价行数与金额静默翻倍。
--   实证（只读，桥数据为共享库真实数据）：
--     锚点 1 行 × TEST0813-P01-PROD  →  经桥 LEFT JOIN 后 2 行
--     锚点 1 行 × 3110520789        →  经桥 LEFT JOIN 后 1 行
--   去重/聚合修法涉及 builder/compiler/**（后端 #1 的文件）或桥的建模形态，
--   已报主线待裁，🚫 本迁移不擅自扩范围。

-- 按主键逐条更新（28 个确定性 UUIDv5，非无 WHERE 的全表 UPDATE）。
UPDATE semantic_edge SET cardinality = 'ONE_TO_MANY', updated_by = 'seed', updated_at = now(),
       assert_status = 'NA', assert_sample_rows = NULL
 WHERE cardinality = 'MANY_TO_ONE'
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

-- 落地守卫：id 对不上就**报错中止**，而不是静默更新 0 行。
-- （种子若被重灌成别的 UUID，静默 0 行 = 断言继续按错的基数跑 = 回到本次要修的那个坑。）
DO $$
DECLARE bad_cnt int; ok_cnt int;
BEGIN
  SELECT count(*) INTO ok_cnt FROM semantic_edge e
    JOIN semantic_node t ON t.id = e.to_node_id
   WHERE t.node_key = 'QUOTE_MATERIAL_BRIDGE' AND e.cardinality = 'ONE_TO_MANY';
  SELECT count(*) INTO bad_cnt FROM semantic_edge e
    JOIN semantic_node t ON t.id = e.to_node_id
   WHERE t.node_key = 'QUOTE_MATERIAL_BRIDGE' AND e.cardinality <> 'ONE_TO_MANY';
  IF ok_cnt <> 28 OR bad_cnt <> 0 THEN
    RAISE EXCEPTION '料号桥基数修正未落全：期望 % 条 ONE_TO_MANY / 0 条其它，实得 % / %',
      28, ok_cnt, bad_cnt;
  END IF;
END $$;
