-- =====================================================================================
--  repair-260916 · B-1 · AC-1 / AC-2 / AC-3 / AC-4 / AC-5 / AC-10 / AC-11 / AC-12
--  来料类数据源的「材料名」：物料表查不到时再查材质表（两段查名）
--
--  现象：来料固定加工费 / 来料其他费用 / 来料回收 等页签里，投入料号是【材质号】（如 00144）时
--        材料名显示「—」—— 这些数据源只连了物料表（MAT_NAME_LK），材质号在物料表里没有。
--  修法：照 V430 里 MATERIAL_BOM 的既有写法，对 11 个数据源：
--        ① 既有的「→ MAT_NAME_LK」查名边归入组 PART_NAME、顺序 1（连接键一条不改）；
--        ② 新增「→ RECIPE_NAME_LK」查名边，组 PART_NAME、顺序 2，
--           连接键只有一条 seq=0：<锚点料号列> = material_recipe.code（🚫 不带客户键：材质表无客户维度）。
--        编译器 SemanticCompiler.resolveLookup 已支持同组多源 COALESCE ⇒ Java 零改动即生成
--        COALESCE(<物料别名>.material_name, <材质别名>.symbol)。
--
--  🚫 本迁移不做：改 V430；改 semantic_node / semantic_node_column；DELETE 任何行；
--     gen_random_uuid()；写死全局边总数（语义图有管理界面，别的库上别的边可能被改过）。
--
--  🔑 定位既有边按 (dialect, from node_key, to node_key) —— 不按 V430 的确定性 id：
--     语义图有管理界面（operation_log 里有 SEMANTIC_EDGE_UPDATE/CREATE），不能假设 id 从未变过。
--
--  幂等：新边/新键 id 为确定性常量 + ON CONFLICT DO NOTHING；第 1 步 UPDATE 只改「还没改过的」行，
--        重放时是真正的零写入（不会反复刷新 updated_at）。
--        ⇒ 可在多个 worktree 的 mvnw test 与内网增量脚本中安全重放。
-- =====================================================================================

-- ── 0. 规格表（单一事实来源）：边与边键都从它派生 ─────────────────────────────────
--    ON COMMIT DROP：Flyway 每个迁移跑在一个事务里，提交即消失，不留任何对象。
CREATE TEMP TABLE t260916_recipe_fallback (
    ord        int  NOT NULL,
    dialect    text NOT NULL,
    from_key   text NOT NULL,
    anchor_col text NOT NULL
) ON COMMIT DROP;

INSERT INTO t260916_recipe_fallback VALUES
-- ── QUOTE：锚点料号列 = input_material_no（投入料号）
 (1 ,'QUOTE'      ,'INCOMING_FIXED_FEE'      ,'input_material_no'),
 (2 ,'QUOTE'      ,'INCOMING_OTHER_FEE'      ,'input_material_no'),
 (3 ,'QUOTE'      ,'INCOMING_RECOVERY'       ,'input_material_no'),
 (4 ,'QUOTE'      ,'INCOMING_ANNUAL'         ,'input_material_no'),
 (5 ,'QUOTE'      ,'SELF_PROCESS_FEE'        ,'input_material_no'),
-- ── COST_BASIC：锚点料号列 = incoming_material_no（来料料号）
 (6 ,'COST_BASIC' ,'INCOMING_OTHER_FEE'      ,'incoming_material_no'),
 (7 ,'COST_BASIC' ,'INCOMING_OTHER_FIXED_FEE','incoming_material_no'),
 (8 ,'COST_BASIC' ,'INCOMING_PROCESS_FEE'    ,'incoming_material_no'),
-- ── COST_DETAIL：同上
 (9 ,'COST_DETAIL','INCOMING_OTHER_FEE'      ,'incoming_material_no'),
 (10,'COST_DETAIL','INCOMING_OTHER_FIXED_FEE','incoming_material_no'),
 (11,'COST_DETAIL','INCOMING_PROCESS_FEE'    ,'incoming_material_no');

-- 🚨 自检：规格表恰好 11 行、序号 1..11 不重不漏、方言分布固定。
--    写错一行的后果是某个数据源的材料名永远查不到材质表，而那在界面上与「本来就没名称」一模一样。
DO $$
DECLARE n int; mx int; d text;
BEGIN
    SELECT count(*), max(ord) INTO n, mx FROM t260916_recipe_fallback;
    IF n <> 11 OR mx <> 11 OR n <> (SELECT count(DISTINCT ord) FROM t260916_recipe_fallback) THEN
        RAISE EXCEPTION 'V444 规格表异常: rows=%, max_ord=%', n, mx;
    END IF;
    SELECT string_agg(dialect || '=' || c, ' ' ORDER BY dialect) INTO d
      FROM (SELECT dialect, count(*) c FROM t260916_recipe_fallback GROUP BY 1) x;
    IF d <> 'COST_BASIC=3 COST_DETAIL=3 QUOTE=5' THEN
        RAISE EXCEPTION 'V444 方言分布异常: %', d;
    END IF;
