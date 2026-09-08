-- =====================================================================================
--  task-260908 · B-1 / B-2 · AC-1~AC-7, AC-9, AC-24
--  新增 7 个「瘦查名节点」 + 46 条 LOOKUP 边（含边键）
--
--  目的（BL-0213 的 G-A / G-B 两个缺口）：让全部 42 个可选数据源都能在取数配置器里
--  选到一个属于它自己的「材料名」，报价 BOM 额外能选到「生产料号」。
--
--  🚫 本迁移【纯新增】：不 DROP、不 DELETE、不 UPDATE 任何既有行。
--  🚫 Java 源码零改动 —— 全部依赖既有机制：
--       SemanticCompiler.resolveLookup   多源 COALESCE（按 role 挑分支列）
--       SemanticCompiler.resolveColumn   同物理表直读（AC-5 / AC-5b 的同表退化）
--       SemanticCompiler.ensureLeftJoin  按 target.id 缓存别名
--       FieldTreeBuilder.syntheticLookupFields  查名字段【内联】进锚点分组
--
--  🚨 四条不能改的约束（每条都有具体后果，改了不会报错、只会静默出错）：
--   1. 每个查名节点【只声明一列】。syntheticLookupFields 会把代表节点的全部非 is_code 列
--      内联进锚点分组 ⇒ 多声明一列，用户面板里就多一个莫名其妙的字段。
--   2. roles 严格按下表填。pickColumnByRole 在 COALESCE 时按角色在兄弟节点里挑分支列，
--      取第一个命中的。MAT_NAME_LK / RECIPE_NAME_LK 必须都是 {PART_NAME}，
--      否则 AC-2 的 COALESCE(物料.material_name, 材质.symbol) 配不出来。
--      MAT_PROD_LK 必须是【空数组】—— 给它打 PART_NO 会被别的按角色挑列的逻辑误选。
--   3. is_code 一律 false。true 的列不会被内联（syntheticLookupFields 里 if (!col.isCode)），
--      字段就不可见了。
--   4. 🚫 不把这 7 个节点挂进 semantic_tab_view_node。挂了会触发 FieldTreeBuilder 的
--      nodesWithOwnGroup 分支 ⇒ 它们自成一个独立分组而不是内联 ⇒ AC-7 直接失败。
--
--  🔑 为什么 MAT_NAME_LK 与 MAT_PROD_LK 必须是【两个节点】而不是一个节点两条边：
--      报价 BOM 的材料名按【投入料号 input_material_no】查，生产料号按【销售料号 material_no】查
--      —— 两个不同的连接键。而 semantic_edge 的唯一约束是 (from_node_id, to_node_id, edge_kind)，
--      同一对节点同类型只能一条；即使绕过它，ensureLeftJoin 按 target.id 缓存别名，
--      第二条边的连接键也会被【静默吞掉】（不报错、值取错）。AC-3 就是钉这一条的。
--
--  幂等：所有 id 都是【确定性常量】（固定前缀 + 序号，不用 gen_random_uuid()），
--        全部 ON CONFLICT DO NOTHING ⇒ 本迁移可在多个 worktree 的 mvnw test 中安全重放。
-- =====================================================================================

-- ── 1. 7 个瘦查名节点 ──────────────────────────────────────────────────────────────
--    样板照抄现存的 QUOTE_MATERIAL_BRIDGE：node_kind='LOOKUP', scope='NONE', grain_columns='{}'。
--    fixed_predicate 一律 NULL —— 客户维度靠【边键】seq1 表达（见第 3 节），不靠固定谓词。
INSERT INTO semantic_node (
    id, node_key, display_name, short_name, node_kind, physical_table, scope,
    anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator,
    source_handler, dialect, note, created_at, updated_at, status)
