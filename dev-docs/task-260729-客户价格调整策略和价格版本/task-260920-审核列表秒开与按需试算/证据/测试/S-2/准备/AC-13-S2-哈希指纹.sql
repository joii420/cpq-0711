-- task-260920 S-2 · AC-13 哈希 / 指纹 / 逐行导出（S-2 审改版；原件 证据/AC-13-并行正确性脚本.sql 未改动）
-- 全程只读：BEGIN READ ONLY … ROLLBACK；COPY 只 TO STDOUT。
-- 用法：
--   psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -X \
--     -v v=<version_id> -v quotes='{QT-20260908-0628,QT-20260909-0629,QT-20260911-0842}' \
--     -v out=<导出文件.csv> -f AC-13-S2-哈希指纹.sql
-- 相对原件的改动（理由见 派工回报/测试S2-准备-v1.md §3.1）：
--   R1 导出与哈希改 LEFT JOIN + 带 budget_status：原件 INNER JOIN 会让「0 列的行」（FAILED / 反例外 READY+0 列）从哈希里整行消失；
--   R2 原件 quotes 口径正确（0629 = QT-20260909-0629），派工 prompt 写的 QT-20260908-0629 在库里不存在；
--   R3 指纹 b 之外补 b2：quotation_line_component_data 的 snapshot_rows / row_data（dryRun 的真实输入，只比 quote_card_values 不够）；
--   R4 指纹 c 之外补 c0：进池集合本身的哈希（集合不同则指针哈希必不同，但单独列出便于定位）；
--   R5 哈希 ② 同时输出「由导出文件重算」的口径说明，阳性对照在导出副本上做（ac13_tool.py），库内 ④ 保留作第二道对照。
BEGIN READ ONLY;
SELECT now() AS sample_utc, :'v' AS version_id;

\echo '== ① 逐行导出（审核行 LEFT JOIN 比对列；排序 material_no, column_id NULLS FIRST）'
\o :out
COPY (SELECT r.material_no, coalesce(c.column_id, '') AS column_id, r.budget_status,
             c.quote_current, c.quote_adjusted, c.costing_current, c.costing_adjusted, c.diff_adjusted,
             r.updated_at AS review_updated_at
        FROM material_price_review r LEFT JOIN material_price_review_column c ON c.review_id = r.id
       WHERE r.version_id = :'v'
       ORDER BY r.material_no, c.column_id NULLS FIRST) TO STDOUT WITH CSV HEADER;
\o

\echo '== ② 聚合哈希 H（不含 updated_at；NULL 写成 ∅；numeric::text 保留全部位数；行序同导出）'
SELECT count(*) AS rows,
       count(*) FILTER (WHERE c.quote_adjusted IS NOT NULL) AS rows_quote_adjusted_nonnull,
       md5(string_agg(concat_ws('|', r.material_no, coalesce(c.column_id, ''), r.budget_status,
               coalesce(c.quote_current::text, '∅'), coalesce(c.quote_adjusted::text, '∅'),
               coalesce(c.costing_current::text, '∅'), coalesce(c.costing_adjusted::text, '∅'),
               coalesce(c.diff_adjusted::text, '∅')),
           E'\n' ORDER BY r.material_no, c.column_id NULLS FIRST)) AS hash
  FROM material_price_review r LEFT JOIN material_price_review_column c ON c.review_id = r.id
 WHERE r.version_id = :'v';

\echo '== ②b 状态分布（辅助；AC-12 口径另见 R1 脚本）'
SELECT r.status, r.budget_status, count(*) AS n FROM material_price_review r WHERE r.version_id = :'v' GROUP BY 1,2 ORDER BY 1,2;

\echo '== ③a 输入指纹：目标版本元素价「按内容」（元素 / 价格 / 币种；不含版本 id）'
SELECT count(*) AS items,
       md5(string_agg(concat_ws('|', element_code, coalesce(current_price::text, '∅'), coalesce(currency, '∅')),
           E'\n' ORDER BY element_code)) AS element_price_fp
  FROM element_price_version_item WHERE version_id = :'v';

\echo '== ③b 输入指纹：三张大单全部行 quote_card_values + subtotal'
SELECT q.quotation_number, count(li.*) AS lines,
       md5(string_agg(li.id::text || '|' || coalesce(li.quote_card_values::text, '∅') || '|' || coalesce(li.subtotal::text, '∅'),
           E'\n' ORDER BY li.id)) AS card_fp
  FROM quotation q JOIN quotation_line_item li ON li.quotation_id = q.id
 WHERE q.quotation_number = ANY(CAST(:'quotes' AS text[]))
 GROUP BY q.quotation_number ORDER BY 1;

\echo '== ③b2 输入指纹：三张大单 quotation_line_component_data 的 snapshot_rows / row_data / row_version'
SELECT q.quotation_number, count(cd.*) AS comp_rows,
       md5(string_agg(cd.id::text || '|' || coalesce(cd.snapshot_rows::text, '∅') || '|' || coalesce(cd.row_data::text, '∅')
                      || '|' || cd.row_version::text, E'\n' ORDER BY cd.id)) AS comp_fp
  FROM quotation q JOIN quotation_line_item li ON li.quotation_id = q.id
  JOIN quotation_line_component_data cd ON cd.line_item_id = li.id
 WHERE q.quotation_number = ANY(CAST(:'quotes' AS text[]))
 GROUP BY q.quotation_number ORDER BY 1;

\echo '== ③c0 进池集合（本版本有审核行的料号）'
SELECT count(*) AS pooled, md5(string_agg(material_no, E'\n' ORDER BY material_no)) AS pooled_set_fp
  FROM material_price_review WHERE version_id = :'v';

\echo '== ③c 进池料号的版本指针（只比进池料号；不进池料号每轮都会被推到本轮版本，按定义不同）'
SELECT count(*) AS pooled,
       md5(string_agg(r.material_no || '|' || coalesce(f.version_id::text, '∅'), E'\n' ORDER BY r.material_no)) AS pointer_fp
  FROM material_price_review r
  LEFT JOIN material_price_version_ref f ON f.customer_no = r.customer_no AND f.material_no = r.material_no
 WHERE r.version_id = :'v';

\echo '== ④ 库内阳性对照（第二道；第一道在导出副本上做）：第 1 行 quote_adjusted +1e-12 / ∅→0'
WITH x AS (
  SELECT r.material_no, coalesce(c.column_id, '') AS column_id, r.budget_status, c.quote_current, c.quote_adjusted,
         c.costing_current, c.costing_adjusted, c.diff_adjusted,
         row_number() OVER (ORDER BY r.material_no, c.column_id NULLS FIRST) AS rn
    FROM material_price_review r LEFT JOIN material_price_review_column c ON c.review_id = r.id
   WHERE r.version_id = :'v')
SELECT md5(string_agg(concat_ws('|', material_no, column_id, budget_status,
               coalesce(quote_current::text, '∅'),
               CASE WHEN rn = 1 THEN coalesce((quote_adjusted + 0.000000000001)::text, '0') ELSE coalesce(quote_adjusted::text, '∅') END,
               coalesce(costing_current::text, '∅'), coalesce(costing_adjusted::text, '∅'), coalesce(diff_adjusted::text, '∅')),
           E'\n' ORDER BY rn)) AS tampered_hash
  FROM x;
ROLLBACK;
