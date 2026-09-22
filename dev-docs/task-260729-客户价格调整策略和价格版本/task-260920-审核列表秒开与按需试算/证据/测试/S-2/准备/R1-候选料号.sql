-- task-260920 S-2 · R1 界面用候选料号（只读）。
-- 口径：后台循环按依据单分组、料号多的组先派、组内按 material_no 升序（backtask B-3/B-5）。
-- pos_in_group = 该料号在组内的处理序号（1 起）；越大越靠后算到。
-- 基于「上一版」（生成 V1 前的待处理版本）已算完结果；参数 :prev = 上一版 version_id。
-- ⚠️ 新版本的进池集合/依据单以生成时为准，R1 生成后须用本文件第 3 段在新版本上复核一次。
BEGIN READ ONLY;
SELECT now() AS sample_utc;
\echo '== 1. 组规模（派组次序）'
SELECT q.quotation_number AS basis, count(*) AS materials
  FROM material_price_review r LEFT JOIN quotation q ON q.id = r.basis_quotation_id
 WHERE r.version_id = :'prev' GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 5;
\echo '== 2a. AC-1 / AC-5①：0628 组内最靠后的 10 个料号（上一版结果为缺数据）'
WITH g AS (
  SELECT r.material_no, q.quotation_number AS basis, c.status AS col_status, c.missing_side,
         row_number() OVER (PARTITION BY r.basis_quotation_id ORDER BY r.material_no) AS pos,
         count(*) OVER (PARTITION BY r.basis_quotation_id) AS grp
    FROM material_price_review r JOIN quotation q ON q.id = r.basis_quotation_id
    LEFT JOIN material_price_review_column c ON c.review_id = r.id AND c.column_id = 'col-default'
   WHERE r.version_id = :'prev')
SELECT * FROM g WHERE basis = 'QT-20260908-0628' ORDER BY pos DESC LIMIT 10;
\echo '== 2b. AC-5② / AC-6 / AC-21 / AC-8 / AC-15 / AC-26：0842 组内「报价·现」「报价·调整后」都非空的料号（按组内处理序号倒序）'
WITH g AS (
  SELECT r.material_no, q.quotation_number AS basis, c.quote_current, c.quote_adjusted, c.status AS col_status, c.missing_side,
         row_number() OVER (PARTITION BY r.basis_quotation_id ORDER BY r.material_no) AS pos,
         count(*) OVER (PARTITION BY r.basis_quotation_id) AS grp
    FROM material_price_review r JOIN quotation q ON q.id = r.basis_quotation_id
    LEFT JOIN material_price_review_column c ON c.review_id = r.id AND c.column_id = 'col-default'
   WHERE r.version_id = :'prev')
SELECT material_no, pos, grp, quote_current, quote_adjusted, col_status, missing_side
  FROM g WHERE basis = 'QT-20260911-0842' AND quote_current IS NOT NULL AND quote_adjusted IS NOT NULL ORDER BY pos DESC;
\echo '== 2c. 43 池里的小单料号（依据单不是三张大单，组小、先派但只占 1 个线程片刻）'
SELECT r.material_no, q.quotation_number AS basis, c.quote_current, c.quote_adjusted
  FROM material_price_review r JOIN quotation q ON q.id = r.basis_quotation_id
  JOIN material_price_review_column c ON c.review_id = r.id AND c.column_id = 'col-default'
 WHERE r.version_id = :'prev' AND c.quote_current IS NOT NULL AND c.quote_adjusted IS NOT NULL
   AND q.quotation_number NOT IN ('QT-20260908-0628','QT-20260909-0629','QT-20260911-0842')
 ORDER BY 2, 1;
\echo '== 2d. 每个 0842 候选料号在全部活单上的行数（通过会升版这些行 → E-6 备份面）'
SELECT li.product_part_no_snapshot AS material_no, count(*) AS active_lines, count(DISTINCT q.id) AS quotes,
       string_agg(DISTINCT q.quotation_number, ',') AS quote_nos
  FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id JOIN customer cu ON cu.id = q.customer_id
 WHERE cu.code = 'CUST-0004' AND (li.composite_type IS NULL OR li.composite_type <> 'PART')
   AND q.status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','COSTING_REJECTED')
   AND li.product_part_no_snapshot IN (
     SELECT r.material_no FROM material_price_review r JOIN quotation q2 ON q2.id = r.basis_quotation_id
       JOIN material_price_review_column c ON c.review_id = r.id AND c.column_id = 'col-default'
      WHERE r.version_id = :'prev' AND q2.quotation_number = 'QT-20260911-0842'
        AND c.quote_current IS NOT NULL AND c.quote_adjusted IS NOT NULL)
 GROUP BY 1 ORDER BY 1;
ROLLBACK;
