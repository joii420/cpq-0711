-- ============================================================================
-- 证伪 1 · 灵敏度实验（test.md §3）  —— 只读，零写入
-- 做法：不动数据库，只把断言里的**期望字面量**故意写错一个字，
--       确认原本为 t 的断言**变成 f**。
-- 判据：下面 5 行的 expect_FALSE 列必须**全部为 f**。
--       任何一行为 t ⇒ 该断言压根没在读真数据（或被写成了恒真），对应 AC 的用例作废重写。
-- ============================================================================
\pset border 2

\set COST_CID '''54001a3b-fd2e-4580-b90e-4bfa157ea77c'''
\set NODE_COST_DETAIL '''26090901-0000-4000-8000-000000000002'''
\set EDGE_COST_DETAIL '''26090904-0000-4000-8000-000000000002'''
\set TPL_ZHENGTAI '''62dd00cb-2fcc-47f5-a055-9801ab11461e'''

-- F1/F2/F3 · AC-2 的三条正向断言各毒化一次
WITH v AS (SELECT sql_template,
       substring(sql_template from 'FROM[[:space:]]+[^[:space:]]+[[:space:]]+([A-Za-z_][A-Za-z0-9_]*)') AS a
       FROM component_sql_view WHERE component_id = :COST_CID)
SELECT 'F1 · AC2_2 列名毒化 element_code -> element_codeX' AS experiment,
       coalesce(sql_template ~ ('cep\.element_codeX[[:space:]]*=[[:space:]]*' || a || '\.element_code'), false) AS expect_FALSE
  FROM v
UNION ALL
SELECT 'F2 · AC2_2 别名毒化 <alias> -> <alias>X',
       coalesce(sql_template ~ ('cep\.element_code[[:space:]]*=[[:space:]]*' || a || 'X\.element_code'), false) FROM v
UNION ALL
SELECT 'F3 · AC2_1 函数名毒化 f_customer_element_price -> f_customer_element_priceX',
       position('f_customer_element_priceX(:customerCode, :priceBaseDate)' in sql_template) > 0 FROM v;

-- F4 · AC-9 节点断言毒化
SELECT 'F4 · AC9 func_signature 毒化 ...priceX' AS experiment,
       coalesce(bool_or(func_signature = 'f_customer_element_priceX(:customerCode, :priceBaseDate)'), false) AS expect_FALSE
  FROM semantic_node WHERE id = :NODE_COST_DETAIL;

-- F5 · AC-9 边键计数断言毒化（把期望条数从 1 改成 99）
SELECT 'F5 · AC9 边键期望条数毒化 1 -> 99' AS experiment, count(*) = 99 AS expect_FALSE
  FROM semantic_edge_key WHERE edge_id = :EDGE_COST_DETAIL;

-- F6 · AC-8 基线毒化（把基线 updated_at 尾号改一位）
SELECT 'F6 · AC8 updated_at 基线毒化 ...608232 -> ...608233' AS experiment,
       to_char(updated_at,'YYYY-MM-DD HH24:MI:SS.US') = '2026-09-11 01:01:02.608233' AS expect_FALSE
  FROM template WHERE id = :TPL_ZHENGTAI;
