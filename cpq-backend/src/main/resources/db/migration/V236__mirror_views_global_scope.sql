-- ============================================================
-- V236: 4 个 mirror 视图 scope 改 GLOBAL + CHILD-PARTS 用跨组件引用
--
-- 触发: CHILD-PARTS 组件 driver path 用 $composite_child_materials_mirror 但
--       该 mirror 视图 scope=COMPONENT 绑在 MATERIAL_RECIPE 组件上，
--       CHILD-PARTS 跨组件访问报"本组件 SQL 视图未找到"。
--
-- 修复: 把 4 个 mirror 视图 scope 改 GLOBAL（任意组件可引用），
--       CHILD-PARTS driver path 用 $$<componentCode>.<view> 跨组件语法。
-- ============================================================

-- 1. 4 个 mirror 视图 scope 改 GLOBAL
UPDATE component_sql_view
SET scope = 'GLOBAL', updated_at = NOW()
WHERE sql_view_name IN (
    'composite_child_processes_mirror',
    'composite_child_materials_mirror',
    'composite_child_elements_mirror',
    'composite_child_weights_mirror'
);

-- 2. CHILD-PARTS 改用跨组件引用 (MATERIAL_RECIPE 组件 code=COMP-CFG-MATERIAL-RECIPE)
UPDATE component
SET data_driver_path = '$$COMP-CFG-MATERIAL-RECIPE.composite_child_materials_mirror',
    updated_at = NOW()
WHERE code = 'COMP-CFG-CHILD-PARTS';

-- 3. template snapshot 同步
UPDATE template
SET components_snapshot = REPLACE(
        components_snapshot::text,
        '"data_driver_path": "$composite_child_materials_mirror"',
        '"data_driver_path": "__PLACEHOLDER_MAT__"'
    )::jsonb,
    updated_at = NOW()
WHERE components_snapshot::text LIKE '%"componentCode": "COMP-CFG-CHILD-PARTS"%';

UPDATE template
SET components_snapshot = REPLACE(
        components_snapshot::text,
        '"data_driver_path": "__PLACEHOLDER_MAT__"',
        '"data_driver_path": "$$COMP-CFG-MATERIAL-RECIPE.composite_child_materials_mirror"'
    )::jsonb,
    updated_at = NOW()
WHERE components_snapshot::text LIKE '%__PLACEHOLDER_MAT__%';
