-- =====================================================================================
--  repair-260910 · B-1 · AC-1 / AC-2 / AC-4 / AC-5 / AC-6 / AC-7 / AC-9 / AC-10
--  核价侧元素取价：换客户级函数 + 双键降为单键（料号维度从取价键里彻底移除）
--
--  【缺陷】核价侧取价是 element_code + sales_material_no 双键 JOIN，而 sales_material_no
--          由「生产料号 → 销售料号」桥接得到；核价 BOM 树的中间件/内部件（零件13/零件15/
--          外购件1 …）在业务上本来就没有销售料号 ⇒ 第二键恒不成立 ⇒ 元素单价恒空 ⇒
--          材料费用（FORMULA）恒 0 ⇒ 页签小计 0 ⇒ 核价总额系统性偏低且不报错。
--          这不是数据缺失，是桥接方案对这类料号的结构性失效。
--
--  【裁决】A0-1（用户 2026-09-10）：「元素价格和销售料号没有直接关系，元素价格只根据
--          元素符号和客户编号的策略进行取价」⇒ 核价侧改用客户级函数
--          f_customer_element_price(:customerCode, :priceBaseDate)，只按 element_code 单键连接。
--
--  【Java 零改动依据】SemanticCompiler 价格 JOIN 生成处，第 2..N 个连接键包在
--          `if (keys.size() > 1)` 里 ⇒ 删掉 seq=1 边键后 keys.size()==1，该分支不进入，
--          JOIN 自然只剩单键；函数签名直接取自节点 func_signature。
--          ⇒ 本次是纯语义图数据配置，SemanticCompiler / 前端 / HTTP 契约全部零改动。
--
--  【本次不碰】两张 v_ds_cost_*_element_bom_all 视图及其 sales_material_no 桥接列
--          （留而不用：删它要同步改 CostAllVersionViewSelfCheck 启动守卫，风险大于收益，
--            用户 2026-09-10 裁决不登记 BACKLOG）；CostAllVersionViewSelfCheck 零改动；
--          f_material_element_price / f_customer_element_price 两个函数本身零改动；
--          QUOTE 方言一行不动（报价侧继续走双键 + f_material_element_price）。
--  ⇒ 本迁移无任何 DDL、无 DROP / TRUNCATE / 无 WHERE 的 DELETE·UPDATE，
--    也不需要 V434 那套「守卫先进 master」的两阶段落库纪律。
-- =====================================================================================

-- ── 1. 两个核价方言的 FUNCTION 节点：换函数签名 + 改显示名 + 记裁决来由 ──────────────
--    🚫 WHERE 精确钉住 node_key + dialect，QUOTE 方言不在命中面内。
UPDATE semantic_node
   SET func_signature = 'f_customer_element_price(:customerCode, :priceBaseDate)',
       display_name   = '价格策略 f_customer_element_price',
       note = 'repair-260910（A0-1，用户 2026-09-10 裁决）：别名固定为 cep。'
              || '取价键已从「element_code + sales_material_no 双键」降为「element_code 单键」，'
              || '函数由 f_material_element_price 换为客户级的 f_customer_element_price。'
              || '裁决依据（用户原话）：「元素价格和销售料号没有直接关系，元素价格只根据元素符号和客户编号的策略进行取价」。'
              || '根因：核价 BOM 树里的中间件/内部件（零件13/零件15/外购件1 …）业务上没有销售料号，'
              || '桥接列 v_ds_cost_*_element_bom_all.sales_material_no 恒为 NULL ⇒ 第二键恒不成立 ⇒ 元素单价恒空。'
              || '🚨 知情取舍：换成客户级函数后，核价侧不再读 f_material_element_price 的 versioned 分支'
              || '（material_price_version_ref 按 customer_no + 销售料号指向的冻结版本价），只取实时客户级策略价。'
              || '本库该分支当前 0 行、对现值无影响；其去向见 BACKLOG「单据级元素价格表」（A0-3 裁决本次不做）。'
              || '桥接列 sales_material_no 本身保留但不再被取价使用（删它要改 CostAllVersionViewSelfCheck 启动守卫）。',
       updated_at = now()
 WHERE node_key = 'FUNC_ELEMENT_PRICE'
   AND dialect IN ('COST_BASIC', 'COST_DETAIL');

