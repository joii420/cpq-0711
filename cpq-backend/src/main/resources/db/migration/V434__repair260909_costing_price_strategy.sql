-- =====================================================================================
--  repair-260909 · B-2 / B-3 · AC-P1 / AC-P2 / AC-B1 / AC-B2 / AC-R3
--  核价两方言接入价格策略：两张元素BOM全版本视图补销售料号桥接列 + 语义图加节点/边/边键/列
--
--  🚨 落库有【严格的两阶段顺序】，反了会让共享库上所有实例起不来（用户 2026-09-09 裁决）：
--     阶段一：CostAllVersionViewSelfCheck 的具名派生列登记表（本次同批 Java 改动）先进 master。
--             该登记表【只允许不要求】—— 列还没建出来时它是纯无害的，故可安全先行。
--     阶段二：本迁移才允许被 migrate-at-start 应用，随即提交进 master。
--     ⇒ 反过来（迁移先落库、守卫还没进 master）会出现「共享库有列、master 没登记」的窗口，
--       期间任何实例重启都会 IllegalStateException 起不来，而【已应用的迁移不能改不能删】。
-- =====================================================================================

-- ── 1. 两张全版本视图补 sales_material_no（追加在末尾，主表零改动）──────────────────
--    🚨 必须 LEFT JOIN LATERAL + LIMIT 1：material_master 存在 1 生产料号 → 2 销售料号
--       （TEST0813-P01-PROD，22 个里 1 个）。裸 LEFT JOIN 会让元素BOM行本身翻倍。
--    ✅ CREATE OR REPLACE 直接替换，不需要 DROP（2026-09-09 事务内实测，新列追加末尾时成功）

CREATE OR REPLACE VIEW v_ds_cost_basic_element_bom_all AS
 SELECT e.id, e.production_no, e.material_part_no, e.item_seq, e.element_code,
        e.content_pct, e.loss_rate, e.version_no, e.row_fingerprint, e.source,
        e.created_at, e.created_by, e.updated_at, e.updated_by,
        true AS is_current,
        mm.material_no AS sales_material_no
   FROM ds_cost_basic_element_bom e
   LEFT JOIN LATERAL (SELECT m.material_no FROM material_master m
                       WHERE m.production_no = e.production_no
                       ORDER BY m.material_no LIMIT 1) mm ON TRUE
UNION ALL
 SELECT h.id, h.production_no, h.material_part_no, h.item_seq, h.element_code,
        h.content_pct, h.loss_rate, h.version_no, h.row_fingerprint, h.source,
        h.created_at, h.created_by, h.updated_at, h.updated_by,
        false AS is_current,
        mm.material_no AS sales_material_no
   FROM ds_cost_basic_element_bom_history h
   LEFT JOIN LATERAL (SELECT m.material_no FROM material_master m
                       WHERE m.production_no = h.production_no
                       ORDER BY m.material_no LIMIT 1) mm ON TRUE;

CREATE OR REPLACE VIEW v_ds_cost_detail_element_bom_all AS
 SELECT e.id, e.production_no, e.material_part_no, e.item_seq, e.element_code,
        e.content_pct, e.loss_rate, e.version_no, e.row_fingerprint, e.source,
        e.created_at, e.created_by, e.updated_at, e.updated_by,
        true AS is_current,
        mm.material_no AS sales_material_no
   FROM ds_cost_detail_element_bom e
   LEFT JOIN LATERAL (SELECT m.material_no FROM material_master m
                       WHERE m.production_no = e.production_no
                       ORDER BY m.material_no LIMIT 1) mm ON TRUE
UNION ALL
 SELECT h.id, h.production_no, h.material_part_no, h.item_seq, h.element_code,
        h.content_pct, h.loss_rate, h.version_no, h.row_fingerprint, h.source,
        h.created_at, h.created_by, h.updated_at, h.updated_by,
        false AS is_current,
        mm.material_no AS sales_material_no
   FROM ds_cost_detail_element_bom_history h
   LEFT JOIN LATERAL (SELECT m.material_no FROM material_master m
                       WHERE m.production_no = h.production_no
                       ORDER BY m.material_no LIMIT 1) mm ON TRUE;

-- ── 2. 两个 FUNCTION 节点（func_signature 与 QUOTE 侧逐字相同）────────────────────
--    🚫 不挂 semantic_tab_view_node —— PRICE 组由 FieldTreeBuilder 末尾专用块产出，
--       挂了会走通用循环再出一组 ⇒ 复现 task-260904 B-23 的「两个价格策略组」（AC-P1 钉 1 个）。
INSERT INTO semantic_node (
    id, node_key, display_name, short_name, node_kind, physical_table, scope,
    anchor_expr, grain_columns, fixed_predicate, func_signature, discriminator,
    source_handler, dialect, note, created_at, updated_at, status)
