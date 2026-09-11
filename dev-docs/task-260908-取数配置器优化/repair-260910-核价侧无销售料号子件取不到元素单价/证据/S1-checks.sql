-- ============================================================================
-- S-1 配置与产物片 · 断言脚本（只读，零写入）
-- repair-260910 核价侧无销售料号子件取不到元素单价
-- 认领 AC：AC-2 / AC-8 / AC-9
-- 库：10.177.152.12:5432/cpq_db_0910   跑法：
--   PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0910 -f S1-checks.sql
--
-- 🚫 本脚本不含任何 INSERT/UPDATE/DELETE/DDL。
-- 🚫 本脚本不含任何全局计数断言（只对钉死的对象 id 做存在性/针对性断言）。
-- ============================================================================

\pset border 2
\timing off

-- ---------------------------------------------------------------------------
-- 钉死的基线常量（2026-09-10 19:5x PDT / DB 2026-09-11 02:55 UTC 实取）
-- ⚠️ 这些值是「改动前」的事实，不是断言目标；改动后不许回来改它们。
-- ---------------------------------------------------------------------------
\set BASE_COST_COMPONENT_ID  '''54001a3b-fd2e-4580-b90e-4bfa157ea77c'''
\set BASE_QUOTE_COMPONENT_ID '''813b4ead-d89a-416c-b5a8-fdbbe4e6e183'''
\set NODE_COST_BASIC         '''26090901-0000-4000-8000-000000000001'''
\set NODE_COST_DETAIL        '''26090901-0000-4000-8000-000000000002'''
\set NODE_QUOTE              '''ddc7fafa-0fd3-5c47-a86c-e3f8b86b0f76'''
\set EDGE_COST_BASIC         '''26090904-0000-4000-8000-000000000001'''
\set EDGE_COST_DETAIL        '''26090904-0000-4000-8000-000000000002'''
\set EDGE_QUOTE              '''5b1bfc30-551b-511c-bb2b-42becb609a06'''
\set TPL_ZHENGTAI            '''62dd00cb-2fcc-47f5-a055-9801ab11461e'''
\set TPL_ZHENGTAI_SERIES     '''83faa13e-efed-4d22-85e1-1e9f8de0e8c8'''
\set TPL_HEJIA               '''28c595d3-55ef-471c-8f63-7c3efdb901c2'''
\set TPL_HEJIA_SERIES        '''1f76134e-9c3b-4aed-8db3-7809e2dc2111'''
-- 正泰模板 改动前基线
\set ZT_BASE_VERSION         '''v1.0'''
\set ZT_BASE_STATUS          '''PUBLISHED'''
\set ZT_BASE_UPDATED_AT      '''2026-09-11 01:01:02.608232'''
\set ZT_BASE_SNAP_MD5        '''2825b27f1749811b676caea2e5e22e42'''
\set ZT_BASE_SNAP_LEN        7941
-- 核价模板 改动前基线（T1.3 的前置守卫用：这个必须「变了」）
\set HJ_BASE_VERSION         '''v1.0'''
\set HJ_BASE_UPDATED_AT      '''2026-09-11 01:01:33.754498'''
\set HJ_BASE_SNAP_MD5        '''14b8a92d209a3ae753b8fbbd69678a80'''
-- 报价侧编译产物 改动前基线（对照组，判定归 S-2 的 AC-4）
\set QUOTE_BASE_TPL_MD5      '''ea79ee6381eebb3717a9bc5a0a9ef644'''
-- 核价侧编译产物 改动前基线（必须「变了」）
\set COST_BASE_TPL_MD5       '''5ada59e3a232887cf8b3229b08cec7aa'''
-- 期望的新函数签名
\set NEW_FUNC                '''f_customer_element_price(:customerCode, :priceBaseDate)'''
\set OLD_FUNC_NAME           '''f_material_element_price'''
\set NEW_FUNC_NAME           '''f_customer_element_price'''

\echo '################ T1.0 · 前置守卫：本片要断言的对象是否都存在 ################'
-- 🚨 任何一行 present=false ⇒ 后续断言会在空集上空跑 ⇒ 整片结论作废，不许判通过。
SELECT '核价侧组件 54001a3b 的 component_sql_view 行' AS obj,
       count(*) = 1 AS present, count(*) AS n
  FROM component_sql_view WHERE component_id = :BASE_COST_COMPONENT_ID
