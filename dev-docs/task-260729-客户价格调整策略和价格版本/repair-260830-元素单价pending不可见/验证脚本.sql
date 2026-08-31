-- =====================================================================================
-- repair-260830 · B-4 零回归验证脚本（服务 AC-4 / AC-5 / AC-6 / AC-7 / AC-8）
--
-- 🚫 全文只读：没有一条 INSERT / UPDATE / DELETE / DDL。可安全在 dev 库反复跑。
--
-- 用法（改动前跑一次存基线，改动后跑一次比对；两次输出应逐行相同，除 S-4/S-5 两节）：
--   PGPASSWORD=**** psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -f 验证脚本.sql
--
-- 脚本自适应：V397 尚未应用到本库时，S-4/S-5（需要三参重载）自动跳过并打印提示，
-- 其余各节照跑 —— 这正是「改动前基线」那一次的预期形态。
--
-- ⚠️ P-0 一节把 V397 三参版函数体「逐字内联」成普通只读查询，
--    因此**改动前也能验证修法的取数结果**，不需要先把函数装进库。
-- =====================================================================================

\set QUIET on
\pset pager off
\timing off
\set cust      'CUST-0004'
\set basedate  '2026-08-30'
\set pq_self   'bbcb566f-4600-4fb0-9f7c-1154cf566d66'
\set pq_other  '1288120e-c187-4c37-9388-7cc9c3cc90c4'
\set QUIET off

\echo ''
\echo '###############  环境探针  ###############'
SELECT current_database() AS db,
       (to_regprocedure('f_material_element_price(text,date)')      IS NOT NULL) AS has_2arg,
       (to_regprocedure('f_material_element_price(text,date,uuid)') IS NOT NULL) AS has_3arg_v397;

SELECT (to_regprocedure('f_material_element_price(text,date,uuid)') IS NOT NULL) AS has_v397 \gset

-- =====================================================================================
-- S-1 ｜ AC-4 核价侧零回归：核价侧渲染恒 quotationId=NULL ⇒ 改写器不介入 ⇒ 走两参版。
--        断言：候选料号 = 16（全部来自 _GLOBAL_ 正式行）
-- =====================================================================================
\echo ''
\echo '###############  S-1 (AC-4) 核价侧口径：两参版候选料号数，期望 16  ###############'
SELECT count(DISTINCT material_no) AS candidate_material_no_cnt,
       count(*)                    AS row_cnt
  FROM f_material_element_price(:'cust', DATE :'basedate');

-- =====================================================================================
-- S-2 ｜ AC-5 老客户零回归：CUST-0001 / CUST-0002 全是正式行（is_current=true），
--        走 is_current 半边，取价结果必须逐字不变。断言：行数 + md5 前后一致。
-- =====================================================================================
\echo ''
\echo '###############  S-2 (AC-5) 老客户取价结果 md5（前后必须逐字一致）  ###############'
SELECT 'CUST-0001' AS customer_no, count(*) AS row_cnt,
       md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), '')) AS md5
  FROM f_material_element_price('CUST-0001', DATE :'basedate') t
UNION ALL
SELECT 'CUST-0002', count(*),
       md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), ''))
  FROM f_material_element_price('CUST-0002', DATE :'basedate') t
ORDER BY 1;

-- =====================================================================================
-- S-3 ｜ AC-7 两参版签名/行为不变：CREATE OR REPLACE 委托后，两参调用结果必须逐字不变。
--        断言：80 行 / 16 料号 / md5 与基线一致。
-- =====================================================================================
\echo ''
\echo '###############  S-3 (AC-7) 两参版结果指纹（期望 80 行 / 16 料号 / md5 与基线同）  ###############'
SELECT count(*) AS row_cnt, count(DISTINCT material_no) AS material_no_cnt,
       md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), '')) AS md5
  FROM f_material_element_price(:'cust', DATE :'basedate') t;

