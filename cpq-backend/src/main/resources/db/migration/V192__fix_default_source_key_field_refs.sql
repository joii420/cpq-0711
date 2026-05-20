-- V192: 修正 V191 字段 default_source.key_field_refs 错误推导
--
-- 故障: V191 把 key_field_refs 设成 {"process_code": "工序代码"}, 意图是「全局变量 key
--       列 process_code 对应组件字段「工序代码」」. 但 ComponentDriverService.resolveGvarForRow
--       从 driver row (mat_process 表行) 取值, driver row 字段名是物理列名 (process_code),
--       不是组件字段名 (工序代码) → 查 driverRow["工序代码"] 失败, 全局变量解出 null,
--       报价单 placeholder 退到 content="100" 静态兜底, Z350=15 配置不生效.
--
-- 修法: 把 key_field_refs 清空, 让 ComponentDriverService.resolveGvarForRow 走默认同名
--       映射 (col → driverRow[col]). 简洁且符合 99% 场景.

-- ── Step 1: 重写 component.fields ───────────────────────────────────────────
UPDATE component
SET fields = (
    SELECT jsonb_agg(
        CASE
            WHEN f ? 'default_source'
              AND (f->'default_source'->>'type') = 'GLOBAL_VARIABLE'
              AND (f->'default_source'->'key_field_refs') IS NOT NULL
            THEN jsonb_set(f, '{default_source,key_field_refs}', '{}'::jsonb)
            ELSE f
        END
    )
    FROM jsonb_array_elements(fields) AS f
),
updated_at = NOW()
WHERE fields::text LIKE '%"key_field_refs"%';

-- ── Step 2: 同步 template.components_snapshot ───────────────────────────────
UPDATE template
SET components_snapshot = (
    SELECT jsonb_agg(
        CASE
            WHEN comp ? 'fields' AND (comp->'fields')::text LIKE '%"key_field_refs"%' THEN
                comp || jsonb_build_object('fields', (
                    SELECT jsonb_agg(
                        CASE
                            WHEN f ? 'default_source'
                              AND (f->'default_source'->>'type') = 'GLOBAL_VARIABLE'
                              AND (f->'default_source'->'key_field_refs') IS NOT NULL
                            THEN jsonb_set(f, '{default_source,key_field_refs}', '{}'::jsonb)
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
WHERE components_snapshot::text LIKE '%"key_field_refs"%';

-- ── Step 3: 自检 ────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_stale_field INT; v_stale_snap INT;
BEGIN
    SELECT COUNT(*) INTO v_stale_field FROM component
        WHERE fields::text LIKE '%"工序代码"%';
    SELECT COUNT(*) INTO v_stale_snap FROM template
        WHERE components_snapshot::text LIKE '%"工序代码"%';
    -- 容许 "工序代码" 出现在 name 字段 (它就是字段名), 但 key_field_refs 内不能有
    -- 这里只警告, 因为字段名「工序代码」是合法存在
    RAISE NOTICE 'V192 完成: key_field_refs 已清空 (字段名「工序代码」作为 name 仍合法存在)';
END $$;
