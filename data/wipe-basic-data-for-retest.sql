-- ============================================================
-- 清空报价基础数据 + 报价单快照 — 用于重新导入测试
--
-- 适用场景:
--   料号版本管理 B 方案测试中, 历史导入污染累积导致 line_item.componentData
--   JSONB 快照含多版本数据. 清空后重新导入, 让快照只含单一版本数据.
--
-- 清空范围 (按依赖顺序):
--   1. 报价单相关 (line_item 快照 + 主表)
--   2. 料号版本日志
--   3. 报价基础数据 (mat_* 6 张)
--   4. 物料主档 mat_part (有 FK 被引用, 最后删)
--
-- 保留 (不动):
--   - customer / contact / customer_tax / region / department
--   - product / component / template / costing_template
--   - costing_part_* (核价侧 9 张表, 与报价侧无 FK 耦合)
--   - 系统配置 / 用户 / 角色等
--
-- 用法:
--   psql -h <host> -U postgres -d cpq_db -f data/wipe-basic-data-for-retest.sql
--   或 PgAdmin / DBeaver 执行
--
-- 不入 Flyway: 一次性清空脚本.
-- ============================================================

BEGIN;

-- ----- 1. 清空报价单快照 -----
DO $$
DECLARE
    v_count INT;
BEGIN
    DELETE FROM quotation_line_component_data;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 quotation_line_component_data: % 行', v_count;

    DELETE FROM quotation_line_process;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 quotation_line_process: % 行', v_count;

    DELETE FROM quotation_line_item_snapshot;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 quotation_line_item_snapshot: % 行', v_count;

    DELETE FROM quotation_line_item;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 quotation_line_item: % 行', v_count;

    DELETE FROM quotation_approval;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 quotation_approval: % 行', v_count;

    DELETE FROM quotation;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 quotation: % 行', v_count;
END $$;

-- ----- 2. 清空料号版本日志 -----
DO $$
DECLARE
    v_count INT;
BEGIN
    DELETE FROM mat_part_version_log;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 mat_part_version_log: % 行', v_count;
END $$;

-- ----- 3. 清空报价基础数据 (mat_* 6 张, 按依赖顺序) -----
DO $$
DECLARE
    v_count INT;
BEGIN
    DELETE FROM mat_plating_fee;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 mat_plating_fee: % 行', v_count;

    DELETE FROM mat_plating_plan;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 mat_plating_plan: % 行', v_count;

    DELETE FROM mat_fee;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 mat_fee: % 行', v_count;

    DELETE FROM mat_process;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 mat_process: % 行', v_count;

    DELETE FROM mat_bom;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 mat_bom: % 行', v_count;

    DELETE FROM mat_customer_part_mapping;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 mat_customer_part_mapping: % 行', v_count;
END $$;

-- ----- 4. 清空物料主档 (有 FK 引用, 最后删) -----
DO $$
DECLARE
    v_count INT;
BEGIN
    DELETE FROM mat_part;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 mat_part: % 行', v_count;
END $$;

-- ----- 5. 清空导入记录 -----
DO $$
DECLARE
    v_count INT;
BEGIN
    DELETE FROM import_record;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE '清空 import_record: % 行', v_count;
END $$;

-- ----- 6. 校验输出 -----
DO $$
DECLARE
    v_mat_part INT;
    v_mat_bom INT;
    v_mapping INT;
    v_log INT;
    v_qli INT;
BEGIN
    SELECT COUNT(*) INTO v_mat_part FROM mat_part;
    SELECT COUNT(*) INTO v_mat_bom FROM mat_bom;
    SELECT COUNT(*) INTO v_mapping FROM mat_customer_part_mapping;
    SELECT COUNT(*) INTO v_log FROM mat_part_version_log;
    SELECT COUNT(*) INTO v_qli FROM quotation_line_item;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '清空完成 — 期望全部为 0:';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '  mat_part: % 行', v_mat_part;
    RAISE NOTICE '  mat_bom: % 行', v_mat_bom;
    RAISE NOTICE '  mat_customer_part_mapping: % 行', v_mapping;
    RAISE NOTICE '  mat_part_version_log: % 行', v_log;
    RAISE NOTICE '  quotation_line_item: % 行', v_qli;
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '现在可以重新导入 Excel 测试版本管理流程';
END $$;

COMMIT;
