-- V183: 选配产品标准报价模板拆分为两个独立模板(2026-05-17)
--
-- 背景: 用户期望"模板渲染按配置展示, 不要前端按 productType 自动隐藏 Tab"(WYSIWYG).
-- 原模板 b1d2e3f4 含 7 个组件(其中"组合工艺/子配件"只对 COMPOSITE 有意义).
-- 拆成两个独立模板, 让用户在新建报价单 Step1 自行选择.
--
-- 拆分策略:
--   1. 原模板 b1d2e3f4 改名 "选配产品标准报价模板-组合产品" (保持 7 个组件不变,
--      已有 lineItem.template_id 引用自动指向新名称, 不影响历史数据)
--   2. 派生新模板 "选配产品标准报价模板-单一产品" (新 series_id, 含 5 个组件:
--      材质/元素含量/工序/单重/总成本; 去掉"组合工艺"和"子配件")
--
-- 总成本组件 (COMP-CFG-TOTAL) 字段不变: SIMPLE 模板下"组合工艺费"字段 token
-- (component_subtotal of COMP-CFG-COMPOSITE-PROC) 求值返 0(因模板无该组件),
-- 产品总成本 = 材料 + 加工费 + 0, 业务结果正确.

-- ── 1. 原模板改名 ─────────────────────────────────────────────────────────

UPDATE template
SET name = '选配产品标准报价模板-组合产品',
    updated_at = NOW()
WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163'
  AND name IN ('选配产品标准报价模板', '选配产品标准报价模板-组合产品');  -- 幂等

-- ── 2. 派生新模板"选配产品标准报价模板-单一产品" ─────────────────────────

DO $$
DECLARE
    v_new_id UUID := 'b1d2e3f4-cf63-4163-8163-000000000164';  -- 固定 UUID 便于追溯
    v_new_series UUID := 'b1d2e3f4-cf63-4163-8163-000000000165';
    v_orig RECORD;
    v_new_snapshot JSONB;
    v_composite_proc_component_id UUID := '3bbde78f-718c-4544-85f2-0a25397b7eaa';
    v_child_parts_component_id UUID := 'c3ad18a5-2cd8-424e-b702-ea26f3a48cee';
BEGIN
    -- 幂等检查: 已存在则跳过
    IF EXISTS (SELECT 1 FROM template WHERE id = v_new_id) THEN
        RAISE NOTICE 'V183: 单一产品模板 % 已存在, 跳过派生', v_new_id;
        RETURN;
    END IF;

    SELECT * INTO v_orig FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';
    IF v_orig.id IS NULL THEN
        RAISE EXCEPTION 'V183: 原模板 b1d2e3f4 不存在, 无法派生';
    END IF;

    -- 从原 components_snapshot 过滤掉 COMPOSITE-PROC + CHILD-PARTS
    SELECT jsonb_agg(elem ORDER BY ord)
    INTO v_new_snapshot
    FROM (
        SELECT elem, (elem->>'sortOrder')::int AS ord
        FROM jsonb_array_elements(v_orig.components_snapshot) elem
        WHERE COALESCE(elem->>'componentCode', elem->>'component_code')
              NOT IN ('COMP-CFG-COMPOSITE-PROC', 'COMP-CFG-CHILD-PARTS')
    ) sub;

    -- 重新规整 sortOrder (0..4) — 删除中间项后填补空洞
    SELECT jsonb_agg(
        jsonb_set(elem, '{sortOrder}', to_jsonb(rn - 1))
        ORDER BY rn
    )
    INTO v_new_snapshot
    FROM (
        SELECT elem, ROW_NUMBER() OVER (ORDER BY (elem->>'sortOrder')::int) AS rn
        FROM jsonb_array_elements(v_new_snapshot) elem
    ) sub;

    INSERT INTO template (
        id, template_series_id, name, version, category, description, usage_note,
        product_attributes, subtotal_formula, components_snapshot, status,
        created_by, published_at, created_at, updated_at,
        excel_view_config, customer_id, category_id, template_kind,
        formulas, is_default, referenced_variables
    ) VALUES (
        v_new_id, v_new_series, '选配产品标准报价模板-单一产品', 'v1.0',
        v_orig.category, v_orig.description,
        '本模板专用于"添加产品 — 选配"功能产出的 SIMPLE 料号(hf_part_no 形如 CFG-AgCu-000001). ' ||
        '5 个 Tab: 材质 / 元素含量 / 工序 / 单重 / 总成本. ' ||
        'COMPOSITE 产品请改用"选配产品标准报价模板-组合产品" (含组合工艺 + 子配件 Tab).',
        v_orig.product_attributes, v_orig.subtotal_formula, v_new_snapshot, 'PUBLISHED',
        v_orig.created_by, NOW(), NOW(), NOW(),
        v_orig.excel_view_config, NULL, v_orig.category_id, 'QUOTATION',
        v_orig.formulas, FALSE, v_orig.referenced_variables
    );

    -- 复制 template_component 关联 (除 COMPOSITE-PROC + CHILD-PARTS)
    INSERT INTO template_component (
        id, template_id, component_id, tab_name, sort_order,
        preset_rows, formula_assignments, created_at
    )
    SELECT
        gen_random_uuid(),
        v_new_id,
        component_id,
        tab_name,
        ROW_NUMBER() OVER (ORDER BY sort_order) - 1,  -- 重规整 sort_order 0..4
        preset_rows,
        formula_assignments,
        NOW()
    FROM template_component
    WHERE template_id = 'b1d2e3f4-cf63-4163-8163-000000000163'
      AND component_id NOT IN (v_composite_proc_component_id, v_child_parts_component_id);

    RAISE NOTICE 'V183: 已派生"选配产品标准报价模板-单一产品" id=% (5 个 Tab 组件)', v_new_id;
END $$;

-- ── 3. 自检 ──────────────────────────────────────────────────────────────

DO $$
DECLARE
    v_composite_name TEXT;
    v_simple_count INT;
    v_simple_snapshot_count INT;
    v_composite_count INT;
BEGIN
    SELECT name INTO v_composite_name FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';
    IF v_composite_name != '选配产品标准报价模板-组合产品' THEN
        RAISE EXCEPTION 'V183 自检失败: 原模板未改名 (当前=%)', v_composite_name;
    END IF;

    SELECT COUNT(*) INTO v_simple_count
    FROM template_component
    WHERE template_id = 'b1d2e3f4-cf63-4163-8163-000000000164';
    IF v_simple_count != 5 THEN
        RAISE EXCEPTION 'V183 自检失败: 单一产品模板 template_component 数 = %, 期望 5', v_simple_count;
    END IF;

    SELECT jsonb_array_length(components_snapshot) INTO v_simple_snapshot_count
    FROM template WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000164';
    IF v_simple_snapshot_count != 5 THEN
        RAISE EXCEPTION 'V183 自检失败: 单一产品模板 components_snapshot 数 = %, 期望 5', v_simple_snapshot_count;
    END IF;

    SELECT COUNT(*) INTO v_composite_count
    FROM template_component
    WHERE template_id = 'b1d2e3f4-cf63-4163-8163-000000000163';
    IF v_composite_count != 7 THEN
        RAISE EXCEPTION 'V183 自检失败: 组合产品模板 template_component 数 = %, 期望 7 (不应改动)', v_composite_count;
    END IF;

    RAISE NOTICE 'V183 自检通过: 组合产品模板保持 7 Tab, 单一产品模板派生 5 Tab, 两份模板均 PUBLISHED';
END $$;
