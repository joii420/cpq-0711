-- ============================================================
-- V155: quotation_line_item 加 part_version_locked
--
-- 设计背景:
--   报价单锁版本 — 已发布报价单不被新导入数据影响 (用户决策 #3).
--   每行 quotation_line_item 锁定它创建时刻使用的料号版本号.
--
-- 用户决策 #3 (回填规则 a):
--   所有现有 quotation_line_item 行 part_version_locked = 2000 (基线版本)
--
-- 用法 (S5 阶段):
--   - 创建报价单时: 拷贝 mat_customer_part_mapping.current_version → part_version_locked
--   - 草稿期: UI 提供版本选择器, 用户可改 part_version_locked (从历史版本里选)
--   - 已发布后: 锁死, 任何升版不影响
--   - 数据路径求值时: 显式传递此版本号给视图 / SQL 函数 current_part_version()
--
-- 不修改:
--   - quotation_line_item_snapshot (V148 字段级快照, 与版本锁正交)
-- ============================================================

ALTER TABLE quotation_line_item
    ADD COLUMN IF NOT EXISTS part_version_locked INT NOT NULL DEFAULT 2000;

COMMENT ON COLUMN quotation_line_item.part_version_locked IS '料号版本锁定: 该行报价使用的 (customer_product_no, hf_part_no) 版本号. 创建时从 mat_customer_part_mapping.current_version 拷贝, 已发布后锁死.';

-- ----- 校验输出 -----
DO $$
DECLARE
    v_total INT;
    v_default INT;
BEGIN
    SELECT COUNT(*) INTO v_total FROM quotation_line_item;
    SELECT COUNT(*) INTO v_default FROM quotation_line_item WHERE part_version_locked = 2000;

    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE 'V155 完成';
    RAISE NOTICE '════════════════════════════════════════════';
    RAISE NOTICE '  quotation_line_item 总行: %', v_total;
    RAISE NOTICE '  part_version_locked = 2000: %', v_default;
    RAISE NOTICE '════════════════════════════════════════════';
END $$;
