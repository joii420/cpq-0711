-- ============================================================
-- V235: data_driver_path 改回 $<mirror> SQL 视图引用形式
--
-- 触发: AP-53 严格规则 — BNF path 查询只能用组件配置的 SQL 视图，禁止用 PG view
-- V232 曾把 driver path 回滚为 PG view 名（因 DataLoader 不支持 $<view> 无列名形式）
-- 本次后端已扩展 SqlViewExecutor.executeAllRows + DataLoader.loadByPath 分流，
-- 现在可以让 driver path 用 $<mirror> 形式直接执行 mirror SQL，
-- 不再依赖 PG view，BNF 通道整体由 component_sql_view.sql_template 提供数据。
-- ============================================================

-- 1. component 表
UPDATE component SET data_driver_path = '$composite_child_processes_mirror', updated_at = NOW()
    WHERE code IN ('COMP-CFG-PROCESS', 'COMP-CFG-COMPOSITE-PROC');
UPDATE component SET data_driver_path = '$composite_child_materials_mirror', updated_at = NOW()
    WHERE code IN ('COMP-CFG-MATERIAL-RECIPE', 'COMP-CFG-CHILD-PARTS');
UPDATE component SET data_driver_path = '$composite_child_elements_mirror',  updated_at = NOW()
    WHERE code = 'COMP-CFG-ELEMENT-BOM';
UPDATE component SET data_driver_path = '$composite_child_weights_mirror', updated_at = NOW()
    WHERE code = 'COMP-CFG-WEIGHT' AND data_driver_path IS NOT NULL;

-- 2. template.components_snapshot 同步
UPDATE template
SET components_snapshot = REPLACE(REPLACE(REPLACE(REPLACE(
        components_snapshot::text,
        '"data_driver_path": "v_composite_child_processes"',
        '"data_driver_path": "$composite_child_processes_mirror"'
    ),
        '"data_driver_path": "v_composite_child_materials"',
        '"data_driver_path": "$composite_child_materials_mirror"'
    ),
        '"data_driver_path": "v_composite_child_elements"',
        '"data_driver_path": "$composite_child_elements_mirror"'
    ),
        '"data_driver_path": "v_composite_child_weights"',
        '"data_driver_path": "$composite_child_weights_mirror"'
    )::jsonb,
    updated_at = NOW()
WHERE components_snapshot::text LIKE '%"data_driver_path": "v_composite_child_%';
