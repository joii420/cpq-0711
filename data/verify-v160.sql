-- ============================================================
-- V160 修复后验证 SQL — 必须全部通过才算修复成功
-- 报价单: QT-20260513-1423   料号: 3120012580
-- 用法: psql -h <host> -U postgres -d cpq_db -f data/verify-v160.sql
-- 只读, 无 INSERT/UPDATE/DDL
-- ============================================================

\echo '========== ❶ V160 已落库 =========='
SELECT version, success, installed_on
FROM flyway_schema_history
WHERE version = '160';
-- 期望: 1 行 success=t; 0 行 → Quarkus 没重启或 migration 漏跑

\echo '========== ❷ 6 视图均已含 part_version 列 =========='
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_name IN (
        'v_q_incoming_merged','v_q_element_merged','v_q_finished_merged',
        'v_q_component_merged','v_q_assembly_merged','v_q_plating_merged')
  AND column_name = 'part_version'
ORDER BY table_name;
-- 期望: 6 行

\echo '========== ❸ 视图按版本过滤行数 (修复关键证据) =========='
SELECT 'v_q_element_merged @ v=2000' AS scope, count(*) AS rows
FROM v_q_element_merged WHERE hf_part_no='3120012580' AND part_version=2000
UNION ALL
SELECT 'v_q_element_merged @ v=2001', count(*)
FROM v_q_element_merged WHERE hf_part_no='3120012580' AND part_version=2001
UNION ALL
SELECT 'v_q_element_merged 无过滤(对照)', count(*)
FROM v_q_element_merged WHERE hf_part_no='3120012580';
-- 期望:
--   v=2000 → 4 行 (单版本 v2000 数据)
--   v=2001 → 4 行 (单版本 v2001 数据)
--   无过滤 → 8 行 (对照, 显示叠加问题客观存在)

\echo '========== ❹ QT-20260513-1423 实际 part_version_locked =========='
SELECT q.code, q.name, q.status,
       li.product_part_no_snapshot,
       li.customer_part_no,
       li.part_version_locked,
       li.created_at,
       li.updated_at
FROM quotation q
JOIN quotation_line_item li ON li.quotation_id = q.id
WHERE q.code = 'QT-20260513-1423'
   OR q.name LIKE '%QT-20260513-1423%'
ORDER BY li.created_at DESC;
-- 关键判定:
--   part_version_locked = 2001 → 卡片应该显 v2001, 若仍显 v2000 → 第二个独立 Bug (前端层)
--   part_version_locked = 2000 → 卡片显 v2000 是正确的, 但数据混版需 V160 才解决
