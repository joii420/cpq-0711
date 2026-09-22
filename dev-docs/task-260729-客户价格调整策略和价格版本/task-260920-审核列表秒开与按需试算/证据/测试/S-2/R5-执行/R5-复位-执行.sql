-- task-260920 S-2 · R5 复位【执行版 · 带行数守卫】用户批准原文「批准 R5（推荐）」（D-17 甲）。
-- 来源：R5-准备/R5-复位-实例.sql（原文件不动）。每条写语句改为 WITH … RETURNING 1 计数，与演练行数不等即 \q（不 COMMIT，断开即回滚）。
\set ON_ERROR_STOP on
\set ECHO queries
BEGIN;
SELECT now() AS reset_start_utc;
CREATE TEMP TABLE bk_qli  (LIKE quotation_line_item)            ON COMMIT DROP;
CREATE TEMP TABLE bk_qlcd (LIKE quotation_line_component_data)  ON COMMIT DROP;
CREATE TEMP TABLE bk_q    (LIKE quotation)                      ON COMMIT DROP;
CREATE TEMP TABLE bk_qpr  (LIKE quotation_price_revision)       ON COMMIT DROP;
CREATE TEMP TABLE bk_mbom (LIKE ds_quote_material_bom_record)   ON COMMIT DROP;
CREATE TEMP TABLE bk_ebom (LIKE ds_quote_element_bom_record)    ON COMMIT DROP;
CREATE TEMP TABLE bk_mpvr (LIKE material_price_version_ref)     ON COMMIT DROP;
\copy bk_qli  FROM '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/测试/S-2/R4-准备/E-6-备份/qli.csv' WITH CSV HEADER
\copy bk_qlcd FROM '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/测试/S-2/R4-准备/E-6-备份/qlcd.csv' WITH CSV HEADER
\copy bk_q    FROM '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/测试/S-2/R4-准备/E-6-备份/quotation.csv' WITH CSV HEADER
\copy bk_qpr  FROM '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/测试/S-2/R4-准备/E-6-备份/qpr.csv' WITH CSV HEADER
\copy bk_mbom FROM '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/测试/S-2/R4-准备/E-6-备份/ds_quote_material_bom_record.csv' WITH CSV HEADER
\copy bk_ebom FROM '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/测试/S-2/R4-准备/E-6-备份/ds_quote_element_bom_record.csv' WITH CSV HEADER
\copy bk_mpvr FROM '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/测试/S-2/R4-准备/E-6-备份/mpvr.csv' WITH CSV HEADER

-- R5-1 报价行（期望 7）
WITH x AS (UPDATE quotation_line_item t SET quote_card_values = b.quote_card_values, costing_card_values = b.costing_card_values,
       quote_excel_values = b.quote_excel_values, subtotal = b.subtotal, line_unit_price = b.line_unit_price,
       discount_base_amount = b.discount_base_amount, line_final_price = b.line_final_price,
       line_discount_amount = b.line_discount_amount, line_total_amount = b.line_total_amount,
       card_snapshot_at = b.card_snapshot_at, quote_values_at = b.quote_values_at, row_version = b.row_version
  FROM bk_qli b WHERE t.id = b.id RETURNING 1) SELECT count(*) AS n FROM x \gset
SELECT :n = 7 AS ok \gset
\if :ok
  \echo R5-1 OK n=:n
\else
  \echo R5-1 行数不符 n=:n 期望 7，中止不提交
  \q
\endif

-- R5-2 组件数据（期望 21）
WITH x AS (UPDATE quotation_line_component_data t SET snapshot_rows = b.snapshot_rows, row_data = b.row_data, row_version = b.row_version,
       snapshot_at = b.snapshot_at FROM bk_qlcd b WHERE t.id = b.id RETURNING 1) SELECT count(*) AS n FROM x \gset
SELECT :n = 21 AS ok \gset
\if :ok
  \echo R5-2 OK n=:n
\else
  \echo R5-2 行数不符 n=:n 期望 21，中止不提交
  \q
\endif

