-- =====================================================================================
--  task-260907 · 撤回 V423 建的 FUNC_CUSTOMER_ELEMENT_PRICE 语义节点及其 PRICE 边
--
--  为什么删（D-39 裁决撤回）：
--    V423 的思路是「报价侧元素单价改按客户取」，为此新建了一个函数节点 +
--    一条从 ELEMENT_BOM 出发的单键 PRICE 边。该方案已被裁决撤回，
--    改走并发线的 candidate_materials 方案（让 ds_quote_* 料号进入候选集，
--    从根上解决「新导入料号恒不在候选集」，而不是绕开料号维度）。
--    ⇒ V423 建的这一套成了悬空配置，留着就是 §「同一锚点两条 PRICE 边」的雷。
--
--  🚫 不改 V423 本身（已应用到共享库，改它 = §3.2 契约红线）。本迁移是它的反向补偿。
--
--  🚩 按 node_key 删，不按 uuid：
--     uuid 是「V423 那一次的实例」，node_key 是「这类东西」。
--     干净库重放 / 别的环境里 uuid 可能对不上，node_key 不会。
--
--  🚨 删除顺序不可调换：semantic_edge.to_node_id 的外键是 NO ACTION
--     （confdeltype='a'，并发线实查）。先删节点会外键违约 → 迁移失败
--     → migrate-at-start 让【所有人】启动失败。
--     顺序：edge_key → edge → node_column → node
--
--  📌 dry-run（BEGIN…ROLLBACK）实测影响行数：1 / 1 / 2 / 1，合计 5 行。
-- =====================================================================================

-- ── 1. 边键（挂在边下，先删）──────────────────────────────────────────────────────
DELETE FROM semantic_edge_key k
 USING semantic_edge e, semantic_node t
 WHERE k.edge_id = e.id
   AND e.to_node_id = t.id
   AND t.node_key = 'FUNC_CUSTOMER_ELEMENT_PRICE';

-- ── 2. PRICE 边（挂在节点下，先于节点删）────────────────────────────────────────
DELETE FROM semantic_edge e
 USING semantic_node t
 WHERE e.to_node_id = t.id
   AND t.node_key = 'FUNC_CUSTOMER_ELEMENT_PRICE';

-- ── 3. 节点列 ────────────────────────────────────────────────────────────────────
DELETE FROM semantic_node_column nc
 USING semantic_node n
 WHERE nc.node_id = n.id
   AND n.node_key = 'FUNC_CUSTOMER_ELEMENT_PRICE';

-- ── 4. 函数节点 ──────────────────────────────────────────────────────────────────
DELETE FROM semantic_node
 WHERE node_key = 'FUNC_CUSTOMER_ELEMENT_PRICE';

-- ── 5. 自检（🚩 肯定式）──────────────────────────────────────────────────────────
--    判据来自本任务沉淀：**守卫只写否定式，会在「功能整个缺失」时静默放行。**
--    所以这里不写「没报错就算过」，而是逐条断言删干净了、且【该留的原样还在】。
--
--    🚨 后半段（存量守卫）才是真正要守的东西：
--       ELEMENT_BOM 曾同时挂两条 PRICE 边。删错边 / 多删一个键，
--       存量 21 个组件的元素单价会从「按料号取」静默变成「按客户取」——
--       数值变了，但不报错、不抛异常、页面照常渲染。
DO $$
DECLARE n int; problems text := '';
BEGIN
  -- ① 目标节点已删净
  SELECT count(*) INTO n FROM semantic_node WHERE node_key='FUNC_CUSTOMER_ELEMENT_PRICE';
  IF n <> 0 THEN problems := problems || format('[1] 函数节点应删净，仍剩 %s 行; ', n); END IF;

  -- ② 目标节点列已删净（连带 node_id 悬空的也算）
  SELECT count(*) INTO n FROM semantic_node_column nc
    JOIN semantic_node sn ON sn.id = nc.node_id
   WHERE sn.node_key='FUNC_CUSTOMER_ELEMENT_PRICE';
  IF n <> 0 THEN problems := problems || format('[2] 节点列应删净，仍剩 %s 行; ', n); END IF;

  -- ③ 目标 PRICE 边已删净
  SELECT count(*) INTO n FROM semantic_edge e
    JOIN semantic_node t ON t.id = e.to_node_id
   WHERE t.node_key='FUNC_CUSTOMER_ELEMENT_PRICE';
  IF n <> 0 THEN problems := problems || format('[3] PRICE 边应删净，仍剩 %s 行; ', n); END IF;

  -- ④ 不留悬空边键（边没了键还在 = 脏数据）
  SELECT count(*) INTO n FROM semantic_edge_key k
   WHERE NOT EXISTS (SELECT 1 FROM semantic_edge e WHERE e.id = k.edge_id);
  IF n <> 0 THEN problems := problems || format('[4] 存在 %s 个悬空 edge_key（边已删、键还在）; ', n); END IF;

  -- ⑤ 🚨 存量守卫：旧边 ELEMENT_BOM → FUNC_ELEMENT_PRICE 必须原样还在
  SELECT count(*) INTO n FROM semantic_edge e
    JOIN semantic_node f ON f.id = e.from_node_id
    JOIN semantic_node t ON t.id = e.to_node_id
   WHERE f.node_key='ELEMENT_BOM' AND t.node_key='FUNC_ELEMENT_PRICE'
     AND e.edge_kind='PRICE';
  IF n <> 1 THEN problems := problems || format('[5] 旧 PRICE 边应恰好 1 条，实得 %s; ', n); END IF;

  -- ⑥ 🚨 存量守卫：旧边仍是【两个键】——少一个键，元素单价就少一个 JOIN 条件而静默变值
  SELECT count(*) INTO n FROM semantic_edge_key k
    JOIN semantic_edge e ON e.id = k.edge_id
    JOIN semantic_node t ON t.id = e.to_node_id
   WHERE t.node_key='FUNC_ELEMENT_PRICE' AND e.edge_kind='PRICE';
  IF n <> 2 THEN problems := problems || format('[6] 旧 PRICE 边的键应仍为 2 个，实得 %s; ', n); END IF;

  -- ⑦ 🚨 存量守卫：两个键的形状逐字对上（seq0 编码键 / seq1 料号键）
  SELECT count(*) INTO n FROM semantic_edge_key k
    JOIN semantic_edge e ON e.id = k.edge_id
    JOIN semantic_node t ON t.id = e.to_node_id
   WHERE t.node_key='FUNC_ELEMENT_PRICE' AND e.edge_kind='PRICE'
     AND ((k.seq=0 AND k.left_column='element_code' AND k.right_column='element_code')
       OR (k.seq=1 AND k.left_column='material_no'  AND k.right_column='material_no'));
  IF n <> 2 THEN
    problems := problems || format('[7] 旧边两个键的形状不对（期望 seq0 element_code / seq1 material_no），命中 %s; ', n);
  END IF;

  IF problems <> '' THEN
    RAISE EXCEPTION 'task-260907 V424 撤回自检失败：%', problems;
  END IF;
END $$;
