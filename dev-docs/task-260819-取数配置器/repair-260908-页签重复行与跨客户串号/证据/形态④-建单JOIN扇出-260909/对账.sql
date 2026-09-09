-- repair-260908 · 缺陷① 形态④：DatasetQuotationCommitService.CANDIDATE_SQL 的 JOIN 扇出
-- 库：10.177.152.12:5432/cpq_db_0724（默认 profile）
-- 用法：PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -f 对账.sql

\echo '=== ① 正向：QT-20260909-0631 的批次（4 个客户产品编号）修前 8 行 / 修后 4 行 ==='
SELECT '修前' AS 口径, count(*) FROM ds_quote_customer_part cp
  LEFT JOIN ds_quote_material m ON m.material_no = cp.material_no
 WHERE cp.customer_no='CUST-0004' AND cp.customer_product_no = ANY(ARRAY['A002','RW-A004','TC-B008','ZT-C012'])
UNION ALL
SELECT '修后', count(*) FROM ds_quote_customer_part cp
  LEFT JOIN ds_quote_material m ON m.material_no = cp.material_no AND m.customer_no = cp.customer_no
 WHERE cp.customer_no='CUST-0004' AND cp.customer_product_no = ANY(ARRAY['A002','RW-A004','TC-B008','ZT-C012']);

\echo '=== ② 反向 A（不误伤）：只在单客户下存在的料号，修前修后必须一致 ==='
SELECT '修前' AS 口径, count(*) FROM ds_quote_customer_part cp
  LEFT JOIN ds_quote_material m ON m.material_no = cp.material_no
 WHERE cp.customer_no='CUST-0004' AND cp.customer_product_no = ANY(ARRAY['ZT600-00001','ZT600-00002','ZT600-00003','ZT600-00004'])
UNION ALL
SELECT '修后', count(*) FROM ds_quote_customer_part cp
  LEFT JOIN ds_quote_material m ON m.material_no = cp.material_no AND m.customer_no = cp.customer_no
 WHERE cp.customer_no='CUST-0004' AND cp.customer_product_no = ANY(ARRAY['ZT600-00001','ZT600-00002','ZT600-00003','ZT600-00004']);

\echo '=== ③ 反向 B（不丢行）：全 CUST-0004。修后必须恰好等于客户料号基数（LEFT JOIN 1:1） ==='
SELECT
 (SELECT count(*) FROM ds_quote_customer_part cp WHERE cp.customer_no='CUST-0004') AS 基数_客户料号行,
 (SELECT count(*) FROM ds_quote_customer_part cp LEFT JOIN ds_quote_material m ON m.material_no=cp.material_no WHERE cp.customer_no='CUST-0004') AS 修前,
 (SELECT count(*) FROM ds_quote_customer_part cp LEFT JOIN ds_quote_material m ON m.material_no=cp.material_no AND m.customer_no=cp.customer_no WHERE cp.customer_no='CUST-0004') AS 修后;

\echo '=== ④ 存量影响面（只读，不清理）==='
WITH ir AS (
  SELECT r.quotation_id, (SELECT c.code FROM customer c WHERE c.id=r.customer_id) AS cust,
         ARRAY(SELECT jsonb_array_elements(r.metadata::jsonb->'batchParts')->>'customerProductNo') AS pns
    FROM import_record r
   WHERE r.system_type='DATASET_QUOTE' AND r.quotation_id IS NOT NULL AND (r.metadata::jsonb ? 'batchParts')
)
SELECT q.quotation_number, q.status,
  (SELECT count(*) FROM ds_quote_customer_part cp LEFT JOIN ds_quote_material m ON m.material_no=cp.material_no
    WHERE cp.customer_no=ir.cust AND cp.customer_product_no=ANY(ir.pns)) AS 修前候选,
  (SELECT count(*) FROM ds_quote_customer_part cp LEFT JOIN ds_quote_material m ON m.material_no=cp.material_no AND m.customer_no=cp.customer_no
    WHERE cp.customer_no=ir.cust AND cp.customer_product_no=ANY(ir.pns)) AS 修后候选,
  (SELECT count(*) FROM quotation_line_item li WHERE li.quotation_id=q.id) AS 实际明细行
 FROM ir JOIN quotation q ON q.id=ir.quotation_id ORDER BY 1;
