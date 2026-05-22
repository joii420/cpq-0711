-- V214: 修复「选配-元素含量」组件"单位"字段错绑 ELEM_PRICE 全局变量
-- 问题：component.fields 中 name='单位' 的字段携带 global_variable_code='ELEM_PRICE'，
--       导致前端 ComponentCell.tsx 优先读 @gvar:ELEM_PRICE（价格值）而非视图的 unit 列，
--       造成"单位"列显示 5800/65 而非"KG"。
-- 修复策略：仅移除错绑的 global_variable_code 属性，保留 basic_data_path 及其他属性。
--
-- 涉及 3 张表：
--   1. component.fields              —— 主表字段定义
--   2. template_component.fields_override —— 模板组件覆盖配置
--   3. template.components_snapshot  —— PUBLISHED 模板 snapshot（含嵌套 fields 数组）

-- ============================================================
-- 1. 修 component.fields 主表
-- ============================================================
WITH updated AS (
    SELECT
        id,
        jsonb_agg(
            CASE
                WHEN f ->> 'name' = '单位'
                    AND f -> 'global_variable_code' IS NOT NULL
                    AND f ->> 'global_variable_code' = 'ELEM_PRICE'
                THEN f - 'global_variable_code'
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
--    （针对所有含"单位"字段且 global_variable_code='ELEM_PRICE' 的记录）
-- ============================================================
WITH updated AS (
    SELECT
        id,
        jsonb_agg(
            CASE
                WHEN f ->> 'name' = '单位'
                    AND f -> 'global_variable_code' IS NOT NULL
                    AND f ->> 'global_variable_code' = 'ELEM_PRICE'
                THEN f - 'global_variable_code'
                ELSE f
            END
            ORDER BY ord
        ) AS new_override
    FROM template_component tc,
         jsonb_array_elements(tc.fields_override::jsonb) WITH ORDINALITY AS arr(f, ord)
    WHERE tc.fields_override IS NOT NULL
      AND tc.fields_override::text LIKE '%ELEM_PRICE%'
    GROUP BY id
)
UPDATE template_component tc
SET    fields_override = u.new_override
FROM   updated u
WHERE  u.id = tc.id;

-- ============================================================
-- 3. 修 template.components_snapshot（PUBLISHED/ARCHIVED 均修，消除 snapshot 残留）
--    components_snapshot 是 JSONB 数组，每个元素含 fields 数组，需双层遍历重组。
-- ============================================================
WITH
-- 第一层：展开 components_snapshot 数组，对每个组件元素重建 fields
comp_fixed AS (
    SELECT
        t.id AS template_id,
        comp_idx,
        -- 重组 comp 对象：将 fields 替换为去掉错绑后的新 fields
        jsonb_set(
            comp,
            '{fields}',
            (
                SELECT jsonb_agg(
                    CASE
                        WHEN f ->> 'name' = '单位'
                            AND f -> 'global_variable_code' IS NOT NULL
                            AND f ->> 'global_variable_code' = 'ELEM_PRICE'
                        THEN f - 'global_variable_code'
                        ELSE f
                    END
                    ORDER BY field_idx
                )
                FROM jsonb_array_elements(comp -> 'fields') WITH ORDINALITY AS fi(f, field_idx)
            )
        ) AS new_comp
    FROM template t,
         jsonb_array_elements(t.components_snapshot::jsonb) WITH ORDINALITY AS ci(comp, comp_idx)
    WHERE t.components_snapshot::text LIKE '%ELEM_PRICE%'
),
-- 第二层：按 template_id 重新聚合回完整的 components_snapshot 数组
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
