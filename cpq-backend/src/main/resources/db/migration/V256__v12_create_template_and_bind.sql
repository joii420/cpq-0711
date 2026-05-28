-- ============================================================
-- V256: v1.2 模板创建 + template_component 绑定
--
-- 复制 v1.1（id=a6075db5-b4ad-4449-b9be-7007556d4d2e，PUBLISHED）为 v1.2（DRAFT）
-- 绑定 20 个 -V12 组件（对应 v1.1 的 20 个 COMP-V5-* 组件）
-- excel_view_config 先沿用 v1.1，V258 再改写 variable_path
--
-- 幂等：
--   template INSERT：WHERE NOT EXISTS (version='v1.2' AND status='DRAFT')
--   template_component INSERT：按 (template_id, component_id) 幂等
-- ============================================================

DO $$ BEGIN RAISE NOTICE 'V256: 开始创建 v1.2 模板'; END $$;

DO $$
DECLARE
    v_src_id   UUID := 'a6075db5-b4ad-4449-b9be-7007556d4d2e'::uuid;
    v_series   UUID;
    v_new_id   UUID;
    v_tc_cnt   INT;
BEGIN
    -- 取 series_id
    SELECT template_series_id INTO v_series FROM template WHERE id = v_src_id;
    IF v_series IS NULL THEN
        RAISE EXCEPTION 'V256: 源模板 v1.1 不存在（id=%）', v_src_id;
    END IF;

    -- 检查 v1.2 是否已存在
    SELECT id INTO v_new_id
    FROM template
    WHERE template_series_id = v_series AND version = 'v1.2'
    LIMIT 1;

    IF v_new_id IS NOT NULL THEN
        RAISE NOTICE 'V256: v1.2 模板已存在（id=%），跳过 INSERT', v_new_id;
    ELSE
        -- 插入 v1.2 DRAFT
        INSERT INTO template (
            id,
            template_series_id,
            name,
            version,
            category,
            customer_id,
            category_id,
            description,
            usage_note,
            product_attributes,
            subtotal_formula,
            formulas,
            excel_view_config,
            template_kind,
            status,
            is_default,
            components_snapshot,
            template_sql_views_snapshot,
            sql_views_snapshot,
            referenced_variables,
            created_at,
            updated_at
        )
        SELECT
            gen_random_uuid(),
            src.template_series_id,
            src.name,
            'v1.2',
            src.category,
            src.customer_id,
            src.category_id,
            COALESCE(src.description, '') || E'\n\n[v1.2 改造] 全部组件视图替换为从 V6 表查询的 $v12_* SQL 视图；'
                || 'v_costing_summary_full / v_c_summary_agg 拆为 7 个 template SQL 视图。'
                || 'FROM 子句全部走 V6 表（material_master/material_bom_item/element_bom_item/fee_config/unit_price 等）。',
            src.usage_note,
            src.product_attributes,
            src.subtotal_formula,
            src.formulas,
            src.excel_view_config,
            src.template_kind,
            'DRAFT',
            false,
            NULL,
            '{}'::jsonb,
            '{}'::jsonb,
            src.referenced_variables,
            NOW(),
            NOW()
        FROM template src
        WHERE src.id = v_src_id
        RETURNING id INTO v_new_id;

        RAISE NOTICE 'V256: v1.2 模板已创建 id=%', v_new_id;
    END IF;

    -- 绑定 20 个 -V12 组件（从 v1.1 template_component 关联关系复制，替换到 -V12 组件）
    INSERT INTO template_component (
        id,
        template_id,
        component_id,
        tab_name,
        sort_order,
        created_at,
        preset_rows,
        formula_assignments,
        data_driver_path_override,
        fields_override
    )
    SELECT
        gen_random_uuid(),
        v_new_id,
        nc.id,
        stc.tab_name,
        stc.sort_order,
        NOW(),
        COALESCE(stc.preset_rows, '[]'::jsonb),
        COALESCE(stc.formula_assignments, '{}'::jsonb),
        NULL,
        NULL
    FROM template_component stc
    JOIN component sc ON sc.id = stc.component_id
    JOIN component nc ON nc.code = sc.code || '-V12'
    WHERE stc.template_id = v_src_id
      AND NOT EXISTS (
          SELECT 1 FROM template_component ex
          WHERE ex.template_id = v_new_id AND ex.component_id = nc.id
      );

    SELECT COUNT(*) INTO v_tc_cnt
    FROM template_component
    WHERE template_id = v_new_id;

    RAISE NOTICE 'V256: v1.2 模板绑定完成，template_component 总数=%（期望 20）', v_tc_cnt;
END $$;
