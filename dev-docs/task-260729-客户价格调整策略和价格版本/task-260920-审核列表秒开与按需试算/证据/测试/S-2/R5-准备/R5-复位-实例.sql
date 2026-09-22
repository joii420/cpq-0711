-- task-260920 S-2 · R5 复位脚本 v2（按 D-17「甲：还原报价单 + 删 7 条新指针」修改）【执行前必须按 D-7 报批】
-- 🚦 默认以 ROLLBACK 结尾（演练模式）：先整段跑一次看每步 「影响行数」 是否与报批数字一致，用户批准后把末尾改成 COMMIT 再跑。
-- 生成：bash R5-复位-生成.sh <备份目录> <待复位料号 {a,b}> <R4 升版开始时刻 UTC> > R5-复位-实例.sql
-- 占位：/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/测试/S-2/R4-准备/E-6-备份 备份目录；{PERF600-B00011,PERF600-B00012,PERF600-B00021,PERF600-B00022,PERF600-B00023,PERF600-B00598,PERF600-B00599} 料号数组；2026-09-22 08:53:00+00 升版开始时刻（UTC）；CUST-0004 客户号
-- 每步「影响行数口径」写在步骤注释里；所有 UPDATE / DELETE 都有 WHERE 且以备份 id 集合或本次新建 id 集合为界。
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
SELECT (SELECT count(*) FROM bk_qli) qli, (SELECT count(*) FROM bk_qlcd) qlcd, (SELECT count(*) FROM bk_q) q, (SELECT count(*) FROM bk_qpr) qpr,
       (SELECT count(*) FROM bk_mbom) mbom, (SELECT count(*) FROM bk_ebom) ebom, (SELECT count(*) FROM bk_mpvr) mpvr;

-- R5-1 quotation_line_item：影响行数 = 备份行数（bk_qli），按 id 还原升版写过的列
UPDATE quotation_line_item t SET quote_card_values = b.quote_card_values, costing_card_values = b.costing_card_values,
       quote_excel_values = b.quote_excel_values, subtotal = b.subtotal, line_unit_price = b.line_unit_price,
       discount_base_amount = b.discount_base_amount, line_final_price = b.line_final_price,
       line_discount_amount = b.line_discount_amount, line_total_amount = b.line_total_amount,
       card_snapshot_at = b.card_snapshot_at, quote_values_at = b.quote_values_at, row_version = b.row_version
  FROM bk_qli b WHERE t.id = b.id;
-- R5-2 quotation_line_component_data：影响行数 = bk_qlcd 行数
UPDATE quotation_line_component_data t SET snapshot_rows = b.snapshot_rows, row_data = b.row_data, row_version = b.row_version,
       snapshot_at = b.snapshot_at FROM bk_qlcd b WHERE t.id = b.id;
-- R5-3 quotation 表头：影响行数 = bk_q 行数（受影响单据数）
UPDATE quotation t SET original_amount = b.original_amount, total_amount = b.total_amount, updated_at = b.updated_at
  FROM bk_q b WHERE t.id = b.id;
-- R5-4a quotation_price_revision 已有记录还原：影响行数 ≤ bk_qpr 行数（升版到新版本不应改已有记录；逐列还原防万一）
UPDATE quotation_price_revision t SET sealed = b.sealed, upgraded_material_nos = b.upgraded_material_nos,
       quote_card_values = b.quote_card_values, costing_card_values = b.costing_card_values, snapshot_rows = b.snapshot_rows,
       quote_total_amount = b.quote_total_amount, last_updated_at = b.last_updated_at
  FROM bk_qpr b WHERE t.id = b.id;
-- R5-4b 本次新建的修订记录：影响行数 = 受影响单据上 id 不在备份里、且 created_at ≥ T0 的记录数
--       （预期：每张受影响单 1 条本期记录；0628 若首次升版另有 1 条初版）。先列出再删。
SELECT r.id, q.quotation_number, r.revision_no, r.based_version_id, r.created_at FROM quotation_price_revision r JOIN quotation q ON q.id = r.quotation_id
 WHERE r.quotation_id IN (SELECT id FROM bk_q) AND r.id NOT IN (SELECT id FROM bk_qpr) AND r.created_at >= '2026-09-22 08:53:00+00';
