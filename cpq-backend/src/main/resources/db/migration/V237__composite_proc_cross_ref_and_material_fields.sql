-- ============================================================
-- V237:
-- 1) COMPOSITE-PROC 组件 driver path 改跨组件引用（同 CHILD-PARTS 模式）
--    复用 PROCESS 组件的 mirror 视图，避免每个组件重复维护同一份 SQL 模板
-- 2) materials_mirror 修字段映射：
--    material_name 取 material_master.material_type（之前 V228 mirror 已经这样写，但实际未生效）
--    保留 material_code / chemical_symbol / recipe_type 为 NULL（V6 没有 material_recipe FK）
--    spec_label = material_master.specification 已正确（V228 mirror）
-- ============================================================

-- 1. COMPOSITE-PROC 改跨组件引用
UPDATE component
SET data_driver_path = '$$COMP-CFG-PROCESS.composite_child_processes_mirror',
    updated_at = NOW()
WHERE code = 'COMP-CFG-COMPOSITE-PROC';

-- 同步 template snapshot
UPDATE template
SET components_snapshot = REPLACE(
        components_snapshot::text,
        '"componentCode": "COMP-CFG-COMPOSITE-PROC", "componentType",',
        '"componentCode": "COMP-CFG-COMPOSITE-PROC", "componentType":'
    )::jsonb,
    updated_at = NOW()
WHERE FALSE;  -- noop, structure check

UPDATE template t
SET components_snapshot = (
    SELECT jsonb_agg(
        CASE
            WHEN c->>'componentCode' = 'COMP-CFG-COMPOSITE-PROC'
                THEN jsonb_set(c, '{data_driver_path}', '"$$COMP-CFG-PROCESS.composite_child_processes_mirror"'::jsonb)
            ELSE c
        END
    )
    FROM jsonb_array_elements(t.components_snapshot) c
),
    updated_at = NOW()
WHERE components_snapshot::text LIKE '%COMP-CFG-COMPOSITE-PROC%';

-- 2. materials_mirror SQL 已经在 V228 里写对（material_name = material_master.material_type）
--    实际查 material_master 数据，确认是否有 material_type 字段值。如果是 NULL → mirror 输出 NULL → UI 显 None
--    本次迁移不改 mirror SQL，仅作记录：如需有效材质名，需在 V6 导入时填 material_master.material_type
DO $body$
DECLARE
    cnt_with_type INT;
    cnt_total INT;
BEGIN
    SELECT COUNT(*) INTO cnt_with_type FROM material_master WHERE material_type IS NOT NULL;
    SELECT COUNT(*) INTO cnt_total FROM material_master;
    RAISE NOTICE 'V237: material_master 含 material_type 的 %/%（无 type 时 mirror 输出 NULL → UI 显空）',
        cnt_with_type, cnt_total;
END $body$;
