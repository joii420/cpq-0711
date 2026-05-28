-- ============================================================
-- V232: data_driver_path 回滚到 PG 视图名 / V6 表谓词
--
-- 原因: $<sql_view_name> 是字段级 BNF 引用语法（SqlViewExecutor 解析），
--       不支持作为组件级 data_driver_path（DataLoader/ImplicitJoinRewriter 期望 PG 表名/视图名 + 谓词）。
-- 修复方向: data_driver_path 维持 PG view 名，PG view 本身由 V233 改写为查 V6 表。
-- 字段级 basic_data_path 保留 $<mirror>.col 形式不变（V230/V231 已修对）。
-- ============================================================

-- 1. component 表
UPDATE component SET data_driver_path = 'v_composite_child_processes',  updated_at = NOW() WHERE code = 'COMP-CFG-PROCESS';
UPDATE component SET data_driver_path = 'v_composite_child_materials',  updated_at = NOW() WHERE code = 'COMP-CFG-MATERIAL-RECIPE';
UPDATE component SET data_driver_path = 'v_composite_child_elements',   updated_at = NOW() WHERE code = 'COMP-CFG-ELEMENT-BOM';
-- CHILD-PARTS / COMPOSITE-PROC 原本是直接表名 — 改成对应的 PG view 让 V233 一并处理
UPDATE component SET data_driver_path = 'v_composite_child_materials',  updated_at = NOW() WHERE code = 'COMP-CFG-CHILD-PARTS';
UPDATE component SET data_driver_path = 'v_composite_child_processes',  updated_at = NOW() WHERE code = 'COMP-CFG-COMPOSITE-PROC';
-- WEIGHT 组件（若存在）
UPDATE component SET data_driver_path = 'v_composite_child_weights',    updated_at = NOW() WHERE code = 'COMP-CFG-WEIGHT' AND data_driver_path LIKE '$%';

-- 2. template.components_snapshot 同步回滚（5 种映射 → 4 个 PG view 名）
UPDATE template
SET components_snapshot = REPLACE(REPLACE(REPLACE(REPLACE(
        components_snapshot::text,
        '"data_driver_path": "$composite_child_processes_mirror"',
        '"data_driver_path": "v_composite_child_processes"'
    ),
        '"data_driver_path": "$composite_child_materials_mirror"',
        '"data_driver_path": "v_composite_child_materials"'
    ),
        '"data_driver_path": "$composite_child_elements_mirror"',
        '"data_driver_path": "v_composite_child_elements"'
    ),
        '"data_driver_path": "$composite_child_weights_mirror"',
        '"data_driver_path": "v_composite_child_weights"'
    )::jsonb,
    updated_at = NOW()
WHERE components_snapshot::text LIKE '%"data_driver_path": "$composite_child_%';