END $$;

-- ── 1. 既有物料表查名边 → 组 PART_NAME、顺序 1 ─────────────────────────────────────
--    只动 coalesce_group / fallback_order / updated_at；连接键（semantic_edge_key）一条不改。
UPDATE semantic_edge e
   SET coalesce_group = 'PART_NAME',
       fallback_order = 1,
       updated_at     = now()
  FROM t260916_recipe_fallback s, semantic_node fn, semantic_node tn
 WHERE fn.node_key = s.from_key      AND fn.dialect = s.dialect
   AND tn.node_key = 'MAT_NAME_LK'   AND tn.dialect = s.dialect
   AND e.from_node_id = fn.id
   AND e.to_node_id   = tn.id
   AND e.edge_kind = 'LOOKUP'
   AND e.status    = 'ACTIVE'
   AND (e.coalesce_group IS DISTINCT FROM 'PART_NAME' OR e.fallback_order IS DISTINCT FROM 1);

-- ── 2. 新增材质表查名边 → 组 PART_NAME、顺序 2 ─────────────────────────────────────
INSERT INTO semantic_edge (
    id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order,
    coalesce_group, assert_status, assert_sample_rows, note,
    created_at, updated_at, status)
SELECT ('26091601-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       fn.id, tn.id, 'LOOKUP', 'MANY_TO_ONE',
       2, 'PART_NAME',
       'NA',   -- assert_status 是 NOT NULL，与既有查名边取值一致
       NULL,
       'repair-260916 B-1：' || s.dialect || ' ' || s.from_key
           || ' → RECIPE_NAME_LK（材料名第二分支：物料表查不到再查材质表）',
       now(), now(), 'ACTIVE'
  FROM t260916_recipe_fallback s
  JOIN semantic_node fn ON fn.node_key = s.from_key        AND fn.dialect = s.dialect
  JOIN semantic_node tn ON tn.node_key = 'RECIPE_NAME_LK'  AND tn.dialect = s.dialect
ON CONFLICT DO NOTHING;

-- ── 3. 新边的连接键：只有 seq=0 一条，<锚点料号列> = code ───────────────────────────
--    🚫 不补客户键：material_recipe 无客户维度（与 V430 里 MATERIAL_BOM → RECIPE_NAME_LK 同口径）。
INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
SELECT ('26091602-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       ('26091601-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       s.anchor_col, 'code', 0
  FROM t260916_recipe_fallback s
 WHERE EXISTS (SELECT 1 FROM semantic_edge e
                WHERE e.id = ('26091601-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid)
ON CONFLICT DO NOTHING;

-- ── 4. 落库后自检（让错误在启动时就炸，而不是等用户点开配置器才发现）──────────────
--    只数这 11 个数据源自己的边，🚫 不写死全局总数。
DO $$
DECLARE bad text; nk int;
BEGIN
    -- ① 每个数据源恰好 2 条 ACTIVE、组 PART_NAME 的 LOOKUP 边，顺序恰为 {1,2}，
    --    1 → 本方言 MAT_NAME_LK，2 → 本方言 RECIPE_NAME_LK
    SELECT string_agg(s.dialect || '/' || s.from_key || '=' || COALESCE(x.sig, '<无>'), '; ' ORDER BY s.ord)
      INTO bad
      FROM t260916_recipe_fallback s
      LEFT JOIN LATERAL (
          SELECT string_agg(e.fallback_order || ':' || tn.node_key, ',' ORDER BY e.fallback_order, tn.node_key) AS sig
            FROM semantic_node fn
            JOIN semantic_edge e ON e.from_node_id = fn.id
            JOIN semantic_node tn ON tn.id = e.to_node_id
           WHERE fn.node_key = s.from_key AND fn.dialect = s.dialect
             AND tn.dialect = s.dialect
             AND e.edge_kind = 'LOOKUP' AND e.status = 'ACTIVE'
             AND e.coalesce_group = 'PART_NAME'
      ) x ON true
     WHERE x.sig IS DISTINCT FROM '1:MAT_NAME_LK,2:RECIPE_NAME_LK';
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'V444 落库自检失败（期望每个数据源 PART_NAME 组恰为 1:MAT_NAME_LK,2:RECIPE_NAME_LK）: %', bad;
    END IF;

    -- ② 新增的 11 条材质表边各恰有 1 条连接键：seq=0、left=锚点料号列、right='code'
    SELECT count(*) INTO nk
      FROM t260916_recipe_fallback s
     WHERE (SELECT count(*) FROM semantic_edge_key k
             WHERE k.edge_id = ('26091601-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid) = 1
       AND EXISTS (SELECT 1 FROM semantic_edge_key k
                    WHERE k.edge_id = ('26091601-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid
                      AND k.seq = 0 AND k.left_column = s.anchor_col AND k.right_column = 'code');
    IF nk <> 11 THEN
        RAISE EXCEPTION 'V444 落库自检失败: 材质表查名边的连接键合格数=% (期望 11)', nk;
    END IF;
END $$;
