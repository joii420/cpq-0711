-- ============================================================
-- 重置料号版本管理状态 — 清理 B1.5 relabel 事务 bug 留下的污染
--
-- 适用场景:
--   B1.5 修复前 (commit cf5929d 之前) 多次导入触发的升版导致:
--     - mat_customer_part_mapping.current_version > 2000
--     - mat_part_version_log 多了 v2001+ 记录
--     - 但实际数据 mat_bom/mat_process/mat_fee/mat_plating_fee 的
--       part_version 仍 = 2000 (relabel 失败)
--   现在需要重置所有数据到 v2000 基线, 重新测试导入。
--
-- 用法:
--   psql -h <host> -U postgres -d cpq_db -f data/reset-part-version-pollution.sql
--   或在 PgAdmin / DBeaver 等工具中执行
--
-- 不入 Flyway: 此脚本是一次性清理, 放在 data/ 目录手工跑.
-- ============================================================

BEGIN;

-- ----- 1. 清理 mat_part_version_log 中 version > 2000 的记录 -----
DO $$
DECLARE
    v_log_deleted INT;
BEGIN
    DELETE FROM mat_part_version_log WHERE version > 2000;
    GET DIAGNOSTICS v_log_deleted = ROW_COUNT;
    RAISE NOTICE '清理 mat_part_version_log v>2000: 删除 % 行', v_log_deleted;
END $$;

-- ----- 2. 重置 mat_customer_part_mapping.current_version 到 2000 -----
DO $$
DECLARE
    v_mapping_updated INT;
BEGIN
    UPDATE mat_customer_part_mapping SET current_version = 2000 WHERE current_version != 2000;
    GET DIAGNOSTICS v_mapping_updated = ROW_COUNT;
    RAISE NOTICE '重置 mat_customer_part_mapping.current_version: 影响 % 行', v_mapping_updated;
END $$;

-- ----- 3. 重置 4 张 mat_* 表 part_version 到 2000 -----
DO $$
DECLARE
    v_count INT;
BEGIN
    UPDATE mat_bom SET part_version = 2000 WHERE part_version != 2000;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '重置 mat_bom.part_version: 影响 % 行', v_count;

    UPDATE mat_process SET part_version = 2000 WHERE part_version != 2000;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '重置 mat_process.part_version: 影响 % 行', v_count;

    UPDATE mat_fee SET part_version = 2000 WHERE part_version != 2000;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '重置 mat_fee.part_version: 影响 % 行', v_count;

    UPDATE mat_plating_fee SET part_version = 2000 WHERE part_version != 2000;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '重置 mat_plating_fee.part_version: 影响 % 行', v_count;
END $$;

-- ----- 4. 重置 quotation_line_item.part_version_locked 到 2000 -----
DO $$
DECLARE
    v_count INT;
BEGIN
    UPDATE quotation_line_item SET part_version_locked = 2000 WHERE part_version_locked != 2000;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '重置 quotation_line_item.part_version_locked: 影响 % 行', v_count;
END $$;

-- ----- 5. 校验输出 -----
DO $$
DECLARE
    v_mapping_max INT;
    v_log_max INT;
    v_bom_versions TEXT;
BEGIN
    SELECT COALESCE(MAX(current_version), 0) INTO v_mapping_max FROM mat_customer_part_mapping;
    SELECT COALESCE(MAX(version), 0) INTO v_log_max FROM mat_part_version_log;
    SELECT string_agg(DISTINCT part_version::text, ',' ORDER BY part_version::text) INTO v_bom_versions FROM mat_bom;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '重置完成 — 应全部为 2000:';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '  mapping.current_version 最大值: %', v_mapping_max;
    RAISE NOTICE '  mat_part_version_log.version 最大值: %', v_log_max;
    RAISE NOTICE '  mat_bom 涉及的 part_version 集合: %', v_bom_versions;
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '若上述均为 2000, 可重新测试导入';
END $$;

COMMIT;