VALUES
 ('26090901-0000-4000-8000-000000000001'::uuid, 'FUNC_ELEMENT_PRICE',
  '价格策略 f_material_element_price', '价格策略', 'FUNCTION', NULL, 'NONE',
  NULL, '{}', NULL, 'f_material_element_price(:customerCode, :priceBaseDate)', NULL, NULL,
  'COST_BASIC',
  'repair-260909：别名固定为 cep。按 A0-1（用户 2026-09-09 裁决）核价与报价必须对同一 (客户,料号,元素) 给出同一个数 ⇒ 走与报价侧完全相同的函数与双键；销售料号由 v_ds_cost_basic_element_bom_all.sales_material_no 桥接（LATERAL LIMIT 1）',
  now(), now(), 'ACTIVE'),
 ('26090901-0000-4000-8000-000000000002'::uuid, 'FUNC_ELEMENT_PRICE',
  '价格策略 f_material_element_price', '价格策略', 'FUNCTION', NULL, 'NONE',
  NULL, '{}', NULL, 'f_material_element_price(:customerCode, :priceBaseDate)', NULL, NULL,
  'COST_DETAIL',
  'repair-260909：同 COST_BASIC，桥接列在 v_ds_cost_detail_element_bom_all.sales_material_no',
  now(), now(), 'ACTIVE')
ON CONFLICT DO NOTHING;

-- ── 3. 节点列：与 QUOTE 侧逐字一致 —— 实测只有 2 列（unit_price / currency）──────────
--    📌 立项文档初稿写的「单价/币种/价格单位」是想当然，现网 QUOTE 侧没有 price_unit。
INSERT INTO semantic_node_column (
    id, node_id, db_column, display_name, data_type, is_code, roles, sort_order,
    created_at, updated_at, status)
VALUES
 ('26090902-0000-4000-8000-000000000001'::uuid, '26090901-0000-4000-8000-000000000001'::uuid,
  'unit_price', '元素单价', 'MONEY', false, '{}', 0, now(), now(), 'ACTIVE'),
 ('26090902-0000-4000-8000-000000000002'::uuid, '26090901-0000-4000-8000-000000000001'::uuid,
  'currency',   '货币',     'TEXT',  false, '{}', 1, now(), now(), 'ACTIVE'),
 ('26090902-0000-4000-8000-000000000003'::uuid, '26090901-0000-4000-8000-000000000002'::uuid,
  'unit_price', '元素单价', 'MONEY', false, '{}', 0, now(), now(), 'ACTIVE'),
 ('26090902-0000-4000-8000-000000000004'::uuid, '26090901-0000-4000-8000-000000000002'::uuid,
  'currency',   '货币',     'TEXT',  false, '{}', 1, now(), now(), 'ACTIVE')
ON CONFLICT DO NOTHING;

-- ── 4. 锚点上登记桥接列 sales_material_no ─────────────────────────────────────────
--    🚫 roles 必须留空：给 PART_NO 会被 FieldTreeBuilder 的「材料名插到最后一个 PART_NO 之后」
--       判定吃进去，静默改掉材料名位置（repair-260908 刚定的口径）。
INSERT INTO semantic_node_column (
    id, node_id, db_column, display_name, data_type, is_code, roles, sort_order,
    created_at, updated_at, status)
SELECT ('26090903-0000-4000-8000-00000000000' || (row_number() OVER (ORDER BY n.dialect))::text)::uuid,
       n.id, 'sales_material_no', '销售料号', 'TEXT', false, '{}', 6, now(), now(), 'ACTIVE'
  FROM semantic_node n
 WHERE n.node_key = 'ELEMENT_BOM' AND n.dialect IN ('COST_BASIC','COST_DETAIL')
ON CONFLICT DO NOTHING;

-- ── 5. 两条 PRICE 边 ──────────────────────────────────────────────────────────────
INSERT INTO semantic_edge (
    id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order,
    coalesce_group, assert_status, assert_sample_rows, note,
    created_at, updated_at, status)
SELECT ('26090904-0000-4000-8000-00000000000' || (row_number() OVER (ORDER BY fn.dialect))::text)::uuid,
       fn.id, tn.id, 'PRICE', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL,
       'repair-260909：' || fn.dialect || ' ELEMENT_BOM → FUNC_ELEMENT_PRICE（双键：element_code + sales_material_no）',
       now(), now(), 'ACTIVE'
  FROM semantic_node fn
  JOIN semantic_node tn ON tn.node_key = 'FUNC_ELEMENT_PRICE' AND tn.dialect = fn.dialect
 WHERE fn.node_key = 'ELEMENT_BOM' AND fn.dialect IN ('COST_BASIC','COST_DETAIL')
ON CONFLICT DO NOTHING;

