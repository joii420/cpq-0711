-- V181: 修复"选配-材质"组件 5 字段渲染全 null 的模板配置问题.
--
-- 背景: 组件 COMP-CFG-MATERIAL-RECIPE (e42185ec) 的 5 个 BASIC_DATA 字段配置成
--   "material_recipe.code/symbol/name/spec_label/recipe_type"
-- 但 material_recipe 表无 hf_part_no 列, ImplicitJoinRewriter 无法按料号收窄,
-- 导致查询返字典全表 → 前端解析失败 → UI 5 字段全 null.
--
-- 修复:
--   1. 创建视图 v_part_material_recipe (mat_part LEFT JOIN material_recipe),
--      暴露 hf_part_no 列让 ImplicitJoinRewriter 能注入 WHERE hf_part_no=X
--   2. 注册新 sheet "选配-料号材质" + 5 个 attribute(供 PathPickerDrawer 选)
--   3. 更新 component.fields 和 template.components_snapshot 中 5 个字段的
--      basic_data_path,从 material_recipe.X 改为 v_part_material_recipe.X
--
-- 文档: docs/选配与基础数据料号材质关系.md + RECORD.md 2026-05-17

-- ── 1. 视图 ───────────────────────────────────────────────────────────────

CREATE OR REPLACE VIEW v_part_material_recipe AS
SELECT
    mp.part_no            AS hf_part_no,
    mp.material_recipe_id AS recipe_id,
    mr.code,
    mr.symbol,
    mr.name,
    mr.spec_label,
    mr.recipe_type
FROM mat_part mp
LEFT JOIN material_recipe mr ON mr.id = mp.material_recipe_id;

COMMENT ON VIEW v_part_material_recipe IS
    '料号 → 材质字典 1:N 反查视图. 暴露 hf_part_no 让 ImplicitJoinRewriter 能按料号收窄. 详见 V181 migration.';

-- ── 2. 注册 basic_data_config + attributes ────────────────────────────────

DO $$
DECLARE
    v_config_id UUID := gen_random_uuid();
BEGIN
    -- 若已存在同名 sheet (重跑 migration / dev 重启) 则跳过
    IF EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name = '选配-料号材质' AND template_kind = 'QUOTATION'
    ) THEN
        RAISE NOTICE 'basic_data_config sheet "选配-料号材质" 已存在, 跳过 INSERT';
        RETURN;
    END IF;

    INSERT INTO basic_data_config (
        id, sheet_name, sheet_index, header_row_index, data_start_row_index,
        description, sort_order, status, template_kind, target_table, created_at, updated_at
    ) VALUES (
        v_config_id,
        '选配-料号材质',
        0, 1, 2,
        '料号绑定的材质字典(v_part_material_recipe 视图). 暴露 hf_part_no 让组件路径能按料号收窄.',
        90,
        'ACTIVE',
        'QUOTATION',
        'v_part_material_recipe',
        NOW(), NOW()
    );

    INSERT INTO basic_data_attribute
        (id, config_id, column_letter, column_title, variable_code, variable_label, data_type, status, sort_order, created_at, updated_at)
    VALUES
        (gen_random_uuid(), v_config_id, 'A', 'HF 料号',  'hf_part_no',  'HF 料号',  'IDENTIFIER', 'ACTIVE', 0, NOW(), NOW()),
        (gen_random_uuid(), v_config_id, 'B', '材质代码', 'code',        '材质代码', 'IDENTIFIER', 'ACTIVE', 1, NOW(), NOW()),
        (gen_random_uuid(), v_config_id, 'C', '化学符号', 'symbol',      '化学符号', 'VALUE',      'ACTIVE', 2, NOW(), NOW()),
        (gen_random_uuid(), v_config_id, 'D', '材质名称', 'name',        '材质名称', 'VALUE',      'ACTIVE', 3, NOW(), NOW()),
        (gen_random_uuid(), v_config_id, 'E', '规格标签', 'spec_label',  '规格标签', 'VALUE',      'ACTIVE', 4, NOW(), NOW()),
        (gen_random_uuid(), v_config_id, 'F', '配方类型', 'recipe_type', '配方类型', 'VALUE',      'ACTIVE', 5, NOW(), NOW());
END $$;

-- ── 3. 改 component.fields 路径 (仅 COMP-CFG-MATERIAL-RECIPE) ─────────────

UPDATE component
SET fields = REPLACE(fields::text, '"basic_data_path": "material_recipe.', '"basic_data_path": "v_part_material_recipe.')::jsonb,
    updated_at = NOW()
WHERE id = 'e42185ec-e180-437b-831c-b2c0480499e9';

-- ── 4. 改 template.components_snapshot 中对应组件的 fields 路径 ───────────
-- (仅"选配产品标准报价模板" b1d2e3f4 引用此组件)

UPDATE template
SET components_snapshot = REPLACE(components_snapshot::text, '"basic_data_path": "material_recipe.', '"basic_data_path": "v_part_material_recipe.')::jsonb,
    updated_at = NOW()
WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163';

-- ── 5. 自检 ───────────────────────────────────────────────────────────────

DO $$
DECLARE
    v_remaining_in_component INT;
    v_remaining_in_template INT;
    v_view_exists BOOLEAN;
BEGIN
    -- 视图存在?
    SELECT EXISTS (
        SELECT 1 FROM pg_views WHERE viewname = 'v_part_material_recipe'
    ) INTO v_view_exists;
    IF NOT v_view_exists THEN
        RAISE EXCEPTION 'V181 自检失败: 视图 v_part_material_recipe 未创建';
    END IF;

    -- component.fields 不应再有 material_recipe.X 旧路径
    SELECT COUNT(*) INTO v_remaining_in_component
    FROM component
    WHERE id = 'e42185ec-e180-437b-831c-b2c0480499e9'
      AND fields::text LIKE '%"basic_data_path": "material_recipe.%';
    IF v_remaining_in_component > 0 THEN
        RAISE EXCEPTION 'V181 自检失败: component COMP-CFG-MATERIAL-RECIPE 仍含旧路径';
    END IF;

    -- template.components_snapshot 不应再有 material_recipe.X 旧路径
    SELECT COUNT(*) INTO v_remaining_in_template
    FROM template
    WHERE id = 'b1d2e3f4-cf63-4163-8163-000000000163'
      AND components_snapshot::text LIKE '%"basic_data_path": "material_recipe.%';
    IF v_remaining_in_template > 0 THEN
        RAISE EXCEPTION 'V181 自检失败: template 选配产品标准报价模板 snapshot 仍含旧路径';
    END IF;

    RAISE NOTICE 'V181 自检通过: 视图创建 + 组件 / 模板快照路径全部已迁移到 v_part_material_recipe';
END $$;
