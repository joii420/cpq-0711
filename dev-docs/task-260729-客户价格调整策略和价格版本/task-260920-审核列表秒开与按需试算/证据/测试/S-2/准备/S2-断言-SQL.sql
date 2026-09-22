-- task-260920 S-2 · 各 AC 的 SQL 取证片段（全部只读）。按段复制执行，或 psql -v 传参后整份跑。
-- 通用参数：-v cust=CUST-0004 -v ver=<本轮版本 id> -v mats='{料号,...}' -v t_click='<点击时刻 UTC>'
BEGIN READ ONLY;
SELECT now() AS sample_utc;

\echo '== AC-1 / AC-4 接口对账辅助：本版本待处理行中未计算数（应 = 接口 notComputedTotal，无筛选时）'
SELECT count(*) FILTER (WHERE budget_status IN ('QUEUED','COMPUTING')) AS not_computed,
       count(*) AS pending_total
  FROM material_price_review WHERE version_id = :'ver' AND status = 'PENDING';

\echo '== AC-4 辅助：breachedOnly 下因未计算被排除的行（应 = excludedByNotComputed）；标红且 READY 的行数（列表应只剩这些）'
SELECT count(*) FILTER (WHERE budget_status <> 'READY') AS excluded_by_not_computed_upper,
       count(*) FILTER (WHERE budget_status = 'READY' AND breached_count > 0) AS breached_ready
  FROM material_price_review WHERE version_id = :'ver' AND status = 'PENDING';
-- ⚠️ api.md §1.3 原文：「因未计算而未参与筛选的待处理行数」—— 是否含 FAILED 契约没写死，上面 upper 含 FAILED；对不上时分别报 QUEUED+COMPUTING 与 FAILED 两个数

\echo '== AC-5 / AC-6 / AC-21：指定料号的审核行与比对列（逐位值 + 更新时间）'
SELECT r.material_no, r.id AS review_id, r.status, r.budget_status, r.budget_error, r.updated_at,
       c.column_id, c.quote_current, c.quote_adjusted, c.costing_adjusted, c.diff_adjusted, c.status AS col_status, c.missing_side
  FROM material_price_review r LEFT JOIN material_price_review_column c ON c.review_id = r.id
 WHERE r.version_id = :'ver' AND r.material_no = ANY(CAST(:'mats' AS text[])) ORDER BY 1, c.column_id;

\echo '== AC-21：同一料号在本版本的审核行条数（必须 = 1）'
SELECT material_no, count(*) FROM material_price_review WHERE version_id = :'ver' AND material_no = ANY(CAST(:'mats' AS text[])) GROUP BY 1;

\echo '== AC-8 ④ / AC-9：本版本、点击时刻之后的新更新批次（必须 0 行）'
SELECT id, status, total_count, triggered_at FROM material_price_update_job
 WHERE customer_no = :'cust' AND version_id = :'ver' AND triggered_at >= CAST(:'t_click' AS timestamptz);

\echo '== AC-9：这些料号的版本指针（与操作前快照逐个比）'
SELECT material_no, version_id, updated_at FROM material_price_version_ref
 WHERE customer_no = :'cust' AND material_no = ANY(CAST(:'mats' AS text[])) ORDER BY 1;

\echo '== AC-12：本版本待处理行中 QUEUED/COMPUTING = 0 且 待处理总数 > 0'
SELECT count(*) FILTER (WHERE budget_status IN ('QUEUED','COMPUTING')) AS unfinished,
       count(*) AS pending_total,
       count(*) FILTER (WHERE budget_status = 'FAILED') AS failed,
       max(updated_at) AS last_updated
  FROM material_price_review WHERE version_id = :'ver' AND status = 'PENDING';
SELECT budget_error, count(*) FROM material_price_review WHERE version_id = :'ver' AND budget_status = 'FAILED' GROUP BY 1 ORDER BY 2 DESC LIMIT 10;

\echo '== AC-14 辅助：本版本审核行建行/最后更新（全量耗时的库侧旁证；判据用日志 [perf] budget-compute / budget-group）'
SELECT min(created_at) AS first_created, max(created_at) AS last_created, max(updated_at) AS last_updated,
       max(updated_at) - min(created_at) AS wall
  FROM material_price_review WHERE version_id = :'ver';

\echo '== AC-10 / AC-15 / AC-26：依据行的实际报价侧金额（升版后）—— 依据行口径 J-3：单据 created_at 倒序 → sort_order 升序(空后) → id 升序'
WITH basis AS (
  SELECT r.material_no, r.basis_quotation_id FROM material_price_review r
   WHERE r.version_id = :'ver' AND r.material_no = ANY(CAST(:'mats' AS text[])))
SELECT DISTINCT ON (b.material_no) b.material_no, q.quotation_number, li.id AS line_id, li.subtotal, li.line_unit_price,
       li.line_total_amount, li.quote_excel_values IS NULL AS excel_invalidated
  FROM basis b JOIN quotation q ON q.id = b.basis_quotation_id
  JOIN quotation_line_item li ON li.quotation_id = q.id AND li.product_part_no_snapshot = b.material_no
   AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
 ORDER BY b.material_no, li.sort_order ASC NULLS LAST, li.id ASC;
-- ⚠️ 「实际报价侧金额」与 quote_adjusted 对哪一列（subtotal / line_unit_price）要在 R4 前由主线确认口径（回报 §5 Q-3）

\echo '== AC-20 ③：作废之后该行及其比对列无写入（比对列无 updated_at，只能看审核行 updated_at 与列 created_at）'
SELECT r.material_no, r.status, r.budget_status, r.updated_at, max(c.created_at) AS col_max_created
  FROM material_price_review r LEFT JOIN material_price_review_column c ON c.review_id = r.id
 WHERE r.version_id = :'ver' AND r.material_no = ANY(CAST(:'mats' AS text[])) GROUP BY 1,2,3,4;
ROLLBACK;
