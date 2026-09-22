-- task-260920 S-2 · R0 环境复采（只读）。用法：psql ... -X -v cust=CUST-0004 -f R0-环境复采.sql
BEGIN READ ONLY;
\echo '== 采样时刻（UTC / 本机太平洋）'
SELECT now() AS sample_utc, now() AT TIME ZONE 'America/Los_Angeles' AS sample_local;
\echo '== 正泰待处理版本'
SELECT id, version_no, status, trigger_type, created_at FROM element_price_version
 WHERE customer_no = :'cust' AND status = 'PENDING';
\echo '== 待处理版本的审核行分布（status × budget_status）+ 建/改时间范围'
SELECT r.status, r.budget_status, count(*) AS n, min(r.created_at) AS first_created, max(r.created_at) AS last_created, max(r.updated_at) AS last_updated
  FROM material_price_review r JOIN element_price_version v ON v.id = r.version_id
 WHERE v.customer_no = :'cust' AND v.status = 'PENDING'
 GROUP BY 1,2 ORDER BY 1,2;
\echo '== 生成新版本将作废的行数 = 待处理版本下 status=PENDING 的行（R0 报批数字）'
SELECT count(*) AS will_be_voided
  FROM material_price_review r JOIN element_price_version v ON v.id = r.version_id
 WHERE v.customer_no = :'cust' AND v.status = 'PENDING' AND r.status = 'PENDING';
\echo '== 该客户所有版本上仍为 PENDING 的审核行（应只在待处理版本上）'
SELECT v.version_no, v.status AS ver_status, count(*) FROM material_price_review r JOIN element_price_version v ON v.id=r.version_id
 WHERE r.customer_no = :'cust' AND r.status='PENDING' GROUP BY 1,2 ORDER BY 1;
\echo '== 全库在跑的更新批次'
SELECT count(*) AS running_jobs FROM material_price_update_job WHERE status = 'RUNNING';
SELECT id, customer_no, version_no, triggered_at FROM material_price_update_job WHERE status='RUNNING';
\echo '== 该客户最近 5 个更新批次'
SELECT version_no, status, total_count, success_count, failed_count, triggered_at, finished_at FROM material_price_update_job
 WHERE customer_no = :'cust' ORDER BY triggered_at DESC LIMIT 5;
\echo '== 三张大单（行数 / 状态 / 创建时间）'
SELECT q.quotation_number, q.status, q.created_at, count(li.id) AS lines, q.original_amount, q.total_amount
  FROM quotation q LEFT JOIN quotation_line_item li ON li.quotation_id = q.id
 WHERE q.quotation_number IN ('QT-20260908-0628','QT-20260908-0629','QT-20260909-0629','QT-20260911-0842')
 GROUP BY q.id ORDER BY 1;
\echo '== 待处理版本审核行按依据单分组（料号多的组先派）'
SELECT coalesce(q.quotation_number,'(无依据单)') AS basis, count(*) AS materials
  FROM material_price_review r JOIN element_price_version v ON v.id = r.version_id
  LEFT JOIN quotation q ON q.id = r.basis_quotation_id
 WHERE v.customer_no = :'cust' AND v.status='PENDING' GROUP BY 1 ORDER BY 2 DESC LIMIT 8;
\echo '== 版本指针数'
SELECT count(*) AS refs FROM material_price_version_ref WHERE customer_no = :'cust';
\echo '== 策略（触发配置）'
SELECT customer_no, enabled, cycle_type, execute_time, material_scope_mode, updated_at FROM customer_price_adjust_strategy WHERE customer_no = :'cust';
ROLLBACK;
