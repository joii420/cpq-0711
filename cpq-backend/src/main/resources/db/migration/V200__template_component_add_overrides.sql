-- V200: template_component 加 data_driver_path_override / fields_override 列
--
-- 背景 (2026-05-19, QT-20260519-1410/1412 排查总结):
--   `publish()` 流程每次都从 `component` 表 + `template_component` 表重建 snapshot,
--   V195/V198/V199 在 template.components_snapshot 字面写入的 v_composite_child_*
--   driver_path 覆盖会被 publish 一并抹掉. 派生新版本 + publish → snapshot 退化.
--
-- 架构债修复:
--   把"模板级的字段/驱动路径覆盖"显式落到 template_component 表上, 让 publish()
--   按 (component 基础) ⊕ (template_component override) 重建 snapshot.
--
-- 新列语义:
--   - data_driver_path_override TEXT (nullable): 非 NULL 时盖 component.dataDriverPath
--   - fields_override JSONB     (nullable): 非 NULL 时盖 component.fields
--   两列保持 NULL 时 publish 与原行为一致, 无破坏性.
--
-- 后续 V201 把 v1.0/v1.1/v1.2/v1.3 的"组合产品"4 个聚合 Tab 的覆盖落到这两列.

ALTER TABLE template_component
    ADD COLUMN IF NOT EXISTS data_driver_path_override TEXT,
    ADD COLUMN IF NOT EXISTS fields_override JSONB;

COMMENT ON COLUMN template_component.data_driver_path_override IS
    'V200: 模板级 driver_path 覆盖 (非 NULL 时 publish 时盖 component.dataDriverPath). 用于 COMPOSITE 模板把 mat_bom→v_composite_child_elements 等场景.';

COMMENT ON COLUMN template_component.fields_override IS
    'V200: 模板级 fields 覆盖 (非 NULL 时 publish 时盖 component.fields). 用于 COMPOSITE 模板把字段集换成聚合视图列(加 child_part_name 等).';

-- 自检
DO $$
DECLARE
    v_has_driver_col BOOLEAN;
    v_has_fields_col BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name='template_component' AND column_name='data_driver_path_override'
    ) INTO v_has_driver_col;
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name='template_component' AND column_name='fields_override'
    ) INTO v_has_fields_col;
    IF NOT (v_has_driver_col AND v_has_fields_col) THEN
        RAISE EXCEPTION 'V200 自检失败: 缺列 driver_col=% fields_col=%', v_has_driver_col, v_has_fields_col;
    END IF;
    RAISE NOTICE 'V200 自检通过: template_component 已加 data_driver_path_override + fields_override 两列';
END
$$;
