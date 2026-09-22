-- task-260920 S-2 · Q-2 ①：0842 值为何不随 Ag 变（只读）
BEGIN READ ONLY;
SELECT now() AS sample_utc;
\echo '== 正泰策略参与元素'
SELECT e.* FROM customer_price_adjust_element e JOIN customer_price_adjust_strategy s ON s.id=e.strategy_id WHERE s.customer_no='CUST-0004';
\echo '== 正泰元素取价策略（系数 / 加价）'
SELECT id, customer_no, element_code, source_id, method, factor, premium, status, updated_at FROM element_price_strategy WHERE customer_no='CUST-0004';
\echo '== 各版本客户 Ag 价'
SELECT v.version_no, v.trigger_type, i.element_code, i.current_price, i.previous_price, i.change_rate FROM element_price_version_item i JOIN element_price_version v ON v.id=i.version_id WHERE v.customer_no='CUST-0004' ORDER BY v.created_at;
\echo '== PERFHOT-B00291 @0842 材质元素页签（Ag 用量 / 锁定单价）'
SELECT cd.tab_name, cd.row_data::text FROM quotation_line_component_data cd JOIN quotation_line_item li ON li.id=cd.line_item_id JOIN quotation q ON q.id=li.quotation_id
 WHERE q.quotation_number='QT-20260911-0842' AND li.product_part_no_snapshot='PERFHOT-B00291' AND cd.tab_name='材质元素';
\echo '== 自然对照：各版本上代表料号的 现 / 调整后'
SELECT v.version_no, i.current_price ag, r.material_no, r.status, c.quote_current, c.quote_adjusted
  FROM material_price_review r JOIN element_price_version v ON v.id=r.version_id JOIN element_price_version_item i ON i.version_id=v.id AND i.element_code='Ag'
  LEFT JOIN material_price_review_column c ON c.review_id=r.id
 WHERE r.material_no IN ('PERFHOT-B00291','PERF600-B00230','S0001','S0004') AND v.customer_no='CUST-0004' ORDER BY r.material_no, v.created_at;
\echo '== 0842 组按版本：两值都非空数 / 现≠调整后数'
SELECT v.version_no, count(*) n, count(*) FILTER (WHERE c.quote_current IS NOT NULL AND c.quote_adjusted IS NOT NULL) both_nonnull,
       count(*) FILTER (WHERE c.quote_current IS DISTINCT FROM c.quote_adjusted AND c.quote_current IS NOT NULL) differ
  FROM material_price_review r JOIN element_price_version v ON v.id=r.version_id JOIN quotation q ON q.id=r.basis_quotation_id
  LEFT JOIN material_price_review_column c ON c.review_id=r.id
 WHERE v.customer_no='CUST-0004' AND q.quotation_number='QT-20260911-0842' GROUP BY v.version_no, v.created_at ORDER BY v.created_at;
ROLLBACK;