-- R5-3 表头（期望 1）
WITH x AS (UPDATE quotation t SET original_amount = b.original_amount, total_amount = b.total_amount, updated_at = b.updated_at
  FROM bk_q b WHERE t.id = b.id RETURNING 1) SELECT count(*) AS n FROM x \gset
SELECT :n = 1 AS ok \gset
\if :ok
  \echo R5-3 OK n=:n
\else
  \echo R5-3 行数不符 n=:n 期望 1，中止不提交
  \q
\endif

-- R5-4a 原修订记录（期望 2）
WITH x AS (UPDATE quotation_price_revision t SET sealed = b.sealed, upgraded_material_nos = b.upgraded_material_nos,
       quote_card_values = b.quote_card_values, costing_card_values = b.costing_card_values, snapshot_rows = b.snapshot_rows,
       quote_total_amount = b.quote_total_amount, last_updated_at = b.last_updated_at
  FROM bk_qpr b WHERE t.id = b.id RETURNING 1) SELECT count(*) AS n FROM x \gset
SELECT :n = 2 AS ok \gset
\if :ok
  \echo R5-4a OK n=:n
\else
  \echo R5-4a 行数不符 n=:n 期望 2，中止不提交
  \q
\endif

-- R5-4b 删新修订记录（期望 1，且 id 以 0a2b7f7a 开头）
WITH x AS (DELETE FROM quotation_price_revision r
 WHERE r.quotation_id IN (SELECT id FROM bk_q) AND r.id NOT IN (SELECT id FROM bk_qpr) AND r.created_at >= '2026-09-22 08:53:00+00'
 RETURNING r.id) SELECT count(*) AS n, bool_and(id::text LIKE '0a2b7f7a%') AS idok FROM x \gset
SELECT :n = 1 AND :'idok' = 't' AS ok \gset
\if :ok
  \echo R5-4b OK n=:n
\else
  \echo R5-4b 行数/身份不符 n=:n idok=:idok，中止不提交
  \q
\endif

-- R5-5 _record（期望 0 / 0）
WITH x AS (UPDATE ds_quote_material_bom_record t SET element_price = b.element_price, updated_at = b.updated_at FROM bk_mbom b WHERE t.id = b.id RETURNING 1) SELECT count(*) AS n FROM x \gset
SELECT :n = 0 AS ok \gset
\if :ok
  \echo R5-5a OK n=:n
\else
  \echo R5-5a 行数不符 n=:n 期望 0，中止不提交
  \q
\endif
WITH x AS (UPDATE ds_quote_element_bom_record t SET element_price = b.element_price, updated_at = b.updated_at FROM bk_ebom b WHERE t.id = b.id RETURNING 1) SELECT count(*) AS n FROM x \gset
SELECT :n = 0 AS ok \gset
\if :ok
  \echo R5-5b OK n=:n
\else
  \echo R5-5b 行数不符 n=:n 期望 0，中止不提交
  \q
\endif

-- R5-6 删 7 个料号的新指针（期望 7，且 id 前缀为批准的 7 个）
WITH x AS (DELETE FROM material_price_version_ref t WHERE t.customer_no = 'CUST-0004' AND t.material_no = ANY(CAST('{PERF600-B00011,PERF600-B00012,PERF600-B00021,PERF600-B00022,PERF600-B00023,PERF600-B00598,PERF600-B00599}' AS text[]))
   AND t.id NOT IN (SELECT id FROM bk_mpvr) RETURNING t.id)
SELECT count(*) AS n, bool_and(left(id::text, 8) IN ('034bed3c','0d33835a','43fad760','b4285175','cde867c7','f12ee8ee','fa1a7b73')) AS idok FROM x \gset
SELECT :n = 7 AND :'idok' = 't' AS ok \gset
\if :ok
  \echo R5-6 OK n=:n
\else
  \echo R5-6 行数/身份不符 n=:n idok=:idok，中止不提交
  \q
\endif

\echo 全部守卫通过，提交
COMMIT;
SELECT now() AS reset_end_utc;
