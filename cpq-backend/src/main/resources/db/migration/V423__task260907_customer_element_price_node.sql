-- =====================================================================================
--  task-260907 · B-17 / AC-8 · D-27
--  新增语义节点 FUNC_CUSTOMER_ELEMENT_PRICE + 从 ELEMENT_BOM 出发的【单键】PRICE 边
--
--  为什么要有它（不是把旧函数换个名字）：
--    f_material_element_price 的候选料号只从 V6 的 material_bom_item ∪ element_bom_item 取
--    ⇒ 新导入的 ds_quote_* 料号【恒不在候选集】（实测 CUST-0004 返 21 个料号，ds_ 独有的 = 0）
--    ⇒ 报价侧新链路用它，元素单价必然整列空。
--    而用户业务规则是「同一客户、同一元素，价格不随料号变；一张报价单里元素价统一」
--    ⇒ 料号维度对报价侧本就是冗余的 ⇒ 改用 f_customer_element_price(客户, 日期)。
--
--  🚫 不改 V413 等历史种子脚本（已应用到共享库，改它 = §3.2 契约红线）。
--
--  🚨 本迁移会让锚点 ELEMENT_BOM 同时挂【两条】PRICE 边（旧的到 FUNC_ELEMENT_PRICE、
--     新的到 FUNC_CUSTOMER_ELEMENT_PRICE）。这在编译器里曾经是个雷：
--     resolvePricePlan 用 edgesFrom(anchor).filter(PRICE).findFirst() 按顺序碰运气取边。
--     ⇒ 已随本任务把编译器改成「按 builder_config 里列引用的函数节点精确匹配」，
--       并对「同一锚点→同一函数节点多条边」显式报 COMPILE_PRICE_EDGE_DUPLICATED。
--     ⚠️ 若有人把编译器回退成 findFirst，本迁移就会变成一颗定时炸弹：
--        存量 21 个组件有一半概率被解析到新边上，生成的 SQL 少一个 material_no JOIN 条件，
--        元素单价会从「按料号取」静默变成「按客户取」——数值变了，但不报错。
-- =====================================================================================

-- ── 1. 函数节点 ────────────────────────────────────────────────────────────────────
--    返回列 (element_code, unit_price, currency, price_unit)：🚨 没有 material_no，
--    这正是 D-27 想要的（元素价与料号无关）。
INSERT INTO semantic_node (
    id, node_key, display_name, short_name, node_kind, physical_table, scope,
    anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator,
    source_handler, dialect, note, created_at, updated_at, status)
