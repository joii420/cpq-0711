-- ============================================================
-- V229: 把 5 个组合产品组件的 data_driver_path 改成 SQL 视图引用 +
--       同步全部模板 components_snapshot 中的同一字段
--
-- 触发: AP-53 老表禁用规则 (docs/方案制定前必读.md §V6 基础资料表使用规则)
-- 组件级 data_driver_path 当前是 V44 PG 视图名 / 直接表名引用，
-- 必须改为 SQL 视图引用语法 $<sql_view_name>。
--
-- 映射:
--   v_composite_child_processes        → $composite_child_processes_mirror
--   v_composite_child_materials        → $composite_child_materials_mirror
--   v_composite_child_elements         → $composite_child_elements_mirror
--   mat_bom[bom_type='ASSEMBLY']       → $composite_child_materials_mirror  (子配件清单复用 materials mirror)
--   mat_composite_process              → $composite_child_processes_mirror  (组合工艺暂复用 processes mirror)
-- ============================================================

-- ============== 1. component 表 ==============
UPDATE component SET data_driver_path = '$composite_child_processes_mirror', updated_at = NOW()
    WHERE code = 'COMP-CFG-PROCESS';
UPDATE component SET data_driver_path = '$composite_child_materials_mirror', updated_at = NOW()
    WHERE code = 'COMP-CFG-MATERIAL-RECIPE';
UPDATE component SET data_driver_path = '$composite_child_elements_mirror', updated_at = NOW()
    WHERE code = 'COMP-CFG-ELEMENT-BOM';
UPDATE component SET data_driver_path = '$composite_child_materials_mirror', updated_at = NOW()
    WHERE code = 'COMP-CFG-CHILD-PARTS';
UPDATE component SET data_driver_path = '$composite_child_processes_mirror', updated_at = NOW()
    WHERE code = 'COMP-CFG-COMPOSITE-PROC';

-- ============== 2. template.components_snapshot 同步替换 ==============
-- 用 text REPLACE 替换 JSONB 中的 data_driver_path 字段值
-- JSONB 序列化格式为 "data_driver_path": "value"（冒号后有空格）
UPDATE template
SET components_snapshot = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
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
    '"data_driver_path": "mat_bom[bom_type=''ASSEMBLY'']"',
    '"data_driver_path": "$composite_child_materials_mirror"'
    ),
    '"data_driver_path": "mat_composite_process"',
    '"data_driver_path": "$composite_child_processes_mirror"'
    )::jsonb,
    updated_at = NOW()
WHERE components_snapshot::text ~ '"data_driver_path": "(v_composite_child_|mat_bom\[bom_type=''ASSEMBLY''\]|mat_composite_process)';

-- ============== 3. 自检日志（仅打印执行计数，不影响 Flyway） ==============
DO $body$
DECLARE
    comp_cnt   INT;
    tpl_cnt    INT;
    remain_cnt INT;
BEGIN
    SELECT COUNT(*) INTO comp_cnt FROM component
        WHERE code IN ('COMP-CFG-PROCESS','COMP-CFG-MATERIAL-RECIPE','COMP-CFG-ELEMENT-BOM',
                       'COMP-CFG-CHILD-PARTS','COMP-CFG-COMPOSITE-PROC')
          AND data_driver_path LIKE '$%mirror';
    SELECT COUNT(*) INTO tpl_cnt FROM template
        WHERE components_snapshot::text ~ '\$composite_child_(processes|materials|elements)_mirror';
    SELECT COUNT(*) INTO remain_cnt FROM template
        WHERE components_snapshot::text ~ '"data_driver_path": "(v_composite_child_|mat_bom\[bom_type=''ASSEMBLY''\]|mat_composite_process)';
    RAISE NOTICE 'V229 done: 5 组合产品组件已转 mirror (实际 %), 模板 snapshot 含 mirror 引用 % 张, 仍含老路径 % 张',
        comp_cnt, tpl_cnt, remain_cnt;
END $body$;
