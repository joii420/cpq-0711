-- =====================================================================================
--  CPQ 内网数据库增量升级脚本
--
--  源迁移版本  : V444（V444__repair260916_lookup_name_recipe_fallback.sql）
--  升级区间    : Flyway 基线 V443  ->  V444
--  生成日期    : 2026-09-16
--  适用前置    : cpq-init-empty.sql（基线 V439）+ update-260913-v440-v443-record-batch-and-price.sql
--  执行环境    : Navicat（PG 16.13），整份文件一次性执行
--
--  本次改了什么（一件事，只改语义图配置种子，不改任何表结构）
--    V444  来料类 11 个数据源的「材料名」改为两段查：物料表查不到再查材质表
--          （报价侧 5 个 + 基础核价 3 个 + 明细核价 3 个）
--          ① 既有「→ 物料（查名）」边归入组 PART_NAME、顺序 1（连接键不动）
--          ② 新增「→ 材质（查名）」边，组 PART_NAME、顺序 2，
--             连接键仅一条：<锚点料号列> = material_recipe.code（不带客户键）
--
--  幂等性：全文可重复执行。新边/新键为确定性 id + ON CONFLICT DO NOTHING；
--          UPDATE 只命中「尚未改过」的行，第二遍执行为 0 行
--  执行顺序：配置 -> Flyway 基线 -> 自检（只读）
--  本脚本无 DELETE、无 DDL、无函数、全文无美元符号
--
--  ⚠️ 与 Flyway 迁移 V444 的差异（仅形态，不改语义）：
--     迁移里用 ON COMMIT DROP 临时规格表 + DO 块自检；Navicat 默认自动提交，
--     临时表会在建完的瞬间被丢弃、DO 块需要美元引用 ⇒ 本脚本改为内联 VALUES + 只读 SELECT 自检
-- =====================================================================================

SET search_path = public;


-- =====================================================================================
-- 第 1 节 · V444 —— 既有物料表查名边 → 组 PART_NAME、顺序 1
-- =====================================================================================

UPDATE semantic_edge e
   SET coalesce_group = 'PART_NAME',
       fallback_order = 1,
       updated_at     = now()
  FROM (VALUES
        ('QUOTE'      ,'INCOMING_FIXED_FEE'      ),
        ('QUOTE'      ,'INCOMING_OTHER_FEE'      ),
        ('QUOTE'      ,'INCOMING_RECOVERY'       ),
        ('QUOTE'      ,'INCOMING_ANNUAL'         ),
        ('QUOTE'      ,'SELF_PROCESS_FEE'        ),
        ('COST_BASIC' ,'INCOMING_OTHER_FEE'      ),
        ('COST_BASIC' ,'INCOMING_OTHER_FIXED_FEE'),
        ('COST_BASIC' ,'INCOMING_PROCESS_FEE'    ),
        ('COST_DETAIL','INCOMING_OTHER_FEE'      ),
        ('COST_DETAIL','INCOMING_OTHER_FIXED_FEE'),
        ('COST_DETAIL','INCOMING_PROCESS_FEE'    )
       ) AS s(dialect, from_key),
       semantic_node fn, semantic_node tn
 WHERE fn.node_key = s.from_key      AND fn.dialect = s.dialect
   AND tn.node_key = 'MAT_NAME_LK'   AND tn.dialect = s.dialect
   AND e.from_node_id = fn.id
   AND e.to_node_id   = tn.id
   AND e.edge_kind = 'LOOKUP'
   AND e.status    = 'ACTIVE'
   AND (e.coalesce_group IS DISTINCT FROM 'PART_NAME' OR e.fallback_order IS DISTINCT FROM 1);


-- =====================================================================================
-- 第 2 节 · V444 —— 新增材质表查名边（组 PART_NAME、顺序 2）
-- =====================================================================================

INSERT INTO semantic_edge (
    id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order,
    coalesce_group, assert_status, assert_sample_rows, note,
    created_at, updated_at, status)
