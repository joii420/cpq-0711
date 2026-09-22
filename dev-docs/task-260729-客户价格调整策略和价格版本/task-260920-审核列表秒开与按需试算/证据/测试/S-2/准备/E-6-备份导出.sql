-- task-260920 S-2 · E-6 真升版前备份（只读：BEGIN READ ONLY + COPY TO STDOUT，不写库）
-- 写入集合按读码推导（代码位置见 派工回报/测试S2-准备-v1.md §4），🚫 凭印象列。
-- 用法（dir 须事先 mkdir）：
--   psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -X -v cust=CUST-0004 \
--     -v mats='{PERF600-B00230,PERF600-B00231}' -v ver=<待通过审核行所在 version_id> -v dir=<绝对目录> -f E-6-备份导出.sql
-- 受影响行口径 = doApprove.findAllActiveLines：该客户（customer.code）下 product_part_no_snapshot ∈ mats、
--   composite_type 非 PART、报价单状态 ∈ ACTIVE_STATUSES(DRAFT,SUBMITTED,APPROVED,REJECTED,COSTING_REJECTED) 的全部行 —— 跨全部活单，不只依据单。
BEGIN READ ONLY;
SELECT now() AS backup_sample_utc;

\echo '== 0. 影响面计数（报批用）'
WITH l AS (
  SELECT li.id, li.quotation_id FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id
    JOIN customer cu ON cu.id = q.customer_id
   WHERE cu.code = :'cust' AND li.product_part_no_snapshot = ANY(CAST(:'mats' AS text[]))
     AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
     AND q.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED'))
SELECT (SELECT count(*) FROM l) AS lines, (SELECT count(DISTINCT quotation_id) FROM l) AS quotations,
       (SELECT string_agg(DISTINCT q.quotation_number, ',') FROM l JOIN quotation q ON q.id = l.quotation_id) AS quotation_nos,
       (SELECT count(*) FROM quotation_line_component_data cd WHERE cd.line_item_id IN (SELECT id FROM l)) AS comp_rows,
       (SELECT count(*) FROM quotation_price_revision r WHERE r.quotation_id IN (SELECT quotation_id FROM l)) AS revisions,
       (SELECT count(*) FROM ds_quote_material_bom_record r WHERE r.quotation_id IN (SELECT quotation_id FROM l)) AS mbom_record_rows,
       (SELECT count(*) FROM ds_quote_element_bom_record r WHERE r.quotation_id IN (SELECT quotation_id FROM l)) AS ebom_record_rows,
       (SELECT count(*) FROM quotation_view_structure s WHERE s.quotation_id IN (SELECT quotation_id FROM l)) AS view_structures;

\echo '== 1. quotation_line_item 整行（S4a quote_card_values / S5 quote_card_values,costing_card_values / S6 subtotal + LineDiscountService 5 列 / S7 quote_excel_values）'
\set f :dir '/qli.csv'
\o :f
COPY (SELECT li.* FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id JOIN customer cu ON cu.id = q.customer_id
       WHERE cu.code = :'cust' AND li.product_part_no_snapshot = ANY(CAST(:'mats' AS text[]))
         AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
         AND q.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED') ORDER BY li.id) TO STDOUT WITH CSV HEADER;
\o

\echo '== 2. quotation_line_component_data 整行（S3a/S3b/S4b snapshot_rows,row_data,row_version）'
\set f :dir '/qlcd.csv'
\o :f
COPY (SELECT cd.* FROM quotation_line_component_data cd WHERE cd.line_item_id IN (
        SELECT li.id FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id JOIN customer cu ON cu.id = q.customer_id
         WHERE cu.code = :'cust' AND li.product_part_no_snapshot = ANY(CAST(:'mats' AS text[]))
           AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
           AND q.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED')) ORDER BY cd.id) TO STDOUT WITH CSV HEADER;
\o

\echo '== 3. quotation 表头整行（S8 total_amount / S8b recomputeDraftHeaderTotals original_amount,total_amount）'
\set f :dir '/quotation.csv'
\o :f
COPY (SELECT q.* FROM quotation q WHERE q.id IN (
        SELECT li.quotation_id FROM quotation_line_item li JOIN quotation q2 ON q2.id = li.quotation_id JOIN customer cu ON cu.id = q2.customer_id
         WHERE cu.code = :'cust' AND li.product_part_no_snapshot = ANY(CAST(:'mats' AS text[]))
           AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
           AND q2.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED')) ORDER BY q.id) TO STDOUT WITH CSV HEADER;
\o

\echo '== 4. quotation_price_revision 整行（S9 前置 sealInitialIfNeeded 初版 / S9 或 job 延后写 本期记录）'
\set f :dir '/qpr.csv'
\o :f
COPY (SELECT r.* FROM quotation_price_revision r WHERE r.quotation_id IN (
        SELECT li.quotation_id FROM quotation_line_item li JOIN quotation q2 ON q2.id = li.quotation_id JOIN customer cu ON cu.id = q2.customer_id
         WHERE cu.code = :'cust' AND li.product_part_no_snapshot = ANY(CAST(:'mats' AS text[]))
           AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
           AND q2.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED')) ORDER BY r.id) TO STDOUT WITH CSV HEADER;
\o

\echo '== 5. ds_quote_material_bom_record / ds_quote_element_bom_record（S3 写点后 syncElementPrice：element_price, updated_at）'
\set f :dir '/ds_quote_material_bom_record.csv'
\o :f
COPY (SELECT r.* FROM ds_quote_material_bom_record r WHERE r.quotation_id IN (
        SELECT li.quotation_id FROM quotation_line_item li JOIN quotation q2 ON q2.id = li.quotation_id JOIN customer cu ON cu.id = q2.customer_id
         WHERE cu.code = :'cust' AND li.product_part_no_snapshot = ANY(CAST(:'mats' AS text[]))
           AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
           AND q2.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED')) ORDER BY r.id) TO STDOUT WITH CSV HEADER;
\o
\set f :dir '/ds_quote_element_bom_record.csv'
\o :f
COPY (SELECT r.* FROM ds_quote_element_bom_record r WHERE r.quotation_id IN (
        SELECT li.quotation_id FROM quotation_line_item li JOIN quotation q2 ON q2.id = li.quotation_id JOIN customer cu ON cu.id = q2.customer_id
         WHERE cu.code = :'cust' AND li.product_part_no_snapshot = ANY(CAST(:'mats' AS text[]))
           AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
           AND q2.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED')) ORDER BY r.id) TO STDOUT WITH CSV HEADER;
\o

\echo '== 6. quotation_view_structure（S2 结构缺失时 ensureStructure 补建；正常路径不写，防万一）'
\set f :dir '/qvs.csv'
\o :f
COPY (SELECT s.* FROM quotation_view_structure s WHERE s.quotation_id IN (
        SELECT li.quotation_id FROM quotation_line_item li JOIN quotation q2 ON q2.id = li.quotation_id JOIN customer cu ON cu.id = q2.customer_id
         WHERE cu.code = :'cust' AND li.product_part_no_snapshot = ANY(CAST(:'mats' AS text[]))
           AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
           AND q2.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED')) ORDER BY s.id) TO STDOUT WITH CSV HEADER;
\o

\echo '== 7. material_price_version_ref（doApprove.advancePointer）—— 该客户全部指针'
\set f :dir '/mpvr.csv'
\o :f
COPY (SELECT * FROM material_price_version_ref WHERE customer_no = :'cust' ORDER BY material_no) TO STDOUT WITH CSV HEADER;
\o

\echo '== 8. material_price_review（doApprove 置 APPROVED + reviewed_by/at/comment）—— 本版本这些料号的审核行'
\set f :dir '/mpr.csv'
\o :f
COPY (SELECT * FROM material_price_review WHERE version_id = :'ver' AND material_no = ANY(CAST(:'mats' AS text[])) ORDER BY material_no) TO STDOUT WITH CSV HEADER;
\o

\echo '== 9. 更新批次基线（doApprove 新建 job + job_item；复位时按「本次新建的 id」处理，这里只记基线）'
\set f :dir '/mpuj-baseline.csv'
\o :f
COPY (SELECT id, customer_no, version_no, status, triggered_at FROM material_price_update_job WHERE customer_no = :'cust' ORDER BY triggered_at) TO STDOUT WITH CSV HEADER;
\o
\echo '== 10. 备份文件行数自检见 shell：wc -l <dir>/*.csv'
ROLLBACK;