-- =====================================================================================
-- S-6 ｜ AC-6 冻结态零回归（结构性断言，不依赖 V397）
--        冻结单不走 pending 改写（SqlViewExecutor.applyPendingRewrite 对 isQuotationFrozen()
--        直接 return）⇒ SQL 里不出现 :pq ⇒ 走两参版 ⇒ 结果 = S-3。本节列出被冻结的报价单，
--        供人工确认「确有冻结单可验」，取价断言本身与 S-3 同一份指纹。
-- =====================================================================================
\echo ''
\echo '###############  S-6 (AC-6) 冻结态样本清单（冻结单走两参版，指纹见 S-3）  ###############'
SELECT status, count(*) AS quotation_cnt
  FROM quotation
 WHERE customer_id = (SELECT id FROM customer WHERE code = :'cust')
 GROUP BY status ORDER BY 1;

-- =====================================================================================
-- P-0 ｜ 修法结果预验证（逐字内联 V397 三参版函数体，纯只读，改动前也能跑）
--        ① p_pq = 本单  → 202601010001/白银 应返 1 行 12345.0000（AC-1/AC-2 的库层依据）
--        ② p_pq = NULL  → 结果 md5 必须与 S-3 逐字相同（AC-7 退化）
--        ③ p_pq = 不存在的随机 uuid → 候选恰为 16（AC-8 隔离机制的正向证明：
--           不属于本单的 pending 行进不来）
-- =====================================================================================
\echo ''
\echo '###############  P-0 三参函数体内联 A/B（本单 / NULL / 陌生 uuid）  ###############'
WITH probes(label, p_pq) AS (
    VALUES ('①本单 pq',        :'pq_self'::uuid),
           ('②另一张单 pq',    :'pq_other'::uuid),
           ('③NULL(核价/冻结)', NULL::uuid),
           ('④陌生 uuid',      '11111111-2222-3333-4444-555555555555'::uuid)
),
cand AS (
    SELECT pr.label, x.material_no
      FROM probes pr
      CROSS JOIN LATERAL (
          SELECT mbi.material_no FROM material_bom_item mbi
           WHERE mbi.customer_no IN (:'cust', '_GLOBAL_')
             AND (mbi.is_current = true OR mbi.pending_quotation_id = pr.p_pq)
          UNION
          SELECT ebi.material_no FROM element_bom_item ebi
           WHERE ebi.customer_no IN (:'cust', '_GLOBAL_')
             AND (ebi.is_current = true OR ebi.pending_quotation_id = pr.p_pq)
      ) x
)
SELECT label, count(DISTINCT material_no) AS candidate_material_no_cnt
  FROM cand GROUP BY label ORDER BY label;

\echo '--- P-0 取价结果：本单 pq 下 202601010001/白银 应 = 12345.0000 ---'
WITH params AS (SELECT :'cust'::text AS p_customer_no, DATE :'basedate' AS p_base_date,
                       :'pq_self'::uuid AS p_pq),
pointers AS (
    SELECT r.material_no, r.version_id FROM material_price_version_ref r, params
     WHERE r.customer_no = params.p_customer_no),
versioned AS (
    SELECT p.material_no, i.element_code, i.current_price AS unit_price, i.currency, i.price_unit
      FROM pointers p JOIN element_price_version_item i ON i.version_id = p.version_id
     WHERE i.current_price IS NOT NULL),
candidate_materials AS (
    SELECT mbi.material_no FROM material_bom_item mbi, params
     WHERE mbi.customer_no IN (params.p_customer_no, '_GLOBAL_')
       AND (mbi.is_current = true OR mbi.pending_quotation_id = params.p_pq)
    UNION
    SELECT ebi.material_no FROM element_bom_item ebi, params
     WHERE ebi.customer_no IN (params.p_customer_no, '_GLOBAL_')
       AND (ebi.is_current = true OR ebi.pending_quotation_id = params.p_pq)),