SELECT ('26091601-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       fn.id, tn.id, 'LOOKUP', 'MANY_TO_ONE',
       2, 'PART_NAME', 'NA', NULL,
       'repair-260916 B-1：' || s.dialect || ' ' || s.from_key
           || ' → RECIPE_NAME_LK（材料名第二分支：物料表查不到再查材质表）',
       now(), now(), 'ACTIVE'
  FROM (VALUES
        (1 ,'QUOTE'      ,'INCOMING_FIXED_FEE'      ),
        (2 ,'QUOTE'      ,'INCOMING_OTHER_FEE'      ),
        (3 ,'QUOTE'      ,'INCOMING_RECOVERY'       ),
        (4 ,'QUOTE'      ,'INCOMING_ANNUAL'         ),
        (5 ,'QUOTE'      ,'SELF_PROCESS_FEE'        ),
        (6 ,'COST_BASIC' ,'INCOMING_OTHER_FEE'      ),
        (7 ,'COST_BASIC' ,'INCOMING_OTHER_FIXED_FEE'),
        (8 ,'COST_BASIC' ,'INCOMING_PROCESS_FEE'    ),
        (9 ,'COST_DETAIL','INCOMING_OTHER_FEE'      ),
        (10,'COST_DETAIL','INCOMING_OTHER_FIXED_FEE'),
        (11,'COST_DETAIL','INCOMING_PROCESS_FEE'    )
       ) AS s(ord, dialect, from_key)
  JOIN semantic_node fn ON fn.node_key = s.from_key       AND fn.dialect = s.dialect
  JOIN semantic_node tn ON tn.node_key = 'RECIPE_NAME_LK' AND tn.dialect = s.dialect
ON CONFLICT DO NOTHING;


-- =====================================================================================
-- 第 3 节 · V444 —— 新边的连接键（seq=0，<锚点料号列> = code，不带客户键）
-- =====================================================================================

INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
SELECT ('26091602-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       ('26091601-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       s.anchor_col, 'code', 0
  FROM (VALUES
        (1 ,'input_material_no'   ),
        (2 ,'input_material_no'   ),
        (3 ,'input_material_no'   ),
        (4 ,'input_material_no'   ),
        (5 ,'input_material_no'   ),
        (6 ,'incoming_material_no'),
        (7 ,'incoming_material_no'),
        (8 ,'incoming_material_no'),
        (9 ,'incoming_material_no'),
        (10,'incoming_material_no'),
        (11,'incoming_material_no')
       ) AS s(ord, anchor_col)
 WHERE EXISTS (SELECT 1 FROM semantic_edge e
                WHERE e.id = ('26091601-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid)
ON CONFLICT DO NOTHING;


-- =====================================================================================
-- 第 4 节 · Flyway 基线上调 V443 -> V444
--   内网库不跑 Flyway，本行是为了将来万一接上 Quarkus 时不重放中间迁移
-- =====================================================================================

UPDATE flyway_schema_history
   SET version = '444', script = '<< Flyway Baseline >>'
 WHERE installed_rank = 1;


-- =====================================================================================
-- 第 5 节 · 自检（全部只读，可整段粘贴执行，期望值写在每条的注释里）
-- =====================================================================================

-- 5.1 【期望 11】11 个数据源各自的 PART_NAME 组恰为「1:物料（查名）,2:材质（查名）」
SELECT count(*) AS sources_two_branch_expect_11
  FROM (
    SELECT fn.dialect, fn.node_key,
           string_agg(e.fallback_order || ':' || tn.node_key, ',' ORDER BY e.fallback_order) AS sig
      FROM semantic_edge e
      JOIN semantic_node fn ON fn.id = e.from_node_id
      JOIN semantic_node tn ON tn.id = e.to_node_id
     WHERE e.edge_kind = 'LOOKUP' AND e.status = 'ACTIVE' AND e.coalesce_group = 'PART_NAME'
       AND fn.node_key IN ('INCOMING_FIXED_FEE','INCOMING_OTHER_FEE','INCOMING_RECOVERY',
                           'INCOMING_ANNUAL','SELF_PROCESS_FEE',
                           'INCOMING_OTHER_FIXED_FEE','INCOMING_PROCESS_FEE')
     GROUP BY 1, 2
  ) x
 WHERE x.sig = '1:MAT_NAME_LK,2:RECIPE_NAME_LK';

-- 5.2 【期望 11 / 11】新增的 11 条材质表边：各恰 1 条连接键，且 right_column = 'code'
SELECT count(DISTINCT e.id)                                      AS new_edges_expect_11,
       count(*) FILTER (WHERE k.seq = 0 AND k.right_column = 'code'
                          AND k.left_column IN ('input_material_no','incoming_material_no')) AS keys_ok_expect_11
  FROM semantic_edge e
  JOIN semantic_edge_key k ON k.edge_id = e.id
 WHERE e.id::text LIKE '26091601-0000-4000-8000-%';

-- 5.3 【期望 57 / 71】指向三个查名节点的 ACTIVE 边总数 / 连接键总数（V430 的 46 / 60 各 + 11）
--      ⚠️ 若该库的语义图曾在管理界面里改过查名边，本条可能不等，以 5.1 / 5.2 为准
SELECT (SELECT count(*) FROM semantic_edge e JOIN semantic_node t ON t.id = e.to_node_id
         WHERE t.node_key IN ('MAT_NAME_LK','MAT_PROD_LK','RECIPE_NAME_LK') AND e.status = 'ACTIVE') AS lookup_edges_expect_57,
       (SELECT count(*) FROM semantic_edge_key k JOIN semantic_edge e ON e.id = k.edge_id
          JOIN semantic_node t ON t.id = e.to_node_id
         WHERE t.node_key IN ('MAT_NAME_LK','MAT_PROD_LK','RECIPE_NAME_LK')) AS lookup_keys_expect_71;

-- 5.4 【期望 0】防误伤：报价侧物料表查名边的客户键仍在（5 个数据源各 2 条键，缺任一条即计入）
SELECT count(*) AS quote_mat_edges_missing_customer_key_expect_0
  FROM semantic_edge e
  JOIN semantic_node fn ON fn.id = e.from_node_id
  JOIN semantic_node tn ON tn.id = e.to_node_id
 WHERE fn.dialect = 'QUOTE' AND tn.node_key = 'MAT_NAME_LK' AND e.status = 'ACTIVE'
   AND fn.node_key IN ('INCOMING_FIXED_FEE','INCOMING_OTHER_FEE','INCOMING_RECOVERY',
                       'INCOMING_ANNUAL','SELF_PROCESS_FEE')
   AND (SELECT count(*) FROM semantic_edge_key k
         WHERE k.edge_id = e.id
           AND ((k.seq = 0 AND k.left_column = 'input_material_no' AND k.right_column = 'material_no')
             OR (k.seq = 1 AND k.left_column = 'customer_no'       AND k.right_column = 'customer_no'))) <> 2;

-- 5.5 【期望 444】Flyway 基线已上调
SELECT version AS flyway_baseline_expect_444
  FROM flyway_schema_history
 WHERE installed_rank = 1;