VALUES
 ('26090801-0000-4000-8000-000000000001'::uuid, 'MAT_NAME_LK',    '物料（查名）',     '物料',
  'LOOKUP', 'ds_quote_material',       'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'QUOTE',
  'task-260908 B-1：报价侧材料名查名（瘦节点，只声明 material_name 一列）', now(), now(), 'ACTIVE'),
 ('26090801-0000-4000-8000-000000000002'::uuid, 'MAT_PROD_LK',    '物料（生产料号）', '物料',
  'LOOKUP', 'ds_quote_material',       'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'QUOTE',
  'task-260908 B-1：报价 BOM 的生产料号查名。与 MAT_NAME_LK 同表但【连接键不同】，必须独立成节点', now(), now(), 'ACTIVE'),
 ('26090801-0000-4000-8000-000000000003'::uuid, 'RECIPE_NAME_LK', '材质（查名）',     '材质',
  'LOOKUP', 'material_recipe',         'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'QUOTE',
  'task-260908 B-1：材质符号作材料名（COALESCE 第二分支）', now(), now(), 'ACTIVE'),
 ('26090801-0000-4000-8000-000000000004'::uuid, 'MAT_NAME_LK',    '物料（查名）',     '物料',
  'LOOKUP', 'ds_cost_basic_material',  'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'COST_BASIC',
  'task-260908 B-1：基础核价材料名查名', now(), now(), 'ACTIVE'),
 ('26090801-0000-4000-8000-000000000005'::uuid, 'RECIPE_NAME_LK', '材质（查名）',     '材质',
  'LOOKUP', 'material_recipe',         'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'COST_BASIC',
  'task-260908 B-1：基础核价材质查名', now(), now(), 'ACTIVE'),
 ('26090801-0000-4000-8000-000000000006'::uuid, 'MAT_NAME_LK',    '物料（查名）',     '物料',
  'LOOKUP', 'ds_cost_detail_material', 'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'COST_DETAIL',
  'task-260908 B-1：明细核价材料名查名', now(), now(), 'ACTIVE'),
 ('26090801-0000-4000-8000-000000000007'::uuid, 'RECIPE_NAME_LK', '材质（查名）',     '材质',
  'LOOKUP', 'material_recipe',         'NONE', NULL, '{}', NULL, NULL, NULL, NULL, 'COST_DETAIL',
  'task-260908 B-1：明细核价材质查名', now(), now(), 'ACTIVE')
ON CONFLICT DO NOTHING;

-- ── 2. 节点列：每个节点【只有一列】 ────────────────────────────────────────────────
--    material_recipe 同时有 name 与 symbol 两列；按 AC-2 原文取 symbol
--    （COALESCE(<物料表别名>.material_name, <材质表别名>.symbol)）。
INSERT INTO semantic_node_column (
    id, node_id, db_column, display_name, data_type, is_code, roles, sort_order,
    created_at, updated_at, status)
VALUES
 ('26090802-0000-4000-8000-000000000001'::uuid, '26090801-0000-4000-8000-000000000001'::uuid,
  'material_name', '材料名',   'TEXT', false, '{PART_NAME}', 0, now(), now(), 'ACTIVE'),
 ('26090802-0000-4000-8000-000000000002'::uuid, '26090801-0000-4000-8000-000000000002'::uuid,
  'production_no', '生产料号', 'TEXT', false, '{}',          0, now(), now(), 'ACTIVE'),
 ('26090802-0000-4000-8000-000000000003'::uuid, '26090801-0000-4000-8000-000000000003'::uuid,
  'symbol',        '材料名',   'TEXT', false, '{PART_NAME}', 0, now(), now(), 'ACTIVE'),
 ('26090802-0000-4000-8000-000000000004'::uuid, '26090801-0000-4000-8000-000000000004'::uuid,
  'material_name', '材料名',   'TEXT', false, '{PART_NAME}', 0, now(), now(), 'ACTIVE'),
 ('26090802-0000-4000-8000-000000000005'::uuid, '26090801-0000-4000-8000-000000000005'::uuid,
  'symbol',        '材料名',   'TEXT', false, '{PART_NAME}', 0, now(), now(), 'ACTIVE'),
 ('26090802-0000-4000-8000-000000000006'::uuid, '26090801-0000-4000-8000-000000000006'::uuid,
  'material_name', '材料名',   'TEXT', false, '{PART_NAME}', 0, now(), now(), 'ACTIVE'),
 ('26090802-0000-4000-8000-000000000007'::uuid, '26090801-0000-4000-8000-000000000007'::uuid,
  'symbol',        '材料名',   'TEXT', false, '{PART_NAME}', 0, now(), now(), 'ACTIVE')