UNION ALL SELECT '报价侧组件 813b4ead 的 component_sql_view 行', count(*) = 1, count(*)
  FROM component_sql_view WHERE component_id = :BASE_QUOTE_COMPONENT_ID
UNION ALL SELECT 'semantic_node COST_BASIC  FUNC_ELEMENT_PRICE', count(*) = 1, count(*)
  FROM semantic_node WHERE id = :NODE_COST_BASIC
UNION ALL SELECT 'semantic_node COST_DETAIL FUNC_ELEMENT_PRICE', count(*) = 1, count(*)
  FROM semantic_node WHERE id = :NODE_COST_DETAIL
UNION ALL SELECT 'semantic_node QUOTE       FUNC_ELEMENT_PRICE', count(*) = 1, count(*)
  FROM semantic_node WHERE id = :NODE_QUOTE
UNION ALL SELECT 'semantic_edge COST_BASIC  PRICE', count(*) = 1, count(*)
  FROM semantic_edge WHERE id = :EDGE_COST_BASIC
UNION ALL SELECT 'semantic_edge COST_DETAIL PRICE', count(*) = 1, count(*)
  FROM semantic_edge WHERE id = :EDGE_COST_DETAIL
UNION ALL SELECT 'semantic_edge QUOTE       PRICE', count(*) = 1, count(*)
  FROM semantic_edge WHERE id = :EDGE_QUOTE
UNION ALL SELECT 'template 正泰模板 v1.0 行', count(*) = 1, count(*)
  FROM template WHERE id = :TPL_ZHENGTAI
UNION ALL SELECT 'template 核价模板 v1.0 行', count(*) = 1, count(*)
  FROM template WHERE id = :TPL_HEJIA;

\echo ''
\echo '################ T1.1 · AC-2 编译产物是单键且换了函数（核价侧） ################'
-- AC-2 原文四条：① 含 f_customer_element_price(:customerCode, :priceBaseDate)
--                ② 含 cep.element_code = <锚点别名>.element_code
--                ③ 不含 cep.material_no
--                ④ 不含 f_material_element_price
-- 组件清单「钉死在基线」，不在改动后重新推导 —— 否则价格 JOIN 整段消失时清单会变空、循环 0 次、假绿。
WITH pinned(component_id) AS (VALUES (:BASE_COST_COMPONENT_ID::uuid)),
v AS (
  SELECT p.component_id, csv.sql_view_name, csv.sql_template,
         -- 锚点别名从 FROM 子句实取，不写死（重编译可能换别名）
         substring(csv.sql_template from 'FROM[[:space:]]+[^[:space:]]+[[:space:]]+([A-Za-z_][A-Za-z0-9_]*)') AS anchor_alias
    FROM pinned p LEFT JOIN component_sql_view csv ON csv.component_id = p.component_id
)
SELECT sql_view_name,
       (sql_template IS NOT NULL)                                       AS "守卫:产物非空",
       (anchor_alias IS NOT NULL)                                       AS "守卫:锚点别名解析成功",
       anchor_alias                                                     AS "锚点别名",
       coalesce(position(:NEW_FUNC in sql_template) > 0, false)         AS "AC2_1含新函数签名",
       coalesce(sql_template ~ ('cep\.element_code[[:space:]]*=[[:space:]]*' || anchor_alias || '\.element_code'), false)
                                                                        AS "AC2_2含element_code单键",
       coalesce(position('cep.material_no' in sql_template) = 0, false) AS "AC2_3不含cep_material_no",
       coalesce(position(:OLD_FUNC_NAME in sql_template) = 0, false)    AS "AC2_4不含旧函数",
       -- api.md 的隐含契约（非 AC-2 原文，单独列，红了要报但归因另说）
       position('AS "元素单价"' in sql_template) > 0                    AS "契约:输出别名元素单价仍在",
       position(:NEW_FUNC_NAME || '(:customerCode, :priceBaseDate) cep' in sql_template) > 0
                                                                        AS "契约:JOIN别名仍为cep",
       md5(sql_template) <> :COST_BASE_TPL_MD5                          AS "守卫:产物确已变更",
       md5(sql_template) AS tpl_md5
  FROM v;

\echo '--- T1.1 附：核价侧产物全文（人工复核用） ---'
SELECT sql_template FROM component_sql_view WHERE component_id = :BASE_COST_COMPONENT_ID \gset
\echo :sql_template

