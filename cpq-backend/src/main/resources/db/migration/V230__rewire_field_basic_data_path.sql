-- ============================================================
-- V230: 字段级 basic_data_path 从 V44 直接视图名改为 SQL 视图引用
--
-- 触发: AP-53 老表禁用规则 — 字段级 BNF 路径同样禁用直接 PG 视图名
-- 替换:
--   v_composite_child_processes.<col>  → $composite_child_processes_mirror.<col>
--   v_composite_child_materials.<col>  → $composite_child_materials_mirror.<col>
--   v_composite_child_elements.<col>   → $composite_child_elements_mirror.<col>
--
-- 影响：component.fields JSONB + template.components_snapshot JSONB
-- ============================================================

-- ============== 1. component.fields ==============
UPDATE component
SET fields = REPLACE(REPLACE(REPLACE(
        fields::text,
        '"basic_data_path": "v_composite_child_processes.',
        '"basic_data_path": "$composite_child_processes_mirror.'
    ),
        '"basic_data_path": "v_composite_child_materials.',
        '"basic_data_path": "$composite_child_materials_mirror.'
    ),
        '"basic_data_path": "v_composite_child_elements.',
        '"basic_data_path": "$composite_child_elements_mirror.'
    )::jsonb,
    updated_at = NOW()
WHERE fields::text ~ '"basic_data_path": "v_composite_child_';

-- ============== 2. template.components_snapshot ==============
UPDATE template
SET components_snapshot = REPLACE(REPLACE(REPLACE(
        components_snapshot::text,
        '"basic_data_path": "v_composite_child_processes.',
        '"basic_data_path": "$composite_child_processes_mirror.'
    ),
        '"basic_data_path": "v_composite_child_materials.',
        '"basic_data_path": "$composite_child_materials_mirror.'
    ),
        '"basic_data_path": "v_composite_child_elements.',
        '"basic_data_path": "$composite_child_elements_mirror.'
    )::jsonb,
    updated_at = NOW()
WHERE components_snapshot::text ~ '"basic_data_path": "v_composite_child_';

DO $body$
DECLARE
    comp_old INT;
    tpl_old  INT;
BEGIN
    SELECT COUNT(*) INTO comp_old FROM component
        WHERE fields::text ~ '"basic_data_path": "v_composite_child_';
    SELECT COUNT(*) INTO tpl_old  FROM template
        WHERE components_snapshot::text ~ '"basic_data_path": "v_composite_child_';
    RAISE NOTICE 'V230 done: 组件 fields 仍含老 V44 BNF: % 个；模板 snapshot 仍含老 V44 BNF: % 张',
        comp_old, tpl_old;
END $body$;
