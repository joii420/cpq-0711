-- V191: 字段 JSON 重写 — V184 散字段 → V190 统一 default_source 结构
--
-- 配合 V190 全局变量单表重构. 之前 V184 加的两个散元数据 (default_basic_data_path /
-- default_global_variable_code) 现在被 default_source 嵌套结构替代:
--
--   旧形态:
--     {
--       "field_type": "INPUT_NUMBER",
--       "default_basic_data_path": "process_default_yield.yield_rate",
--       "default_global_variable_code": "PROCESS_DEFAULT_YIELD"
--     }
--
--   新形态:
--     {
--       "field_type": "INPUT_NUMBER",
--       "default_source": {
--         "type": "GLOBAL_VARIABLE",
--         "code": "PROCESS_DEFAULT_YIELD",
--         "key_field_refs": {"process_code": "工序代码"}
--       }
--     }
--
-- key_field_refs 推断规则: 默认映到组件同名字段 (V190 def.keyColumns[0] = 'process_code',
-- 工序组件有「工序代码」字段绑 mat_process.process_code, 同名匹配).

-- ── Step 1: 重写 component.fields ───────────────────────────────────────────
UPDATE component
SET fields = (
    SELECT jsonb_agg(
        CASE
            WHEN f ? 'default_basic_data_path' THEN
                (f - 'default_basic_data_path' - 'default_global_variable_code')
                || jsonb_build_object(
                    'default_source',
                    jsonb_build_object(
                        'type', 'GLOBAL_VARIABLE',
                        'code', f->>'default_global_variable_code',
                        -- 推断 key: 工序组件用 process_code → 工序代码
                        'key_field_refs', CASE
                            WHEN f->>'default_global_variable_code' IN
                                ('PROCESS_DEFAULT_YIELD','PROCESS_DEFAULT_PRICE')
                            THEN jsonb_build_object('process_code', '工序代码')
                            ELSE '{}'::jsonb
                        END
                    )
                )
            ELSE f
        END
    )
    FROM jsonb_array_elements(fields) AS f
),
updated_at = NOW()
WHERE fields::text LIKE '%default_basic_data_path%';

-- ── Step 2: 同步 template.components_snapshot ───────────────────────────────
UPDATE template
SET components_snapshot = (
    SELECT jsonb_agg(
        CASE
            WHEN comp ? 'fields' AND (comp->'fields')::text LIKE '%default_basic_data_path%' THEN
                comp || jsonb_build_object('fields', (
                    SELECT jsonb_agg(
                        CASE
                            WHEN f ? 'default_basic_data_path' THEN
                                (f - 'default_basic_data_path' - 'default_global_variable_code')
                                || jsonb_build_object(
                                    'default_source',
                                    jsonb_build_object(
                                        'type', 'GLOBAL_VARIABLE',
                                        'code', f->>'default_global_variable_code',
                                        'key_field_refs', CASE
                                            WHEN f->>'default_global_variable_code' IN
                                                ('PROCESS_DEFAULT_YIELD','PROCESS_DEFAULT_PRICE')
                                            THEN jsonb_build_object('process_code', '工序代码')
                                            ELSE '{}'::jsonb
                                        END
                                    )
                                )
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
WHERE components_snapshot::text LIKE '%default_basic_data_path%';

-- ── Step 3: 自检 ────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_comp_old_count INT;
    v_comp_new_count INT;
    v_tpl_old_count INT;
    v_tpl_new_count INT;
BEGIN
    SELECT COUNT(*) INTO v_comp_old_count FROM component
        WHERE fields::text LIKE '%default_basic_data_path%';
    SELECT COUNT(*) INTO v_comp_new_count FROM component
        WHERE fields::text LIKE '%"default_source"%';
    SELECT COUNT(*) INTO v_tpl_old_count FROM template
        WHERE components_snapshot::text LIKE '%default_basic_data_path%';
    SELECT COUNT(*) INTO v_tpl_new_count FROM template
        WHERE components_snapshot::text LIKE '%"default_source"%';

    IF v_comp_old_count > 0 THEN
        RAISE EXCEPTION 'V191 自检失败: 仍有 % 个 component 含旧 default_basic_data_path', v_comp_old_count;
    END IF;
    IF v_tpl_old_count > 0 THEN
        RAISE EXCEPTION 'V191 自检失败: 仍有 % 个 template snapshot 含旧 default_basic_data_path', v_tpl_old_count;
    END IF;
    RAISE NOTICE 'V191 自检通过: % component + % template snapshot 已重写为 default_source',
        v_comp_new_count, v_tpl_new_count;
END $$;
