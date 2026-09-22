-- task-260920 S-2 · Q-2 第三条路：预算 quote_adjusted 与依据行当前 subtotal 不同（升版能「看得出生效」）的料号，按活单面排序（只读）
BEGIN READ ONLY;
SELECT now() AS sample_utc;
WITH r AS (
  SELECT r.material_no, r.basis_quotation_id, c.quote_current, c.quote_adjusted
    FROM material_price_review r JOIN material_price_review_column c ON c.review_id=r.id AND c.column_id='col-default'
   WHERE r.version_id='0827e9b6-879f-470a-a19c-26d6f0b4e0a2' AND c.quote_adjusted IS NOT NULL),
b AS (
  SELECT DISTINCT ON (r.material_no) r.*, q.quotation_number basis, li.subtotal
    FROM r JOIN quotation q ON q.id=r.basis_quotation_id
    JOIN quotation_line_item li ON li.quotation_id=q.id AND li.product_part_no_snapshot=r.material_no AND (li.composite_type IS NULL OR li.composite_type<>'PART')
   ORDER BY r.material_no, li.sort_order ASC NULLS LAST, li.id ASC),
a AS (
  SELECT li.product_part_no_snapshot m, count(*) lines, count(DISTINCT li.quotation_id) quotes
    FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id JOIN customer cu ON cu.id=q.customer_id
   WHERE cu.code='CUST-0004' AND (li.composite_type IS NULL OR li.composite_type<>'PART')
     AND q.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED') GROUP BY 1)
SELECT b.basis, count(*) AS materials_differ, min(a.quotes) min_quotes, max(a.quotes) max_quotes,
       (array_agg(b.material_no || ' q=' || a.quotes || ' l=' || a.lines || ' sub=' || round(b.subtotal,4) || ' adj=' || round(b.quote_adjusted,4) ORDER BY a.quotes, b.material_no))[1:5] AS sample
  FROM b JOIN a ON a.m=b.material_no
 WHERE b.quote_adjusted IS DISTINCT FROM b.subtotal
 GROUP BY b.basis ORDER BY min_quotes, materials_differ DESC LIMIT 15;
ROLLBACK;