-- ── 2. 两条核价 PRICE 边的注释同步更正（原文在本次之后会主动误导下一个人）────────────
UPDATE semantic_edge e
   SET note = 'repair-260910：' || fn.dialect || ' ELEMENT_BOM → FUNC_ELEMENT_PRICE（单键：element_code）。'
              || '原双键（element_code + sales_material_no）已由本次迁移降为单键，见 A0-1 裁决。',
       updated_at = now()
  FROM semantic_node fn, semantic_node tn
 WHERE fn.id = e.from_node_id
   AND tn.id = e.to_node_id
   AND e.edge_kind = 'PRICE'
   AND fn.node_key = 'ELEMENT_BOM'
   AND tn.node_key = 'FUNC_ELEMENT_PRICE'
   AND fn.dialect IN ('COST_BASIC', 'COST_DETAIL');

-- ── 3. 删除两条核价 PRICE 边的 seq=1 边键（sales_material_no → material_no）──────────
--    🚦 CLAUDE.md §3.2 第 1 步「先量化影响面」：执行前用完全相同的 WHERE 跑过
--       SELECT count(*)，实测 = 2 行（cpq_db_0910，2026-09-10）：
--         26090905-0000-4000-8000-000000000002 | edge 26090904-…-001 (COST_BASIC)
--         26090905-0000-4000-8000-000000000004 | edge 26090904-…-002 (COST_DETAIL)
--    ✅ 带精确 WHERE，命中面 2 行，可由本迁移的逆操作（重新 INSERT 两行边键）恢复。
--    ✅ 保留 seq=0（element_code → element_code）；QUOTE 侧 2 条边键不在命中面内。
DELETE FROM semantic_edge_key k
 WHERE k.seq = 1
   AND k.edge_id IN (
        SELECT e.id
          FROM semantic_edge e
          JOIN semantic_node fn ON fn.id = e.from_node_id
          JOIN semantic_node tn ON tn.id = e.to_node_id
         WHERE e.edge_kind = 'PRICE'
           AND fn.node_key = 'ELEMENT_BOM'
           AND tn.node_key = 'FUNC_ELEMENT_PRICE'
           AND fn.dialect IN ('COST_BASIC', 'COST_DETAIL'));

-- ── 4. 落库自检（口径同 V434 §8）────────────────────────────────────────────────────
DO $$
DECLARE
    n_new_func   int;  -- 核价两节点已换成新函数
    n_old_func   int;  -- 核价侧不应再有旧函数
    n_cost_edge  int;  -- 核价 PRICE 边条数
    n_cost_key   int;  -- 核价 PRICE 边键总数（两条边各 1 ⇒ 2）
    n_bad_edge   int;  -- 键数 <> 1 的核价 PRICE 边
    n_seq0_bad   int;  -- seq=0 不是 element_code→element_code 的核价边键
    n_quote_key  int;  -- QUOTE 侧边键数（防误伤，必须仍为 2）
    n_quote_func int;  -- QUOTE 侧函数签名（防误伤，必须仍为旧函数）