-- ── 6. 四条边键（seq0 元素码；seq1 桥接后的销售料号 → 函数的 material_no）───────────
--    ⚠️ seq1 的 left_column 只有在 SemanticCompiler B-1 改用 leftColumn 之后才生效；
--       不改 B-1 时会静默生成 cep.material_no = <锚点>.production_no（语法合法、恒 0 命中）。
INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
SELECT ('26090905-0000-4000-8000-' || lpad((row_number() OVER (ORDER BY fn.dialect, k.seq))::text, 12, '0'))::uuid,
       e.id, k.left_column, k.right_column, k.seq
  FROM semantic_edge e
  JOIN semantic_node fn ON fn.id = e.from_node_id
  JOIN semantic_node tn ON tn.id = e.to_node_id
  CROSS JOIN (VALUES ('element_code','element_code',0),
                     ('sales_material_no','material_no',1)) AS k(left_column, right_column, seq)
 WHERE e.edge_kind = 'PRICE' AND fn.node_key = 'ELEMENT_BOM'
   AND tn.node_key = 'FUNC_ELEMENT_PRICE' AND fn.dialect IN ('COST_BASIC','COST_DETAIL')
ON CONFLICT DO NOTHING;

-- ── 7. 更正 QUOTE 侧节点的 note（原文在本次之后会主动误导下一个人）──────────────────
UPDATE semantic_node
   SET note = '别名固定为 cep（AC-1 铁律）；不是 f_customer_element_price。'
              || 'repair-260909 起三方言都挂：原注「核价侧语义对不上，不硬接」说过头了——'
              || '真实卡点只是【连接键不在核价视图里】，已由 v_ds_cost_*_element_bom_all.sales_material_no 桥接解决。'
              || '用户 2026-09-09 裁决 A0-1：核价与报价对同一 (客户,料号,元素) 必须给出同一个数。',
       updated_at = now()
 WHERE node_key = 'FUNC_ELEMENT_PRICE' AND dialect = 'QUOTE';

-- ── 8. 落库自检 ───────────────────────────────────────────────────────────────────
DO $$
DECLARE nn int; ne int; nk int; nc int; nb int;
BEGIN
    SELECT count(*) INTO nn FROM semantic_node
     WHERE node_key='FUNC_ELEMENT_PRICE' AND dialect IN ('COST_BASIC','COST_DETAIL') AND status='ACTIVE';
    SELECT count(*) INTO ne FROM semantic_edge e
      JOIN semantic_node fn ON fn.id=e.from_node_id
     WHERE e.edge_kind='PRICE' AND fn.dialect IN ('COST_BASIC','COST_DETAIL');
    SELECT count(*) INTO nk FROM semantic_edge_key k
      JOIN semantic_edge e ON e.id=k.edge_id
      JOIN semantic_node fn ON fn.id=e.from_node_id
     WHERE e.edge_kind='PRICE' AND fn.dialect IN ('COST_BASIC','COST_DETAIL');
    SELECT count(*) INTO nc FROM semantic_node_column c
      JOIN semantic_node n ON n.id=c.node_id
     WHERE n.node_key='FUNC_ELEMENT_PRICE' AND n.dialect IN ('COST_BASIC','COST_DETAIL');
    SELECT count(*) INTO nb FROM semantic_node_column c
      JOIN semantic_node n ON n.id=c.node_id
     WHERE n.node_key='ELEMENT_BOM' AND n.dialect IN ('COST_BASIC','COST_DETAIL')
       AND c.db_column='sales_material_no';
    IF nn<>2 OR ne<>2 OR nk<>4 OR nc<>4 OR nb<>2 THEN
        RAISE EXCEPTION 'V434 落库自检失败: nodes=%(期望2) edges=%(期望2) keys=%(期望4) funcCols=%(期望4) bridgeCols=%(期望2)',
              nn, ne, nk, nc, nb;
    END IF;
END $$;

-- =====================================================================================
-- ✅ 已处置的阻塞（本节记录处置，不是待办）
--
--   com.cpq.builder.selfcheck.CostAllVersionViewSelfCheck（@Observes StartupEvent）
--   要求每张 v_<主表>_all 的列集合 == 主表列集合 ∪ {is_current}，【双向】。
--   第 1 节给视图加的 sales_material_no 在主表里不存在 ⇒ 原本会命中
--   「视图多出未在主表出现的列」⇒ IllegalStateException ⇒ 应用启动失败。
--
--   实测（2026-09-09，事务内 CREATE OR REPLACE 后跑该守卫的等价查询再 ROLLBACK）：
--     改前 违例列数 = 0 ；改后 = 1（v_ds_cost_basic_element_bom_all / sales_material_no）
--
--   处置（用户裁决「走甲」）：该守卫的 DERIVED_COLUMN 单常量已升级为
--   REGISTERED_DERIVED_COLUMNS ——【按视图】登记的具名派生列表，本迁移的两张视图
--   各登记 sales_material_no 一列。登记制，未登记的多余列照旧当场打挂。
--   🚫 该 Java 改动必须先于本迁移进 master，见文件头部的两阶段顺序。
-- =====================================================================================