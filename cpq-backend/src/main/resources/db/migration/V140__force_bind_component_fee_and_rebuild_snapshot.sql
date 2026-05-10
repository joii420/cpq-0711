-- V140: 强制把 COMP-QX-COMPONENT-FEE 绑定到「报价标准模板-Excel基础结构 v1.0」
--       + 重建该模板的 components_snapshot (因模板状态 PUBLISHED, snapshot 冻结需手动重建)
--
-- 起因: V139 创建了 component 但模板绑定 (template_component INSERT) 实测未生效
--       (可能 Flyway 事务内 DO 块某条件导致 RETURN/SKIP). API GET template/{id}
--       仍返 8 个组件 (sort 0-7, 第 8 个是历史"小计1"), 没有"组件费用"。
--       同时模板 status=PUBLISHED 时 components_snapshot 字段已被冻结, 即使 template_component
--       表 INSERT 成功, snapshot 也不会自动更新 (publish 流程才生成 snapshot, 见
--       TemplateService.java:200-223). publish 流程不会被 Flyway 触发, 须手动重建.

-- ── 步骤 1: 确保 template_component 含 COMP-QX-COMPONENT-FEE 绑定 ───────────
DO $$
DECLARE
    v_tpl_id   UUID;
    v_comp_id  UUID;
    v_existing INT;
BEGIN
    SELECT id INTO v_tpl_id FROM template
     WHERE name = '报价标准模板-Excel基础结构 v1.0' AND template_kind = 'QUOTATION'
     LIMIT 1;
    IF v_tpl_id IS NULL THEN
        RAISE EXCEPTION 'V140: 未找到模板「报价标准模板-Excel基础结构 v1.0」';
    END IF;

    SELECT id INTO v_comp_id FROM component WHERE code = 'COMP-QX-COMPONENT-FEE' LIMIT 1;
    IF v_comp_id IS NULL THEN
        RAISE EXCEPTION 'V140: 组件 COMP-QX-COMPONENT-FEE 未找到';
    END IF;

    SELECT COUNT(*) INTO v_existing FROM template_component
     WHERE template_id = v_tpl_id AND component_id = v_comp_id;

    IF v_existing = 0 THEN
        INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, created_at)
        VALUES (gen_random_uuid(), v_tpl_id, v_comp_id, '组件费用', 8, now());
        RAISE NOTICE 'V140: 已绑定「组件费用」(sort_order=8) 到模板';
    ELSE
        RAISE NOTICE 'V140: 已存在绑定, 跳过 (existing=%)', v_existing;
    END IF;
END $$;

-- ── 步骤 2: 重建该模板的 components_snapshot ─────────────────────────────────
-- 模仿 TemplateService.publish line 200-223 的 snapshot 结构
UPDATE template t
SET components_snapshot = (
    SELECT jsonb_agg(
        jsonb_build_object(
            'id',                  tc.id::text,
            'componentId',         c.id::text,
            'componentName',       c.name,
            'componentCode',       c.code,
            'componentType',       c.component_type,
            'tabName',             tc.tab_name,
            'sortOrder',           tc.sort_order,
            'fields',              c.fields,
            'formulas',            c.formulas,
            'preset_rows',         COALESCE(tc.preset_rows, '[]'::jsonb),
            'data_driver_path',    c.data_driver_path,
            'formula_assignments', COALESCE(tc.formula_assignments, '{}'::jsonb)
        ) ORDER BY tc.sort_order ASC
    )
    FROM template_component tc
    JOIN component c ON c.id = tc.component_id
    WHERE tc.template_id = t.id
)
WHERE t.name = '报价标准模板-Excel基础结构 v1.0'
  AND t.template_kind = 'QUOTATION';

DO $$
DECLARE
    v_snap_count INT;
BEGIN
    SELECT jsonb_array_length(components_snapshot) INTO v_snap_count
      FROM template
     WHERE name = '报价标准模板-Excel基础结构 v1.0' AND template_kind = 'QUOTATION';
    RAISE NOTICE 'V140: components_snapshot 已重建, 含 % 个组件 (期望 9: 原 8 + 组件费用)', v_snap_count;
END $$;