BEGIN
    SELECT count(*) INTO n_new_func FROM semantic_node
     WHERE node_key='FUNC_ELEMENT_PRICE' AND dialect IN ('COST_BASIC','COST_DETAIL')
       AND func_signature = 'f_customer_element_price(:customerCode, :priceBaseDate)';

    SELECT count(*) INTO n_old_func FROM semantic_node
     WHERE node_key='FUNC_ELEMENT_PRICE' AND dialect IN ('COST_BASIC','COST_DETAIL')
       AND func_signature LIKE '%f_material_element_price%';

    SELECT count(*) INTO n_cost_edge FROM semantic_edge e
      JOIN semantic_node fn ON fn.id=e.from_node_id
      JOIN semantic_node tn ON tn.id=e.to_node_id
     WHERE e.edge_kind='PRICE' AND fn.node_key='ELEMENT_BOM'
       AND tn.node_key='FUNC_ELEMENT_PRICE' AND fn.dialect IN ('COST_BASIC','COST_DETAIL');

    SELECT count(*) INTO n_cost_key FROM semantic_edge_key k
      JOIN semantic_edge e  ON e.id = k.edge_id
      JOIN semantic_node fn ON fn.id = e.from_node_id
      JOIN semantic_node tn ON tn.id = e.to_node_id
     WHERE e.edge_kind='PRICE' AND fn.node_key='ELEMENT_BOM'
       AND tn.node_key='FUNC_ELEMENT_PRICE' AND fn.dialect IN ('COST_BASIC','COST_DETAIL');

    SELECT count(*) INTO n_bad_edge FROM (
        SELECT e.id, count(k.id) AS kc
          FROM semantic_edge e
          JOIN semantic_node fn ON fn.id = e.from_node_id
          JOIN semantic_node tn ON tn.id = e.to_node_id
          LEFT JOIN semantic_edge_key k ON k.edge_id = e.id
         WHERE e.edge_kind='PRICE' AND fn.node_key='ELEMENT_BOM'
           AND tn.node_key='FUNC_ELEMENT_PRICE' AND fn.dialect IN ('COST_BASIC','COST_DETAIL')
         GROUP BY e.id) t
     WHERE t.kc <> 1;

    SELECT count(*) INTO n_seq0_bad FROM semantic_edge_key k
      JOIN semantic_edge e  ON e.id = k.edge_id
      JOIN semantic_node fn ON fn.id = e.from_node_id
      JOIN semantic_node tn ON tn.id = e.to_node_id
     WHERE e.edge_kind='PRICE' AND fn.node_key='ELEMENT_BOM'
       AND tn.node_key='FUNC_ELEMENT_PRICE' AND fn.dialect IN ('COST_BASIC','COST_DETAIL')
       AND NOT (k.seq = 0 AND k.left_column = 'element_code' AND k.right_column = 'element_code');

    SELECT count(*) INTO n_quote_key FROM semantic_edge_key k
      JOIN semantic_edge e  ON e.id = k.edge_id
      JOIN semantic_node fn ON fn.id = e.from_node_id
      JOIN semantic_node tn ON tn.id = e.to_node_id
     WHERE e.edge_kind='PRICE' AND tn.node_key='FUNC_ELEMENT_PRICE' AND fn.dialect = 'QUOTE';

    SELECT count(*) INTO n_quote_func FROM semantic_node
     WHERE node_key='FUNC_ELEMENT_PRICE' AND dialect='QUOTE'
       AND func_signature = 'f_material_element_price(:customerCode, :priceBaseDate)';

    IF n_new_func <> 2 OR n_old_func <> 0 OR n_cost_edge <> 2 OR n_cost_key <> 2
       OR n_bad_edge <> 0 OR n_seq0_bad <> 0 OR n_quote_key <> 2 OR n_quote_func <> 1 THEN
        RAISE EXCEPTION 'V440 落库自检失败: newFunc=%(期望2) oldFunc=%(期望0) costEdges=%(期望2) costKeys=%(期望2) badEdges=%(期望0) seq0Bad=%(期望0) quoteKeys=%(期望2) quoteFunc=%(期望1)',
              n_new_func, n_old_func, n_cost_edge, n_cost_key, n_bad_edge, n_seq0_bad, n_quote_key, n_quote_func;
    END IF;
END $$;
