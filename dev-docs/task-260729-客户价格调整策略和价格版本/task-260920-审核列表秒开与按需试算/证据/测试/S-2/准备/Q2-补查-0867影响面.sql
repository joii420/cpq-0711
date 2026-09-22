-- task-260920 S-2 · Q-2 补查：0867 四个料号的通过影响面（只读）
BEGIN READ ONLY;
SELECT now() AS sample_utc;
\echo '== 1. 每料号在正泰名下的活单数 / 行数（口径 = findAllActiveLines）+ E-6 各表备份行数'
WITH l AS (
  SELECT li.product_part_no_snapshot m, li.id lid, li.quotation_id qid FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id
    JOIN customer cu ON cu.id=q.customer_id
   WHERE cu.code='CUST-0004' AND li.product_part_no_snapshot IN ('S0001','S0004','S0008','S0012')
     AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
     AND q.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED'))
SELECT m, count(*) lines, count(DISTINCT qid) quotes,
  (SELECT count(*) FROM quotation_line_component_data cd WHERE cd.line_item_id IN (SELECT lid FROM l l2 WHERE l2.m=l.m)) comp_rows,
  (SELECT count(*) FROM quotation_price_revision r WHERE r.quotation_id IN (SELECT qid FROM l l2 WHERE l2.m=l.m)) revisions,
  (SELECT count(*) FROM ds_quote_material_bom_record r WHERE r.quotation_id IN (SELECT qid FROM l l2 WHERE l2.m=l.m)) mbom,
  (SELECT count(*) FROM ds_quote_element_bom_record r WHERE r.quotation_id IN (SELECT qid FROM l l2 WHERE l2.m=l.m)) ebom,
  (SELECT count(*) FROM quotation_view_structure s WHERE s.quotation_id IN (SELECT qid FROM l l2 WHERE l2.m=l.m)) vs,
  (SELECT count(*) FROM quotation q WHERE q.id IN (SELECT qid FROM l l2 WHERE l2.m=l.m) AND NOT EXISTS (SELECT 1 FROM quotation_price_revision r WHERE r.quotation_id=q.id)) quotes_without_revision
 FROM l GROUP BY m ORDER BY m;
\echo '== 2. 两两组合的并集影响面（单数 / 行数），按单数升序'
WITH l AS (
  SELECT li.product_part_no_snapshot m, li.id lid, li.quotation_id qid FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id
    JOIN customer cu ON cu.id=q.customer_id
   WHERE cu.code='CUST-0004' AND li.product_part_no_snapshot IN ('S0001','S0004','S0008','S0012')
     AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
     AND q.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED')),
p AS (SELECT a.m m1, b.m m2 FROM (SELECT DISTINCT m FROM l) a JOIN (SELECT DISTINCT m FROM l) b ON a.m < b.m)
SELECT m1, m2, (SELECT count(DISTINCT qid) FROM l WHERE m IN (m1,m2)) quotes, (SELECT count(*) FROM l WHERE m IN (m1,m2)) lines,
  (SELECT count(*) FROM quotation_line_component_data cd WHERE cd.line_item_id IN (SELECT lid FROM l WHERE m IN (m1,m2))) comp_rows
 FROM p ORDER BY 3, 4;
\echo '== 3. 活单按状态 / 业务员 / 创建日期分布（判断是否测试单）'
WITH l AS (
  SELECT DISTINCT li.quotation_id qid FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id JOIN customer cu ON cu.id=q.customer_id
   WHERE cu.code='CUST-0004' AND li.product_part_no_snapshot IN ('S0001','S0004','S0008','S0012')
     AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
     AND q.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED'))
SELECT q.status, coalesce(u.username, q.sales_rep_id::text, '(空)') sales_rep, q.created_at::date d, count(*) quotes,
       string_agg(q.quotation_number, ',' ORDER BY q.quotation_number) nos, min(q.name) sample_name
  FROM quotation q JOIN l ON l.qid=q.id LEFT JOIN "user" u ON u.id=q.sales_rep_id GROUP BY 1,2,3 ORDER BY 3,1;
\echo '== 4. 0867 组在 V26092101 的规模与派组次序（组按料号数降序）'
SELECT row_number() OVER (ORDER BY count(*) DESC, q.quotation_number) dispatch_rank, q.quotation_number, count(*) materials
  FROM material_price_review r JOIN quotation q ON q.id=r.basis_quotation_id
 WHERE r.version_id='0827e9b6-879f-470a-a19c-26d6f0b4e0a2' GROUP BY q.quotation_number ORDER BY 1 LIMIT 8;
\echo '== 5. 0867 组内次序'
SELECT r.material_no, row_number() OVER (ORDER BY r.material_no) pos FROM material_price_review r JOIN quotation q ON q.id=r.basis_quotation_id
 WHERE r.version_id='0827e9b6-879f-470a-a19c-26d6f0b4e0a2' AND q.quotation_number='QT-20260914-0867';
ROLLBACK;
