-- V215: 修复「选配-元素含量」组件"单价"字段 key_field_refs 空映射
-- 问题根因：
--   component COMP-CFG-ELEMENT-BOM 的"单价"字段使用 datasource_binding.type=GLOBAL_VARIABLE,
--   global_variable_code=COST_ELEMENT，但 key_field_refs={} 为空映射。
--   后端 resolveGvarForRow 时 col="key" 找不到 driverRow 中对应列，返 null →
--   @gvar:COST_ELEMENT 解析为 null → 单价显示 0。
-- 修复策略：
--   COST_ELEMENT.key_columns=["key"]，global_variable_value.key_values={"key":"Ag"/"Cu"/"Sn"}
--   driver row (v_composite_child_elements) 含 element_name 列，值即为元素符号（Ag/Cu/Sn）。
--   因此 key_field_refs 应映射 {"key": "element_name"}。
-- 涉及 3 张表：
--   1. component.fields              —— 主表字段定义
--   2. template_component.fields_override —— 模板组件覆盖配置（当前无 COST_ELEMENT 条目，保留逻辑）
--   3. template.components_snapshot  —— 所有含 COST_ELEMENT 的 snapshot（PUBLISHED/ARCHIVED 均修）

-- ============================================================
-- 1. 修 component.fields 主表
-- ============================================================
WITH updated AS (
    SELECT
        id,
        jsonb_agg(
            CASE
                WHEN f ->> 'name' = '单价'
                    AND f -> 'datasource_binding' IS NOT NULL
                    AND f -> 'datasource_binding' ->> 'global_variable_code' = 'COST_ELEMENT'
                THEN jsonb_set(
                        f,
                        '{datasource_binding,key_field_refs}',
                        '{"key": "element_name"}'::jsonb
                     )
                ELSE f
            END
            ORDER BY ord
        ) AS new_fields
    FROM component c,
         jsonb_array_elements(c.fields::jsonb) WITH ORDINALITY AS arr(f, ord)
    WHERE c.code = 'COMP-CFG-ELEMENT-BOM'
       OR c.name = '选配-元素含量'
    GROUP BY id
)
UPDATE component c
SET    fields = u.new_fields
FROM   updated u
WHERE  u.id = c.id;

-- ============================================================
-- 2. 修 template_component.fields_override
--    （当前无 COST_ELEMENT 条目，但保留逻辑以防将来有 override）
-- ============================================================
WITH updated AS (
    SELECT
        id,
        jsonb_agg(
            CASE
                WHEN f ->> 'name' = '单价'
                    AND f -> 'datasource_binding' IS NOT NULL
                    AND f -> 'datasource_binding' ->> 'global_variable_code' = 'COST_ELEMENT'
                THEN jsonb_set(
                        f,
                        '{datasource_binding,key_field_refs}',
                        '{"key": "element_name"}'::jsonb
                     )
                ELSE f
            END
            ORDER BY ord
        ) AS new_override
    FROM template_component tc,
         jsonb_array_elements(tc.fields_override::jsonb) WITH ORDINALITY AS arr(f, ord)
    WHERE tc.fields_override IS NOT NULL
      AND tc.fields_override::text LIKE '%COST_ELEMENT%'
    GROUP BY id
)
UPDATE template_component tc
SET    fields_override = u.new_override
FROM   updated u
WHERE  u.id = tc.id;

-- ============================================================
-- 3. 修 template.components_snapshot（PUBLISHED/ARCHIVED 均修）
--    components_snapshot 是 JSONB 数组，每元素含 fields 数组，需双层遍历重组。
-- ============================================================
WITH
comp_fixed AS (
    SELECT
        t.id AS template_id,
        comp_idx,
        jsonb_set(
            comp,
            '{fields}',
            (
                SELECT jsonb_agg(
                    CASE
                        WHEN f ->> 'name' = '单价'
                            AND f -> 'datasource_binding' IS NOT NULL
                            AND f -> 'datasource_binding' ->> 'global_variable_code' = 'COST_ELEMENT'
                        THEN jsonb_set(
                                f,
                                '{datasource_binding,key_field_refs}',
                                '{"key": "element_name"}'::jsonb
                             )
                        ELSE f
                    END
                    ORDER BY field_idx
                )
                FROM jsonb_array_elements(comp -> 'fields') WITH ORDINALITY AS fi(f, field_idx)
            )
        ) AS new_comp
    FROM template t,
         jsonb_array_elements(t.components_snapshot::jsonb) WITH ORDINALITY AS ci(comp, comp_idx)
    WHERE t.components_snapshot::text LIKE '%COST_ELEMENT%'
),
snapshot_fixed AS (
    SELECT
        template_id,
        jsonb_agg(new_comp ORDER BY comp_idx) AS new_snapshot
    FROM comp_fixed
    GROUP BY template_id
)
UPDATE template t
SET    components_snapshot = sf.new_snapshot
FROM   snapshot_fixed sf
WHERE  sf.template_id = t.id;
