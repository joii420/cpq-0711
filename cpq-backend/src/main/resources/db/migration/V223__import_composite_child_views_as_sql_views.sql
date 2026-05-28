-- V218: 备份性导入 v_composite_child_* 物理视图 SQL 到三个核心选配组件的 component_sql_view
--
-- 背景：用户要求"将选配模板组件目录下所有组件使用的 PG 物理视图维护到组件 SQL 视图管理中"
-- 实施方向：X（备份性导入，与 2026-05-26 RECORD 阶段 4 决策对齐）
--
-- 核心约束（不变）：
--   1. 不动 component.dataDriverPath —— 仍走 v_composite_child_* 物理视图
--   2. 不动 ComponentDriverService 三分支策略（contains("v_composite_child_") 硬编码保留）
--   3. 不动 V202 智能视图自适应（基线 §10.1.1）
--   4. 0 风险破坏既有 AP-45 修复
--
-- 本迁移效果：
--   - 三个核心组件的「SQL 视图」Tab 显示视图 SQL 镜像（status=ACTIVE，作"参考/备份"）
--   - 用户在 UI 上能完整看到视图定义内容
--   - 运行时仍走 PG 物理视图，无切换
--   - 后续新组件可按"全部用 SQL 视图"指示工作（本视图作为 reference 模板）
--
-- 关联文档：docs/RECORD.md [2026-05-26] 阶段 4 / 方向 X 决策

-- ============================================================
-- Helper 函数: 从 information_schema 拿视图 SQL + 列签名
-- ============================================================

DO $BODY$
DECLARE
    v_material_id UUID;
    v_element_id UUID;
    v_process_id UUID;
    v_materials_sql TEXT;
    v_elements_sql TEXT;
    v_processes_sql TEXT;
    v_weights_sql TEXT;
    v_materials_cols TEXT;
    v_elements_cols TEXT;
    v_processes_cols TEXT;
    v_weights_cols TEXT;