DELETE FROM quotation_price_revision r
 WHERE r.quotation_id IN (SELECT id FROM bk_q) AND r.id NOT IN (SELECT id FROM bk_qpr) AND r.created_at >= '2026-09-22 08:53:00+00';
-- R5-5 _record element_price：影响行数 ≤ bk_mbom + bk_ebom
UPDATE ds_quote_material_bom_record t SET element_price = b.element_price, updated_at = b.updated_at FROM bk_mbom b WHERE t.id = b.id;
UPDATE ds_quote_element_bom_record  t SET element_price = b.element_price, updated_at = b.updated_at FROM bk_ebom b WHERE t.id = b.id;
-- R5-6 版本指针（D-17 甲）：只删 R4 新建的、这 7 个料号的指针 —— 「料号属于 {PERF600-B00011,PERF600-B00012,PERF600-B00021,PERF600-B00022,PERF600-B00023,PERF600-B00598,PERF600-B00599} 且 id 不在备份里」。
--       这些指针可能已被 V4 进池推进（version_id 不再是 V3），按 id 判而不按 version_id 判。预期影响 = 7（备份时 7 个料号都无指针）
SELECT id, material_no, version_id, updated_at FROM material_price_version_ref t WHERE t.customer_no = 'CUST-0004' AND t.material_no = ANY(CAST('{PERF600-B00011,PERF600-B00012,PERF600-B00021,PERF600-B00022,PERF600-B00023,PERF600-B00598,PERF600-B00599}' AS text[]))
   AND t.id NOT IN (SELECT id FROM bk_mpvr);
DELETE FROM material_price_version_ref t WHERE t.customer_no = 'CUST-0004' AND t.material_no = ANY(CAST('{PERF600-B00011,PERF600-B00012,PERF600-B00021,PERF600-B00022,PERF600-B00023,PERF600-B00598,PERF600-B00599}' AS text[]))
   AND t.id NOT IN (SELECT id FROM bk_mpvr);
-- R5-7 / R5-8（审核行、更新批次）按 D-17 甲 **不复位**：审核行与批次保留作「通过过」的记录（版本链不还原，D-15）

-- 复位后抽样核对：受影响行的关键列与备份逐位相同（期望 mismatch = 0）
SELECT count(*) AS qli_mismatch FROM quotation_line_item t JOIN bk_qli b ON b.id = t.id
 WHERE (t.subtotal, t.line_total_amount, t.quote_card_values::text, coalesce(t.quote_excel_values::text,''))
       IS DISTINCT FROM (b.subtotal, b.line_total_amount, b.quote_card_values::text, coalesce(b.quote_excel_values::text,''));
SELECT count(*) AS q_mismatch FROM quotation t JOIN bk_q b ON b.id = t.id
 WHERE (t.original_amount, t.total_amount) IS DISTINCT FROM (b.original_amount, b.total_amount);
SELECT count(*) AS qlcd_mismatch FROM quotation_line_component_data t JOIN bk_qlcd b ON b.id = t.id
 WHERE (t.snapshot_rows::text, t.row_data::text, t.row_version) IS DISTINCT FROM (b.snapshot_rows::text, b.row_data::text, b.row_version);
SELECT count(*) AS qli_full_mismatch FROM quotation_line_item t JOIN bk_qli b ON b.id = t.id
 WHERE (t.subtotal, t.line_unit_price, t.discount_base_amount, t.line_final_price, t.line_discount_amount, t.line_total_amount, coalesce(t.quote_card_values::text,''), coalesce(t.costing_card_values::text,''))
   IS DISTINCT FROM (b.subtotal, b.line_unit_price, b.discount_base_amount, b.line_final_price, b.line_discount_amount, b.line_total_amount, coalesce(b.quote_card_values::text,''), coalesce(b.costing_card_values::text,''));
SELECT count(*) AS qpr_rows_after FROM quotation_price_revision WHERE quotation_id IN (SELECT id FROM bk_q);
SELECT count(*) AS new_pointers_left FROM material_price_version_ref WHERE customer_no='CUST-0004' AND material_no = ANY(CAST('{PERF600-B00011,PERF600-B00012,PERF600-B00021,PERF600-B00022,PERF600-B00023,PERF600-B00598,PERF600-B00599}' AS text[]));
ROLLBACK;  -- 🚦 演练默认 ROLLBACK；用户批准后才改成 COMMIT
