-- task-260920 B-6 · AC-13 并行正确性判据（只读；每条都是 SELECT / \copy TO，不写库）
-- 用法（psql 变量）：
--   psql -h <host> -U postgres -d <db> -v v=<version_id> \
--        -v quotes='{QT-20260908-0628,QT-20260909-0629,QT-20260911-0842}' \
--        -v out=/path/AC-13-<轮次>-逐行导出.csv -f AC-13-并行正确性脚本.sql
-- 🔒 AC-13 ②：串行参照必须与「R3 之前导出的」逐行文件比，🚫 与库里当前值比（重算会覆盖它们）。

\echo '== ① 逐行导出（含审核行 updated_at（比对列表无该列，取所属审核行的）；排序键 material_no, column_id）'
\o :out
COPY (SELECT r.material_no, c.column_id, c.quote_current, c.quote_adjusted, c.costing_current, c.costing_adjusted,
             c.diff_adjusted, r.updated_at AS review_updated_at
        FROM material_price_review r JOIN material_price_review_column c ON c.review_id = r.id
       WHERE r.version_id = :'v' ORDER BY r.material_no, c.column_id) TO STDOUT WITH CSV HEADER;
\o

\echo '== ② 聚合哈希（不含 updated_at；NULL 显式写成 ∅，numeric 用 ::text 保留全部位数）'
SELECT count(*) AS rows,
       md5(string_agg(concat_ws('|', r.material_no, c.column_id,
               coalesce(c.quote_current::text, '∅'), coalesce(c.quote_adjusted::text, '∅'),
               coalesce(c.costing_current::text, '∅'), coalesce(c.costing_adjusted::text, '∅'),
               coalesce(c.diff_adjusted::text, '∅')),
           E'\n' ORDER BY r.material_no, c.column_id)) AS hash
  FROM material_price_review r JOIN material_price_review_column c ON c.review_id = r.id
 WHERE r.version_id = :'v';

\echo '== ②b 审核行级状态分布（辅助：READY/FAILED 计数、非空值行数）'
SELECT r.budget_status, count(*) AS n,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM material_price_review_column c
                                      WHERE c.review_id = r.id AND c.quote_adjusted IS NOT NULL)) AS with_quote_adjusted
  FROM material_price_review r WHERE r.version_id = :'v' GROUP BY r.budget_status ORDER BY 1;

\echo '== ③ 输入指纹 a：目标版本元素价「按内容」（元素 / 价格 / 币种，不含版本 id）'
SELECT count(*) AS items,
       md5(string_agg(concat_ws('|', element_code, coalesce(current_price::text, '∅'), coalesce(currency, '∅')),
           E'\n' ORDER BY element_code)) AS element_price_fp
  FROM element_price_version_item WHERE version_id = :'v';

\echo '== ③ 输入指纹 b：点名大单全部行 quote_card_values'
SELECT q.quotation_number, count(li.*) AS lines,
       md5(string_agg(li.id::text || '|' || coalesce(li.quote_card_values::text, '∅'), E'\n' ORDER BY li.id)) AS card_fp
  FROM quotation q JOIN quotation_line_item li ON li.quotation_id = q.id
 WHERE q.quotation_number = ANY(CAST(:'quotes' AS text[]))
 GROUP BY q.quotation_number ORDER BY 1;

\echo '== ③ 输入指纹 c：「进池料号」（本版本有审核行者）的版本指针；🚫 不纳入不进池料号（每轮都会被推到本轮版本）'
SELECT count(*) AS pooled,
       md5(string_agg(r.material_no || '|' || coalesce(f.version_id::text, '∅'), E'\n' ORDER BY r.material_no)) AS pointer_fp
  FROM material_price_review r
  LEFT JOIN material_price_version_ref f ON f.customer_no = r.customer_no AND f.material_no = r.material_no
 WHERE r.version_id = :'v';
-- ⚠️ 指纹 c 须在「生成版本之前」对上一轮的进池集合取一次（生成后指针已变），两轮比的是同一时点口径。

\echo '== ④ 阳性对照：同一哈希口径，只把排序后第 1 行的 quote_adjusted 改一个值（+0.000000000001 或 ∅→0），哈希必须与 ② 不同'
WITH x AS (
  SELECT r.material_no, c.column_id, c.quote_current, c.quote_adjusted, c.costing_current, c.costing_adjusted, c.diff_adjusted,
         row_number() OVER (ORDER BY r.material_no, c.column_id) AS rn
    FROM material_price_review r JOIN material_price_review_column c ON c.review_id = r.id
   WHERE r.version_id = :'v'
)
SELECT md5(string_agg(concat_ws('|', material_no, column_id,
               coalesce(quote_current::text, '∅'),
               CASE WHEN rn = 1 THEN coalesce((quote_adjusted + 0.000000000001)::text, '0') ELSE coalesce(quote_adjusted::text, '∅') END,
               coalesce(costing_current::text, '∅'), coalesce(costing_adjusted::text, '∅'),
               coalesce(diff_adjusted::text, '∅')),
           E'\n' ORDER BY material_no, column_id)) AS tampered_hash
  FROM x;
