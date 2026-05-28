-- ============================================================
-- V259: BnfPathLinter + SqlViewValidator 扩展 V76 关键词（Java 代码改动占位）
--
-- 本脚本自身为 NOOP，仅输出 NOTICE 留痕。
-- 实际改动在：
--   BnfPathLinter.java    — DEPRECATED_TABLE_PREFIXES 加 9 个 V76 costing_part_* 关键词
--   SqlViewValidator.java — FORBIDDEN_TABLE_TOKENS 加同样 9 个关键词
-- 错误码：SQL_VIEW_DEPRECATED_TABLE，HTTP 400
-- 详见 docs/反模式.md AP-53 + 架构师 §7
-- ============================================================

DO $$
BEGIN
    RAISE NOTICE 'V259: BnfPathLinter + SqlViewValidator 已通过 Java 代码加入 V76 costing_part_* 废弃表关键词。'
                 '废弃词汇：costing_part_material_bom / costing_part_element_bom / '
                 'costing_part_process_cost / costing_part_plating / costing_part_plating_fee / '
                 'costing_part_tooling_cost / costing_part_weight / costing_part_quality_check / '
                 'costing_part_design_cost（共 9 个，错误码 SQL_VIEW_DEPRECATED_TABLE，AP-53）';
END $$;