\echo ''
\echo '################ T1.2 · 对照组：报价侧编译产物必须仍是旧函数 + 双键 ################'
-- 🚦 非本片判定项（AC-4 归 S-2）。作用有二：
--   ① 灵敏度对照 —— 同一套断言表达式作用于报价侧应当给出「相反」结果，全都相同说明脚本没在读真数据；
--   ② 抓「全局批量替换把报价侧一起改了」。
SELECT sql_view_name,
       position(:OLD_FUNC_NAME in sql_template) > 0 AS "报价侧仍含旧函数(期望t)",
       position('cep.material_no' in sql_template) > 0 AS "报价侧仍含双键(期望t)",
       position(:NEW_FUNC_NAME in sql_template) = 0 AS "报价侧不含新函数(期望t)",
       md5(sql_template) = :QUOTE_BASE_TPL_MD5 AS "报价侧产物逐字节不变(期望t)"
  FROM component_sql_view WHERE component_id = :BASE_QUOTE_COMPONENT_ID;

\echo ''
\echo '################ T1.3 · AC-8 正泰模板未被误升版 ################'
-- 🚨 前置守卫先判：核价模板必须「确已升版」。核价模板也没动 ⇒ B-3 根本没执行
--    ⇒ AC-8 的「没变」是空跑出来的，判「不成立·前置未满足」，不许判通过。
-- ⚠️ 2026-09-10 实测教训：updated_at 会被 realign 类操作单独 bump，而 sql_views_snapshot
--    纹丝不动（BL-0223 的症状）。所以守卫「不许只看 updated_at」，必须看快照内容真的换了函数。
SELECT '前置守卫·核价模板确已升版并重新冻结' AS item,
       (   (coalesce(t.sql_views_snapshot::text,'') LIKE '%' || :NEW_FUNC_NAME || '%')
       AND (coalesce(t.sql_views_snapshot::text,'') NOT LIKE '%cep.material_no%')
       AND (s.series_rows > 1 OR t.version <> :HJ_BASE_VERSION)
       ) AS pass,
       t.version, to_char(t.updated_at,'YYYY-MM-DD HH24:MI:SS.US') AS updated_at, s.series_rows, s.series_detail,
       md5(coalesce(t.sql_views_snapshot::text,'<NULL>')) AS snap_md5,
       (md5(coalesce(t.sql_views_snapshot::text,'<NULL>')) <> :HJ_BASE_SNAP_MD5) AS "快照md5已变",
       (coalesce(t.sql_views_snapshot::text,'') LIKE '%' || :NEW_FUNC_NAME || '%') AS "快照含新函数(期望t)",
       (coalesce(t.sql_views_snapshot::text,'') LIKE '%cep.material_no%') AS "快照仍含双键(期望f)"
  -- 🚨 不锁死行 id：new-draft + publish 会**新插一行**，老 v1.0 行的快照永远是旧的。
  --    锁 id 会让守卫在「已正确升版」时反而判 false。取该 series 最新的 PUBLISHED 行。
  FROM (SELECT * FROM template
         WHERE template_series_id = :TPL_HEJIA_SERIES AND status = 'PUBLISHED'
         ORDER BY published_at DESC NULLS LAST, version DESC LIMIT 1) t
  CROSS JOIN LATERAL (SELECT count(*) AS series_rows,
                             string_agg(version || '/' || status, ',' ORDER BY version) AS series_detail
                        FROM template x WHERE x.template_series_id = :TPL_HEJIA_SERIES) s;

-- AC-8 判定本体（三条原文断言）
-- ⚠️ updated_at 是 timestamptz，`::text` 会带 `+00` 后缀，与裸字符串基线永不相等
--    （2026-09-10 首跑即踩到，表现为「值明明一样却判 false」的假红）。统一走 to_char。
SELECT 'AC-8 正泰模板' AS item, name,
       version = :ZT_BASE_VERSION                                        AS "AC8_version仍v1_0",
       to_char(updated_at,'YYYY-MM-DD HH24:MI:SS.US') = :ZT_BASE_UPDATED_AT
                                                                         AS "AC8_updated_at未变",
       status = :ZT_BASE_STATUS                                          AS "AC8_status仍PUBLISHED",
       version, to_char(updated_at,'YYYY-MM-DD HH24:MI:SS.US') AS updated_at, status
  FROM template WHERE id = :TPL_ZHENGTAI;