ON CONFLICT DO NOTHING;

-- ── 3. 46 条边的规格表（单一事实来源） ────────────────────────────────────────────
--    边与边键都从这张表派生，避免两份清单各写一遍后互相漂移。
--    ON COMMIT DROP：Flyway 每个迁移跑在一个事务里，提交即消失，不留任何对象。
--
--    with_customer=true ⇒ 额外补一条 seq=1 的键 customer_no = customer_no（D-11）。
--    报价侧 14 张锚点表【实测全部】有 customer_no（2026-09-08 核对，无一例外）；
--    核价侧无客户维度，一律单键。
CREATE TEMP TABLE t260908_lookup_edge (
    ord            int     NOT NULL,
    dialect        text    NOT NULL,
    from_key       text    NOT NULL,
    to_key         text    NOT NULL,
    coalesce_group text,
    fallback_order int,
    left_col       text    NOT NULL,
    right_col      text    NOT NULL,
    with_customer  boolean NOT NULL
) ON COMMIT DROP;

INSERT INTO t260908_lookup_edge VALUES
-- ── QUOTE（14 个数据源 → 16 条边）────────────────────────────────────────────────
--    ⚠️ ord=3 是全部 46 条里【唯一一条连接键不等于锚点材料名料号列】的边：
--       材料名走 input_material_no（投入料号），生产料号走 material_no（销售料号，D-2）。
 (1 ,'QUOTE'      ,'MATERIAL_BOM'            ,'MAT_NAME_LK'   ,'PART_NAME',1   ,'input_material_no'   ,'material_no'  ,true ),
 (2 ,'QUOTE'      ,'MATERIAL_BOM'            ,'RECIPE_NAME_LK','PART_NAME',2   ,'input_material_no'   ,'code'         ,false),
 (3 ,'QUOTE'      ,'MATERIAL_BOM'            ,'MAT_PROD_LK'   ,NULL       ,NULL,'material_no'         ,'material_no'  ,true ),
 (4 ,'QUOTE'      ,'MATERIAL'                ,'MAT_NAME_LK'   ,NULL       ,NULL,'material_no'         ,'material_no'  ,true ),
 (5 ,'QUOTE'      ,'ELEMENT_BOM'             ,'RECIPE_NAME_LK',NULL       ,NULL,'material_part_no'    ,'code'         ,false),
 (6 ,'QUOTE'      ,'ANNUAL_DISCOUNT'         ,'MAT_NAME_LK'   ,NULL       ,NULL,'material_no'         ,'material_no'  ,true ),
 (7 ,'QUOTE'      ,'ASSEMBLY_FEE'            ,'MAT_NAME_LK'   ,NULL       ,NULL,'material_no'         ,'material_no'  ,true ),
 (8 ,'QUOTE'      ,'ASSEMBLY_FEE_ANNUAL'     ,'MAT_NAME_LK'   ,NULL       ,NULL,'material_no'         ,'material_no'  ,true ),
 (9 ,'QUOTE'      ,'FINISHED_OTHER_FEE'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'material_no'         ,'material_no'  ,true ),
 (10,'QUOTE'      ,'INCOMING_ANNUAL'         ,'MAT_NAME_LK'   ,NULL       ,NULL,'input_material_no'   ,'material_no'  ,true ),
 (11,'QUOTE'      ,'INCOMING_FIXED_FEE'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'input_material_no'   ,'material_no'  ,true ),
 (12,'QUOTE'      ,'INCOMING_OTHER_FEE'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'input_material_no'   ,'material_no'  ,true ),
 (13,'QUOTE'      ,'INCOMING_RECOVERY'       ,'MAT_NAME_LK'   ,NULL       ,NULL,'input_material_no'   ,'material_no'  ,true ),
 (14,'QUOTE'      ,'PLATING_FEE'             ,'MAT_NAME_LK'   ,NULL       ,NULL,'material_no'         ,'material_no'  ,true ),
 (15,'QUOTE'      ,'SELF_PROCESS_FEE'        ,'MAT_NAME_LK'   ,NULL       ,NULL,'input_material_no'   ,'material_no'  ,true ),
 (16,'QUOTE'      ,'SUB_COMPONENT_FEE'       ,'MAT_NAME_LK'   ,NULL       ,NULL,'sub_component_no'    ,'material_no'  ,true ),