BEGIN
    -- ── 1. 找三个核心组件的 ID ──────────────────────────────────────
    SELECT id INTO v_material_id FROM component
        WHERE code = 'COMP-CFG-MATERIAL-RECIPE' LIMIT 1;
    SELECT id INTO v_element_id FROM component
        WHERE code = 'COMP-CFG-ELEMENT-BOM' LIMIT 1;
    SELECT id INTO v_process_id FROM component
        WHERE code = 'COMP-CFG-PROCESS' LIMIT 1;

    IF v_material_id IS NULL OR v_element_id IS NULL OR v_process_id IS NULL THEN
        RAISE NOTICE 'V218 SKIP: 核心组件未全部存在 (material=%, element=%, process=%)',
            v_material_id, v_element_id, v_process_id;
        RETURN;
    END IF;

    -- ── 2. 拿 4 张视图当前生效的 SQL 定义（applied V196 + V202 + V207~V209 后的最终形态）
    SELECT view_definition INTO v_materials_sql FROM information_schema.views
        WHERE table_schema = 'public' AND table_name = 'v_composite_child_materials';
    SELECT view_definition INTO v_elements_sql FROM information_schema.views
        WHERE table_schema = 'public' AND table_name = 'v_composite_child_elements';
    SELECT view_definition INTO v_processes_sql FROM information_schema.views
        WHERE table_schema = 'public' AND table_name = 'v_composite_child_processes';
    SELECT view_definition INTO v_weights_sql FROM information_schema.views
        WHERE table_schema = 'public' AND table_name = 'v_composite_child_weights';

    IF v_materials_sql IS NULL OR v_elements_sql IS NULL
       OR v_processes_sql IS NULL OR v_weights_sql IS NULL THEN
        RAISE NOTICE 'V218 SKIP: 物理视图未全部存在';
        RETURN;
    END IF;

    -- ── 3. 拿 4 张视图的列签名（information_schema.columns）
    SELECT json_agg(json_build_object(
                'name', column_name,
                'dataType', udt_name,
                'nullable', is_nullable = 'YES'
            ) ORDER BY ordinal_position)::text
    INTO v_materials_cols
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'v_composite_child_materials';

    SELECT json_agg(json_build_object(
                'name', column_name,
                'dataType', udt_name,
                'nullable', is_nullable = 'YES'
            ) ORDER BY ordinal_position)::text
    INTO v_elements_cols
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'v_composite_child_elements';

    SELECT json_agg(json_build_object(
                'name', column_name,
                'dataType', udt_name,
                'nullable', is_nullable = 'YES'
            ) ORDER BY ordinal_position)::text
    INTO v_processes_cols
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'v_composite_child_processes';

    SELECT json_agg(json_build_object(
                'name', column_name,
                'dataType', udt_name,
                'nullable', is_nullable = 'YES'
            ) ORDER BY ordinal_position)::text
    INTO v_weights_cols
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'v_composite_child_weights';

    -- ── 4. 导入 v_composite_child_materials → COMP-CFG-MATERIAL-RECIPE
    INSERT INTO component_sql_view (
        id, component_id, sql_view_name, sql_template, declared_columns,
        required_variables, scope, status, description, created_at, updated_at
    ) VALUES (
        gen_random_uuid(), v_material_id, 'composite_child_materials_mirror',
        v_materials_sql, v_materials_cols::jsonb, ARRAY[]::TEXT[],
        'COMPONENT', 'ACTIVE',
        'V218 备份性导入：v_composite_child_materials 物理视图 SQL 镜像。' ||
        '⚠️ 仅作参考/备份用途；当前组件 dataDriverPath 仍指向 v_composite_child_materials 物理视图。' ||
        'ComponentDriverService 三分支策略对 v_composite_child_* 字符串硬编码依赖 (基线 §10.1.1)，' ||
        '不切换 dataDriverPath = 不会撞 AP-45 复发风险。',
        now(), now()
    )
    ON CONFLICT (component_id, sql_view_name) DO UPDATE
        SET sql_template = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at = now();

    -- ── 5. 导入 v_composite_child_elements → COMP-CFG-ELEMENT-BOM
    INSERT INTO component_sql_view (
        id, component_id, sql_view_name, sql_template, declared_columns,
        required_variables, scope, status, description, created_at, updated_at
    ) VALUES (
        gen_random_uuid(), v_element_id, 'composite_child_elements_mirror',
        v_elements_sql, v_elements_cols::jsonb, ARRAY[]::TEXT[],
        'COMPONENT', 'ACTIVE',
        'V218 备份性导入：v_composite_child_elements 物理视图 SQL 镜像。' ||
        '⚠️ 仅作参考/备份用途；当前组件 dataDriverPath 仍指向 v_composite_child_elements 物理视图。',
        now(), now()
    )
    ON CONFLICT (component_id, sql_view_name) DO UPDATE
        SET sql_template = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at = now();

    -- ── 6. 导入 v_composite_child_processes → COMP-CFG-PROCESS
    INSERT INTO component_sql_view (
        id, component_id, sql_view_name, sql_template, declared_columns,
        required_variables, scope, status, description, created_at, updated_at
    ) VALUES (
        gen_random_uuid(), v_process_id, 'composite_child_processes_mirror',
        v_processes_sql, v_processes_cols::jsonb, ARRAY[]::TEXT[],
        'COMPONENT', 'ACTIVE',
        'V218 备份性导入：v_composite_child_processes 物理视图 SQL 镜像。' ||
        '⚠️ 仅作参考/备份用途；当前组件 dataDriverPath 仍指向 v_composite_child_processes 物理视图。' ||
        'V207/V208/V209 给本视图加 quotation_line_item_id 列 + main_only 过滤 — 关联 Bug B 三分支策略。',
        now(), now()
    )
    ON CONFLICT (component_id, sql_view_name) DO UPDATE
        SET sql_template = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at = now();

    -- ── 7. 导入 v_composite_child_weights → COMP-CFG-MATERIAL-RECIPE (作 GLOBAL scope，可跨组件 BNF 引用)
    INSERT INTO component_sql_view (
        id, component_id, sql_view_name, sql_template, declared_columns,
        required_variables, scope, status, description, created_at, updated_at
    ) VALUES (
        gen_random_uuid(), v_material_id, 'composite_child_weights_mirror',
        v_weights_sql, v_weights_cols::jsonb, ARRAY[]::TEXT[],
        'GLOBAL', 'ACTIVE',
        'V218 备份性导入：v_composite_child_weights 物理视图 SQL 镜像。' ||
        '挂在 COMP-CFG-MATERIAL-RECIPE 组件下作为 GLOBAL scope 共享 SQL 视图，' ||
        '其他组件可通过双美元符前缀(跨组件 BNF 引用语法) <componentCode>.composite_child_weights_mirror 引用。' ||
        '⚠️ 当前组件配置未引用此视图；后续新组件需要"子件单重"数据时可用作模板。',
        now(), now()
    )
    ON CONFLICT (component_id, sql_view_name) DO UPDATE
        SET sql_template = EXCLUDED.sql_template,
            declared_columns = EXCLUDED.declared_columns,
            updated_at = now();

    RAISE NOTICE 'V218: imported 4 SQL view mirrors (3 COMPONENT + 1 GLOBAL) for 3 core composite components';
END $BODY$;

-- ============================================================
-- 验证：列出导入结果
-- ============================================================
DO $BODY$
DECLARE
    cnt INT;
BEGIN
    SELECT count(*) INTO cnt FROM component_sql_view
        WHERE sql_view_name LIKE 'composite_child_%_mirror' AND status = 'ACTIVE';
    IF cnt < 4 THEN
        RAISE NOTICE 'V218 WARN: expected >= 4 mirror SQL views, found %', cnt;
    ELSE
        RAISE NOTICE 'V218 OK: % mirror SQL views ACTIVE', cnt;
    END IF;
END $BODY$;