VALUES (
    'c7e0a1d2-3b64-4f18-9a55-6d2f8e1c4b70'::uuid,
    'FUNC_CUSTOMER_ELEMENT_PRICE', '价格策略 f_customer_element_price', '价格策略',
    'FUNCTION', NULL, 'NONE', NULL, '{}', NULL,
    'f_customer_element_price(:customerCode, :priceBaseDate)', NULL, NULL, 'QUOTE',
    'task-260907 D-27：报价侧元素单价按【客户】取，与料号无关',
    now(), now(), 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- ── 2. 节点列 ──────────────────────────────────────────────────────────────────────
--    与 FUNC_ELEMENT_PRICE 的列定义同形（unit_price / currency），
--    以便前端配置器的列选择体验一致。
INSERT INTO semantic_node_column (
    id, node_id, db_column, display_name, data_type, is_code, roles, sort_order,
    created_at, updated_at, status)
VALUES
 ('b1a4c907-1f52-4d3e-8c61-0f7a5b2e9d41'::uuid,
  'c7e0a1d2-3b64-4f18-9a55-6d2f8e1c4b70'::uuid,
  'unit_price', '元素单价', 'MONEY', false, '{}', 0, now(), now(), 'ACTIVE'),
 ('b1a4c907-1f52-4d3e-8c61-0f7a5b2e9d42'::uuid,
  'c7e0a1d2-3b64-4f18-9a55-6d2f8e1c4b70'::uuid,
  'currency', '货币', 'TEXT', false, '{}', 1, now(), now(), 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- ── 3. PRICE 边：ELEMENT_BOM → FUNC_CUSTOMER_ELEMENT_PRICE ─────────────────────────
INSERT INTO semantic_edge (
    id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order,
    coalesce_group, assert_status, assert_sample_rows, note,
    created_at, updated_at, status)
SELECT
    'e93f5c18-77a0-42bb-9e14-8c6d0b3a5f27'::uuid,
    n.id,
    'c7e0a1d2-3b64-4f18-9a55-6d2f8e1c4b70'::uuid,
    -- assert_status 是 NOT NULL（默认 'NA'）；这里显式写 'NA'，与既有
    -- ELEMENT_BOM→FUNC_ELEMENT_PRICE 边的取值一致。
    -- 📌 第一版把它写成 NULL，dry-run 直接撞 not-null 约束 —— 那次 BEGIN…ROLLBACK
    --    的价值就在这里：这条错若留到真迁移，就是所有人服务起不来。
    'PRICE', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL,
    'task-260907 D-27：单键边（只按 element_code），元素价与料号无关',
    now(), now(), 'ACTIVE'
FROM semantic_node n
WHERE n.node_key = 'ELEMENT_BOM' AND n.dialect = 'QUOTE'
ON CONFLICT (id) DO NOTHING;

-- ── 4. 边键：🚨【只有 seq0 一个键】────────────────────────────────────────────────
--    旧边 ELEMENT_BOM→FUNC_ELEMENT_PRICE 有两个键：
--        seq0 element_code↔element_code   （编码键）
--        seq1 material_no↔material_no     （料号键）
--    新边【故意只给 seq0】。resolvePricePlan 的 JOIN 拼装是数据驱动的：
--        key[0] 拼编码条件，for(i=1..) 才逐个拼 hf_part_no 条件
--    ⇒ 单键边天然生成【单条件 JOIN】，这一段不需要改编译器。
INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
VALUES ('a2d61b83-4e95-4c07-b3f8-19e7c5a08d64'::uuid,
        'e93f5c18-77a0-42bb-9e14-8c6d0b3a5f27'::uuid,
        'element_code', 'element_code', 0)
ON CONFLICT (id) DO NOTHING;

-- ── 5. 自检（🚩 肯定式，不是只断言「没报错」）──────────────────────────────────────
--    判据来自本任务沉淀：**守卫只写否定式，会在「功能整个缺失」时静默放行。**
--    所以这里逐条断言「东西确实建出来了、且形状对」，而不是「没有异常」。
DO $$
DECLARE n int; problems text := '';
BEGIN
  SELECT count(*) INTO n FROM semantic_node
   WHERE node_key='FUNC_CUSTOMER_ELEMENT_PRICE' AND dialect='QUOTE'
     AND node_kind='FUNCTION' AND status='ACTIVE'
     AND func_signature='f_customer_element_price(:customerCode, :priceBaseDate)';
  IF n <> 1 THEN problems := problems || format('[1] 函数节点期望 1 个，实得 %s; ', n); END IF;

  SELECT count(*) INTO n FROM semantic_node_column nc JOIN semantic_node sn ON sn.id=nc.node_id
   WHERE sn.node_key='FUNC_CUSTOMER_ELEMENT_PRICE' AND nc.status='ACTIVE'
     AND nc.db_column IN ('unit_price','currency');
  IF n <> 2 THEN problems := problems || format('[2] 节点列期望 2 个(unit_price/currency)，实得 %s; ', n); END IF;

  SELECT count(*) INTO n FROM semantic_edge e
    JOIN semantic_node f ON f.id=e.from_node_id
    JOIN semantic_node t ON t.id=e.to_node_id
   WHERE f.node_key='ELEMENT_BOM' AND t.node_key='FUNC_CUSTOMER_ELEMENT_PRICE'
     AND e.edge_kind='PRICE' AND e.status='ACTIVE';
  IF n <> 1 THEN problems := problems || format('[3] PRICE 边期望 1 条，实得 %s; ', n); END IF;

  -- 单键：多一个键就会多拼一个 JOIN 条件，元素价会重新退化成「按料号取」
  SELECT count(*) INTO n FROM semantic_edge_key k
   WHERE k.edge_id='e93f5c18-77a0-42bb-9e14-8c6d0b3a5f27'::uuid;
  IF n <> 1 THEN problems := problems || format('[4] 新边应【只有 1 个键】，实得 %s 个; ', n); END IF;

  SELECT count(*) INTO n FROM semantic_edge_key k
   WHERE k.edge_id='e93f5c18-77a0-42bb-9e14-8c6d0b3a5f27'::uuid
     AND k.seq=0 AND k.left_column='element_code' AND k.right_column='element_code';
  IF n <> 1 THEN problems := problems || format('[5] seq0 应为 element_code↔element_code; '); END IF;

  -- 存量守卫：旧边必须原样还在、且仍是两个键（本迁移是加法，不许动它）
  SELECT count(*) INTO n FROM semantic_edge_key k JOIN semantic_edge e ON e.id=k.edge_id
    JOIN semantic_node t ON t.id=e.to_node_id
   WHERE t.node_key='FUNC_ELEMENT_PRICE' AND e.edge_kind='PRICE';
  IF n <> 2 THEN problems := problems || format('[6] 旧 PRICE 边的键应仍为 2 个，实得 %s; ', n); END IF;

  IF problems <> '' THEN
    RAISE EXCEPTION 'task-260907 V423 语义节点自检失败：%', problems;
  END IF;
END $$;