-- ── COST_BASIC（10 个数据源 → 11 条边）──────────────────────────────────────────
 (17,'COST_BASIC' ,'MATERIAL_BOM'            ,'MAT_NAME_LK'   ,'PART_NAME',1   ,'component_no'        ,'production_no',false),
 (18,'COST_BASIC' ,'MATERIAL_BOM'            ,'RECIPE_NAME_LK','PART_NAME',2   ,'component_no'        ,'code'         ,false),
 (19,'COST_BASIC' ,'MATERIAL'                ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (20,'COST_BASIC' ,'ELEMENT_BOM'             ,'RECIPE_NAME_LK',NULL       ,NULL,'material_part_no'    ,'code'         ,false),
 (21,'COST_BASIC' ,'FINISHED_FIXED_FEE'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (22,'COST_BASIC' ,'FINISHED_RATIO_FEE'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (23,'COST_BASIC' ,'INCOMING_OTHER_FEE'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'incoming_material_no','production_no',false),
 (24,'COST_BASIC' ,'INCOMING_OTHER_FIXED_FEE','MAT_NAME_LK'   ,NULL       ,NULL,'incoming_material_no','production_no',false),
 (25,'COST_BASIC' ,'INCOMING_PROCESS_FEE'    ,'MAT_NAME_LK'   ,NULL       ,NULL,'incoming_material_no','production_no',false),
 (26,'COST_BASIC' ,'OUTSOURCED_PROCESS'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (27,'COST_BASIC' ,'PROCESS_ASSEMBLY_FEE'    ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
-- ── COST_DETAIL（18 个数据源 → 19 条边）─────────────────────────────────────────
 (28,'COST_DETAIL','MATERIAL_BOM'            ,'MAT_NAME_LK'   ,'PART_NAME',1   ,'component_no'        ,'production_no',false),
 (29,'COST_DETAIL','MATERIAL_BOM'            ,'RECIPE_NAME_LK','PART_NAME',2   ,'component_no'        ,'code'         ,false),
 (30,'COST_DETAIL','MATERIAL'                ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (31,'COST_DETAIL','ELEMENT_BOM'             ,'RECIPE_NAME_LK',NULL       ,NULL,'material_part_no'    ,'code'         ,false),
 (32,'COST_DETAIL','AUXILIARY_ENERGY'        ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (33,'COST_DETAIL','CAPACITY'                ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (34,'COST_DETAIL','CONSUMABLE'              ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (35,'COST_DETAIL','DEPRECIATION'            ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (36,'COST_DETAIL','FINISHED_FIXED_FEE'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (37,'COST_DETAIL','FINISHED_RATIO_FEE'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (38,'COST_DETAIL','INCOMING_OTHER_FEE'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'incoming_material_no','production_no',false),
 (39,'COST_DETAIL','INCOMING_OTHER_FIXED_FEE','MAT_NAME_LK'   ,NULL       ,NULL,'incoming_material_no','production_no',false),
 (40,'COST_DETAIL','INCOMING_PROCESS_FEE'    ,'MAT_NAME_LK'   ,NULL       ,NULL,'incoming_material_no','production_no',false),
 (41,'COST_DETAIL','OUTSOURCED_PROCESS'      ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (42,'COST_DETAIL','PACKAGING'               ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (43,'COST_DETAIL','PLATING_COST'            ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (44,'COST_DETAIL','PROCESS_ASSEMBLY_FEE'    ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (45,'COST_DETAIL','PRODUCTION_ENERGY'       ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false),
 (46,'COST_DETAIL','TOOLING'                 ,'MAT_NAME_LK'   ,NULL       ,NULL,'production_no'       ,'production_no',false);

-- 🚨 自检：规格表必须恰好 46 行、序号 1..46 不重不漏。写错一行的后果是某个数据源
--    永远选不到材料名，而那在界面上跟"这个源本来就没有"长得一模一样，不会报错。
DO $$
DECLARE n int; mx int; d text;
BEGIN
    SELECT count(*), max(ord) INTO n, mx FROM t260908_lookup_edge;
    IF n <> 46 OR mx <> 46 OR n <> (SELECT count(DISTINCT ord) FROM t260908_lookup_edge) THEN
        RAISE EXCEPTION 'V430 规格表异常: rows=%, max_ord=%', n, mx;
    END IF;
    SELECT string_agg(dialect || '=' || c, ' ' ORDER BY dialect) INTO d
      FROM (SELECT dialect, count(*) c FROM t260908_lookup_edge GROUP BY 1) x;
    IF d <> 'COST_BASIC=11 COST_DETAIL=19 QUOTE=16' THEN
        RAISE EXCEPTION 'V430 方言分布异常: %', d;
    END IF;
END $$;

-- ── 4. 46 条 LOOKUP 边 ────────────────────────────────────────────────────────────
INSERT INTO semantic_edge (
    id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order,
    coalesce_group, assert_status, assert_sample_rows, note,
    created_at, updated_at, status)
SELECT ('26090803-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       fn.id, tn.id, 'LOOKUP', 'MANY_TO_ONE',
       s.fallback_order, s.coalesce_group,
       'NA',   -- assert_status 是 NOT NULL，与既有查名边取值一致
       NULL,
       'task-260908 B-2：' || s.dialect || ' ' || s.from_key || ' → ' || s.to_key,
       now(), now(), 'ACTIVE'
  FROM t260908_lookup_edge s
  JOIN semantic_node fn ON fn.node_key = s.from_key AND fn.dialect = s.dialect
  JOIN semantic_node tn ON tn.node_key = s.to_key   AND tn.dialect = s.dialect
ON CONFLICT DO NOTHING;

-- ── 5. 边键 seq=0（料号键，46 条）─────────────────────────────────────────────────
INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
SELECT ('26090804-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       ('26090803-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       s.left_col, s.right_col, 0
  FROM t260908_lookup_edge s
 WHERE EXISTS (SELECT 1 FROM semantic_edge e
                WHERE e.id = ('26090803-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid)
ON CONFLICT DO NOTHING;

-- ── 6. 边键 seq=1（客户维度键，仅报价侧 14 条）────────────────────────────────────
--    D-11：报价侧 ds_quote_* 主表与 ds_quote_material 都按 (料号, 客户) 定位；
--    少了这条键会跨客户串号（RECORD.md 有同类历史事故）。核价侧无客户维度，不补。
INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
SELECT ('26090805-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       ('26090803-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid,
       'customer_no', 'customer_no', 1
  FROM t260908_lookup_edge s
 WHERE s.with_customer
   AND EXISTS (SELECT 1 FROM semantic_edge e
                WHERE e.id = ('26090803-0000-4000-8000-' || lpad(s.ord::text, 12, '0'))::uuid)
ON CONFLICT DO NOTHING;

-- ── 7. 落库后自检（写在迁移里，让错误在启动时就炸，而不是等用户点开配置器才发现）──
DO $$
DECLARE nn int; ne int; nk int;
BEGIN
    SELECT count(*) INTO nn FROM semantic_node
     WHERE node_key IN ('MAT_NAME_LK','MAT_PROD_LK','RECIPE_NAME_LK') AND status = 'ACTIVE';
    SELECT count(*) INTO ne FROM semantic_edge e
      JOIN semantic_node tn ON tn.id = e.to_node_id
     WHERE tn.node_key IN ('MAT_NAME_LK','MAT_PROD_LK','RECIPE_NAME_LK') AND e.status = 'ACTIVE';
    SELECT count(*) INTO nk FROM semantic_edge_key k
      JOIN semantic_edge e ON e.id = k.edge_id
      JOIN semantic_node tn ON tn.id = e.to_node_id
     WHERE tn.node_key IN ('MAT_NAME_LK','MAT_PROD_LK','RECIPE_NAME_LK');
    IF nn <> 7 OR ne <> 46 OR nk <> 60 THEN
        RAISE EXCEPTION 'V430 落库自检失败: nodes=% (期望7), edges=% (期望46), keys=% (期望60)', nn, ne, nk;
    END IF;
END $$;
