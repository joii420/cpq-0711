-- task-260920 S-2 · D-14 选料号（只读）：0842 组内 PERF600-B*、依据行 subtotal=0、预算调整后非空、只挂 0842 一张活单一行
BEGIN READ ONLY;
SELECT now() AS sample_utc;
WITH g AS (
  SELECT r.material_no, r.id, row_number() OVER (ORDER BY r.material_no) pos, count(*) OVER () grp, c.quote_current, c.quote_adjusted
    FROM material_price_review r LEFT JOIN material_price_review_column c ON c.review_id=r.id AND c.column_id='col-default'
   WHERE r.version_id=:'prev' AND r.basis_quotation_id=(SELECT id FROM quotation WHERE quotation_number='QT-20260911-0842')),
a AS (
  SELECT li.product_part_no_snapshot m, count(*) lines, count(DISTINCT li.quotation_id) quotes, string_agg(DISTINCT q.quotation_number, ',') qnos,
         max(li.subtotal) sub, max(li.id::text) line_id
    FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id JOIN customer cu ON cu.id=q.customer_id
   WHERE cu.code='CUST-0004' AND (li.composite_type IS NULL OR li.composite_type<>'PART')
     AND q.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED') GROUP BY 1),
ptr AS (SELECT material_no, version_id FROM material_price_version_ref WHERE customer_no='CUST-0004')
SELECT CASE WHEN g.material_no IN (:ac26) THEN 'AC-26' WHEN g.material_no IN (:ac10) THEN 'AC-10' WHEN g.material_no IN (:ac15) THEN 'AC-15' END AS use,
       g.material_no, g.pos, g.grp, a.sub::text subtotal_now, g.quote_current::text prev_qc, g.quote_adjusted::text prev_qa,
       a.quotes, a.lines, a.qnos, a.line_id, (SELECT version_id::text FROM ptr WHERE ptr.material_no=g.material_no) pointer
  FROM g JOIN a ON a.m=g.material_no
 WHERE g.material_no IN (:ac26, :ac10, :ac15) ORDER BY 1, g.pos;
\echo '== 候选池规模：PERF600 且 subtotal=0 且 只 1 单 1 行 且 预算调整后非空'
WITH g AS (SELECT r.material_no, row_number() OVER (ORDER BY r.material_no) pos, c.quote_adjusted FROM material_price_review r
   LEFT JOIN material_price_review_column c ON c.review_id=r.id AND c.column_id='col-default'
   WHERE r.version_id=:'prev' AND r.basis_quotation_id=(SELECT id FROM quotation WHERE quotation_number='QT-20260911-0842')),
a AS (SELECT li.product_part_no_snapshot m, count(*) lines, count(DISTINCT li.quotation_id) quotes, max(li.subtotal) sub FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id
   JOIN customer cu ON cu.id=q.customer_id WHERE cu.code='CUST-0004' AND (li.composite_type IS NULL OR li.composite_type<>'PART')
   AND q.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED') GROUP BY 1)
SELECT count(*) pool, min(pos), max(pos), count(*) FILTER (WHERE pos BETWEEN 700 AND 819) pool_700_819
  FROM g JOIN a ON a.m=g.material_no WHERE g.material_no LIKE 'PERF600-B%' AND a.sub=0 AND a.quotes=1 AND a.lines=1 AND g.quote_adjusted IS NOT NULL AND g.quote_adjusted<>0;
ROLLBACK;
