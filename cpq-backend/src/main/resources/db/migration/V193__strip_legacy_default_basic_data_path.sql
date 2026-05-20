-- V193: 清理 V184 散字段残留 — default_basic_data_path / default_global_variable_code
--
-- V191/V192 已把 component.fields + template.components_snapshot 里的散字段重写为
-- default_source 嵌套结构, 但物理 JSON 里仍残留 default_basic_data_path 等键 (V191
-- 的 jsonb 操作只是"加"了 default_source, 没"删"老键). V193 一次性 strip 干净, 让
-- JSON shape 与 default_source 唯一形态对齐, 前端可彻底删兼容读层.
--
-- 影响面: 仅清键名, 不动业务语义 (default_source 已含相同信息).

-- ── Step 1: component.fields 删 default_basic_data_path / default_global_variable_code ─────
UPDATE component
SET fields = (
    SELECT jsonb_agg(
        CASE
            WHEN f ? 'default_basic_data_path' OR f ? 'default_global_variable_code'
            THEN f - 'default_basic_data_path' - 'default_global_variable_code'
            ELSE f
        END
    )
    FROM jsonb_array_elements(fields) AS f
),
updated_at = NOW()
WHERE fields::text LIKE '%default_basic_data_path%'
   OR fields::text LIKE '%default_global_variable_code%';

-- ── Step 2: template.components_snapshot 同步 ───────────────────────────────
UPDATE template
SET components_snapshot = (
    SELECT jsonb_agg(
        CASE
            WHEN comp ? 'fields'
                AND (
                    (comp->'fields')::text LIKE '%default_basic_data_path%'
                    OR (comp->'fields')::text LIKE '%default_global_variable_code%'
                )
            THEN comp || jsonb_build_object('fields', (
                SELECT jsonb_agg(
                    CASE
                        WHEN f ? 'default_basic_data_path' OR f ? 'default_global_variable_code'
                        THEN f - 'default_basic_data_path' - 'default_global_variable_code'
                        ELSE f
                    END
                )
                FROM jsonb_array_elements(comp->'fields') AS f
            ))
            ELSE comp
        END
    )
    FROM jsonb_array_elements(components_snapshot) AS comp
),
updated_at = NOW()
WHERE components_snapshot::text LIKE '%default_basic_data_path%'
   OR components_snapshot::text LIKE '%default_global_variable_code%';

-- ── Step 3: 自检 — 残留必须为 0 ────────────────────────────────────────────
DO $$
DECLARE
    v_comp_residue INT;
    v_tpl_residue INT;
BEGIN
    SELECT COUNT(*) INTO v_comp_residue FROM component
        WHERE fields::text LIKE '%default_basic_data_path%'
           OR fields::text LIKE '%default_global_variable_code%';
    SELECT COUNT(*) INTO v_tpl_residue FROM template
        WHERE components_snapshot::text LIKE '%default_basic_data_path%'
           OR components_snapshot::text LIKE '%default_global_variable_code%';

    IF v_comp_residue > 0 OR v_tpl_residue > 0 THEN
        RAISE EXCEPTION 'V193 自检失败: component 残留=% template 残留=%',
            v_comp_residue, v_tpl_residue;
    END IF;
    RAISE NOTICE 'V193 完成: V184 散字段已彻底清理, 前端可移除兼容读层';
END $$;
