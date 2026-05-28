-- ============================================================
-- V231: 补 V230 漏掉的 v_composite_child_weights → $composite_child_weights_mirror
-- 同样的字段级 basic_data_path + 组件级 data_driver_path + 模板 snapshot 三层同步
-- ============================================================

-- 1. component.fields 字段级 BNF
UPDATE component
SET fields = REPLACE(
        fields::text,
        '"basic_data_path": "v_composite_child_weights.',
        '"basic_data_path": "$composite_child_weights_mirror.'
    )::jsonb,
    updated_at = NOW()
WHERE fields::text LIKE '%"basic_data_path": "v_composite_child_weights.%';

-- 2. component 级 data_driver_path
UPDATE component SET
    data_driver_path = '$composite_child_weights_mirror',
    updated_at = NOW()
WHERE data_driver_path = 'v_composite_child_weights';

-- 3. template.components_snapshot 字段级 + 驱动级
UPDATE template
SET components_snapshot = REPLACE(REPLACE(
        components_snapshot::text,
        '"basic_data_path": "v_composite_child_weights.',
        '"basic_data_path": "$composite_child_weights_mirror.'
    ),
        '"data_driver_path": "v_composite_child_weights"',
        '"data_driver_path": "$composite_child_weights_mirror"'
    )::jsonb,
    updated_at = NOW()
WHERE components_snapshot::text LIKE '%v_composite_child_weights%';

DO $body$
DECLARE
    comp_remain INT;
    tpl_remain  INT;
BEGIN
    SELECT COUNT(*) INTO comp_remain FROM component
        WHERE (fields::text || COALESCE(data_driver_path,'')) LIKE '%v_composite_child_%';
    SELECT COUNT(*) INTO tpl_remain  FROM template
        WHERE components_snapshot::text LIKE '%v_composite_child_%';
    RAISE NOTICE 'V231 done: 组件残留 v_composite_child_*: %; 模板 snapshot 残留: %', comp_remain, tpl_remain;
END $body$;