realtime AS (
    SELECT cm.material_no, f.element_code, f.unit_price, f.currency, f.price_unit
      FROM candidate_materials cm, params
      CROSS JOIN f_customer_element_price(params.p_customer_no, params.p_base_date) f),
res AS (
    SELECT v.material_no, v.element_code, v.unit_price, v.currency, v.price_unit FROM versioned v
    UNION ALL
    SELECT r.material_no, r.element_code, r.unit_price, r.currency, r.price_unit
      FROM realtime r
      LEFT JOIN versioned v2 ON v2.material_no = r.material_no AND v2.element_code = r.element_code
     WHERE v2.material_no IS NULL)
SELECT material_no, element_code, unit_price, currency, price_unit
  FROM res WHERE material_no = '202601010001' AND element_code = '白银';

-- =====================================================================================
-- S-4 / S-5 ｜ 需要 V397 三参重载已应用到本库
-- =====================================================================================
\if :has_v397
\echo ''
\echo '###############  S-4 (AC-8) 三参传本单 pq vs 另一张单 pq：跨单隔离  ###############'
SELECT '本单 pq'     AS probe, count(*) AS row_cnt, count(DISTINCT material_no) AS material_no_cnt
  FROM f_material_element_price(:'cust', DATE :'basedate', :'pq_self'::uuid)
UNION ALL
SELECT '另一张单 pq', count(*), count(DISTINCT material_no)
  FROM f_material_element_price(:'cust', DATE :'basedate', :'pq_other'::uuid)
UNION ALL
SELECT '陌生 uuid',   count(*), count(DISTINCT material_no)
  FROM f_material_element_price(:'cust', DATE :'basedate', '11111111-2222-3333-4444-555555555555'::uuid);

\echo '--- S-4b：本单结果中，属于「另一张单独有」的 pending 料号数，期望 0 ---'
WITH self_only AS (
    SELECT material_no FROM element_bom_item WHERE customer_no = :'cust' AND pending_quotation_id = :'pq_self'::uuid
    UNION SELECT material_no FROM material_bom_item WHERE customer_no = :'cust' AND pending_quotation_id = :'pq_self'::uuid),
other_only AS (
    (SELECT material_no FROM element_bom_item WHERE customer_no = :'cust' AND pending_quotation_id = :'pq_other'::uuid
     UNION SELECT material_no FROM material_bom_item WHERE customer_no = :'cust' AND pending_quotation_id = :'pq_other'::uuid)
    EXCEPT
    (SELECT material_no FROM self_only)
    EXCEPT
    (SELECT material_no FROM material_bom_item WHERE customer_no IN (:'cust','_GLOBAL_') AND is_current = true))
SELECT (SELECT count(*) FROM other_only) AS other_only_material_cnt,
       (SELECT count(*) FROM f_material_element_price(:'cust', DATE :'basedate', :'pq_self'::uuid) f
         WHERE f.material_no IN (SELECT material_no FROM other_only)) AS leaked_into_self_cnt;

\echo ''
\echo '###############  S-5 (AC-7) 三参传 NULL 必须与两参版逐字相同  ###############'
WITH a AS (SELECT md5(coalesce(string_agg(t::text,'|' ORDER BY t::text),'')) m, count(*) c
             FROM f_material_element_price(:'cust', DATE :'basedate') t),
     b AS (SELECT md5(coalesce(string_agg(t::text,'|' ORDER BY t::text),'')) m, count(*) c
             FROM f_material_element_price(:'cust', DATE :'basedate', NULL::uuid) t)
SELECT a.c AS two_arg_rows, b.c AS three_arg_null_rows, a.m AS two_arg_md5, b.m AS three_arg_null_md5,
       (a.m = b.m AND a.c = b.c) AS identical_expect_true
  FROM a, b;
\else
\echo ''
\echo '>>> 本库尚未应用 V397（三参重载不存在）—— S-4 / S-5 跳过。这是「改动前基线」运行的预期形态。'
\endif

\echo ''
\echo '###############  验证脚本结束  ###############'