-- AC-8 补强：防「原 v1.0 行原封不动，但新插了一行 v1.1」这种「行没变但模板被升版了」
SELECT 'AC-8 补强·正泰 series 仍只有 1 行且仍是 v1.0' AS item,
       count(*) = 1 AND bool_and(version = :ZT_BASE_VERSION) AS pass,
       count(*) AS rows_in_series,
       string_agg(version || '/' || status, ',' ORDER BY version) AS detail
  FROM template WHERE template_series_id = :TPL_ZHENGTAI_SERIES;

-- 供 S-2 的 AC-4 交叉核对（本片只记录，不判定）
SELECT '记录(归 AC-4/S-2)·正泰 sql_views_snapshot' AS item,
       md5(coalesce(sql_views_snapshot::text,'<NULL>')) = :ZT_BASE_SNAP_MD5 AS same_as_baseline,
       md5(coalesce(sql_views_snapshot::text,'<NULL>')) AS snap_md5,
       length(coalesce(sql_views_snapshot::text,'')) AS snap_len
  FROM template WHERE id = :TPL_ZHENGTAI;

\echo ''
\echo '################ T1.4 · AC-9 COST_DETAIL 方言结构断言 ################'
-- ⚠️ COST_DETAIL 元素BOM 实测 0 行 ⇒ 只做结构断言。
-- 结论一律记「结构通过 · 数据面未覆盖」，🚫 不许记「通过」。
SELECT 'AC-9 节点' AS item, id::text, node_key, dialect,
       func_signature = :NEW_FUNC AS "AC9_func_signature已改为新函数",
       func_signature,
       -- ⑤ B-1 还写了 display_name 同步改；AC-9 原文未含此条 ⇒ 只记录不判定
       display_name
  FROM semantic_node WHERE id = :NODE_COST_DETAIL;

SELECT 'AC-9 边键' AS item,
       count(*) = 1 AS "AC9_PRICE边恰好1条边键",
       count(*) AS key_count,
       bool_and(seq = 0 AND left_column = 'element_code' AND right_column = 'element_code')
                AS "AC9_保留的是element_code单键",
       string_agg(seq || ':' || left_column || '->' || right_column, ' | ' ORDER BY seq) AS detail
  FROM semantic_edge_key WHERE edge_id = :EDGE_COST_DETAIL;

\echo '--- T1.4 附：COST_DETAIL 数据面（用于在报告里证明「未覆盖」而非「已验证」） ---'
SELECT 'v_ds_cost_detail_element_bom_all' AS obj,
       count(*) AS all_rows, count(*) FILTER (WHERE is_current) AS current_rows
  FROM v_ds_cost_detail_element_bom_all;

\echo ''
\echo '################ T1.5 · 对照组：COST_BASIC 语义图同样改到位 ################'
-- 🚦 非本片判定项（AC-2 用编译产物间接覆盖）。作为 T1.1 的上游旁证。
SELECT 'COST_BASIC 节点' AS item,
       func_signature = :NEW_FUNC AS "func_signature已改(期望t)", func_signature, display_name
  FROM semantic_node WHERE id = :NODE_COST_BASIC;
SELECT 'COST_BASIC 边键' AS item, count(*) = 1 AS "恰好1条(期望t)", count(*) AS key_count,
       string_agg(seq || ':' || left_column || '->' || right_column, ' | ' ORDER BY seq) AS detail
  FROM semantic_edge_key WHERE edge_id = :EDGE_COST_BASIC;

\echo ''
\echo '################ T1.6 · 覆盖缺口补充：QUOTE 语义图零改动 ################'
-- 🚨 无任何 AC 认领这一条（AC-4 断的是编译产物 md5，而报价侧本次不重编译
--    ⇒ 报价侧语义边键被误删也不会反映到 md5 上，会一直静默到下次重编译）。
--    已上报主线，见 S1-用例.md §5。
SELECT 'QUOTE 节点' AS item,
       func_signature = 'f_material_element_price(:customerCode, :priceBaseDate)' AS "仍为旧函数(期望t)",
       func_signature, status
  FROM semantic_node WHERE id = :NODE_QUOTE;
SELECT 'QUOTE 边键' AS item, count(*) = 2 AS "仍为2条(期望t)", count(*) AS key_count,
       string_agg(seq || ':' || left_column || '->' || right_column, ' | ' ORDER BY seq) AS detail
  FROM semantic_edge_key WHERE edge_id = :EDGE_QUOTE;
