-- =================================================================
-- 内网报价单导入后内容全 "—" 故障诊断 (只读, 无副作用)
-- 用法: psql -h <内网DB> -U postgres -d cpq_db -f diagnose-empty-quotation.sql
-- 重点: 每个 SECTION 输出与"预期值"对比, 不一致即定位到根因
-- =================================================================

\echo '========== 1. 视图依赖的基础表行数 =========='
SELECT 'mat_customer_part_mapping' AS tbl, COUNT(*) AS cnt FROM mat_customer_part_mapping
UNION ALL SELECT 'mat_part', COUNT(*) FROM mat_part
UNION ALL SELECT 'exchange_rate', COUNT(*) FROM exchange_rate
UNION ALL SELECT 'mat_fee', COUNT(*) FROM mat_fee
UNION ALL SELECT 'quotation', COUNT(*) FROM quotation
UNION ALL SELECT 'quotation_line_item', COUNT(*) FROM quotation_line_item
UNION ALL SELECT 'quotation_line_component_data', COUNT(*) FROM quotation_line_component_data;
-- 预期: mapping >= 3, mat_part >= 40, exchange_rate 表必须存在(可以0行)

\echo ''
\echo '========== 2. 该零件号(3120012574) 视图查询效果 =========='
SELECT * FROM v_q_part_info_merged WHERE hf_part_no = '3120012574';
-- 预期: 1 行, customer_part_name='New Tools-11 Ref Approved'
-- 实际 0 行 → mat_customer_part_mapping 缺该记录或字段不一致

\echo ''
\echo '========== 3. mat_customer_part_mapping 该零件号原始记录 =========='
SELECT id, customer_id, hf_part_no, customer_part_name, customer_product_no, base_currency, quote_currency
FROM mat_customer_part_mapping WHERE hf_part_no = '3120012574';
-- 预期: 1 行, customer_id 为 UUID

\echo ''
\echo '========== 4. 该报价单的 customer_id 与 mapping 是否一致 =========='
SELECT q.id AS quotation_id, q.quotation_number,
       q.customer_id AS q_customer_id,
       m.customer_id AS m_customer_id,
       (q.customer_id = m.customer_id) AS customer_match
FROM quotation q
LEFT JOIN quotation_line_item qli ON qli.quotation_id = q.id
LEFT JOIN mat_customer_part_mapping m ON m.hf_part_no = qli.product_part_no_snapshot
WHERE qli.product_part_no_snapshot = '3120012574'
LIMIT 5;
-- 预期: customer_match = t. 是 f → quotation.customer_id 与 mapping.customer_id 不一致

\echo ''
\echo '========== 5. 视图是否完整(38 个) =========='
SELECT COUNT(*) AS view_count FROM information_schema.views WHERE table_schema = 'public';
-- 预期: 38

\echo ''
\echo '========== 6. 关键 component / template 配置是否同步 =========='
SELECT 'component' AS tbl, COUNT(*) AS cnt FROM component
UNION ALL SELECT 'component_directory', COUNT(*) FROM component_directory
UNION ALL SELECT 'template', COUNT(*) FROM template
UNION ALL SELECT 'template_component', COUNT(*) FROM template_component
UNION ALL SELECT 'basic_data_config', COUNT(*) FROM basic_data_config
UNION ALL SELECT 'excel_view_config', COUNT(*) FROM excel_view_config;
-- 预期: component=55, template=27, template_component=129, basic_data_config>0, excel_view_config>0

\echo ''
\echo '========== 7. mat_customer_part_mapping 表结构 =========='
SELECT column_name, data_type FROM information_schema.columns
WHERE table_schema='public' AND table_name='mat_customer_part_mapping' ORDER BY ordinal_position;

\echo ''
\echo '========== 8. Flyway 版本 =========='
SELECT MAX(installed_rank) AS rank, MAX(version) AS version FROM flyway_schema_history WHERE success;
-- 预期: version >= 148
