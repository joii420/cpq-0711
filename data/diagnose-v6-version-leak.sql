-- ============================================================
-- V6 导入 BUMP 后产品卡片显示两版数据 + 版本号 v2000 — 诊断脚本
-- 料号 hf=3120012580   客户产品号 cpn=4NEG5304200(用户截图)
-- 目的: 定位根因落在「mapping 没升版」/「line_item 没拿到新版」/
--       「mat_bom 同版本被写两次」/「V153 schema 未到位」哪一层
-- 用法: psql -h <host> -U postgres -d cpq_db -f data/diagnose-v6-version-leak.sql
-- 只读, 无 INSERT/UPDATE/DDL
-- ============================================================

\echo '========== ❶ V153 schema 是否到位 (mat_bom.part_version 列) =========='
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name='mat_bom' AND column_name='part_version';
-- 期望: 1 行, data_type=integer; 0 行 → V153 未跑, 这是根因#1

\echo '========== ❷ Flyway V152/V153 是否成功 =========='
SELECT version, success, installed_on
FROM flyway_schema_history
WHERE version IN ('152','153')
ORDER BY version;
-- 期望: 2 行, success=t; 缺行或 success=f → schema 未到位

\echo '========== ❸ mat_bom 该料号按 part_version × bom_type 统计 =========='
SELECT part_version, bom_type, count(*) AS rows
FROM mat_bom
WHERE hf_part_no='3120012580'
GROUP BY part_version, bom_type
ORDER BY part_version, bom_type;
-- 期望: 看到 (2000, ELEMENT, 4) + (2001, ELEMENT, 4) → BUMP 成功落表,
--      但卡片显示 8 行 → 后端 part_version 过滤没生效 (根因#2)
-- 若只有 (2000, ELEMENT, 8) → BUMP 没真升版, 新数据全压在 v2000 (根因#3)

\echo '========== ❹ mat_bom 详细行 (识别 Ag 80/20 vs 70/30 的版本归属) =========='
SELECT part_version, bom_type, seq_no, input_material_name, element_name,
       composition_pct, loss_rate, gross_qty
FROM mat_bom
WHERE hf_part_no='3120012580'
ORDER BY part_version, bom_type, seq_no;

\echo '========== ❺ mapping.current_version (applyVersionBump 后期望=2001) =========='
SELECT customer_product_no, hf_part_no, current_version, updated_at
FROM mat_customer_part_mapping
WHERE hf_part_no='3120012580';
-- 期望: current_version=2001; 若=2000 → applyVersionBump 未生效 (根因#4)

\echo '========== ❻ 最新 line_item.part_version_locked =========='
SELECT q.name AS quotation_name,
       li.product_part_no_snapshot,
       li.customer_part_no,
       li.part_version_locked,
       li.created_at
FROM quotation q
JOIN quotation_line_item li ON li.quotation_id = q.id
WHERE li.product_part_no_snapshot='3120012580'
ORDER BY li.created_at DESC
LIMIT 10;
-- 关键判定:
--   part_version_locked=2001 + customer_part_no 非空 → SaveDraft 正确读到新版
--     此时卡片仍显示 v2000 → 前端 applyQuotationData 没回填 / 缓存问题
--   part_version_locked=2000 + customer_part_no 非空 → mapping 查询失败或返了 2000
--   part_version_locked=2000 + customer_part_no=NULL → SaveDraft 跳过 mapping 查询 (根因#5)

\echo '========== ❼ mat_part_version_log (applyVersionBump 是否真写日志) =========='
SELECT version, source_excel, created_at
FROM mat_part_version_log
WHERE hf_part_no='3120012580'
ORDER BY version DESC;
-- 期望: 至少 1 行 version=2001; 0 行 → applyVersionBump 根本没跑到 (根因#4 加重)
