-- V199: 修补 v1.3 (ab826fea) + 通用化:扫描所有"组合产品"系列<7组件的版本统一拉齐
--
-- 背景 (2026-05-19 QT-20260519-1412 排查续):
--   V198 修补完 v1.1/v1.2 后,用户手动点击「以此模板为基础新建版本」生成了 v1.3
--   (ab826fea-2f4a-4844-af99-4d1de9930c8e). 但 v1.3 publish 流程从 component
--   表重建 snapshot,丢失 V195 在 snapshot 字面写入的 v_composite_child_*
--   driver_path 覆盖. 而且 template_component 里 sort_order 是 [0,1,2,3,6],
--   说明 4(子配件)/5(单重) 被用户在 draft 阶段手动删除,导致 7→5 组件退化.
--
-- 长期架构修复 (待办,不在本迁移内):
--   - 给 template_component 加 data_driver_path_override / fields_override JSONB 列
--   - 改 publish 流程: snapshot = component 基础字段 + template_component 的 override
--   - 这样每次派生新版本不会丢失 V195/V198 的语义
--
-- 本迁移做什么:
--   1) 通用化扫描: WHERE name='选配产品标准报价模板-组合产品' AND
--      (status='PUBLISHED' OR status='DRAFT') AND
--      jsonb_array_length(components_snapshot) < 7
--   2) 把 v1.0 (b1d2e3f4...163) 的 components_snapshot 整体覆盖到 stale 版本
--   3) 同步 template_component 关联表 (DELETE + INSERT)
--   4) 自检:目标版本 snapshot 长度 = 7 且含 v_composite_child_*

DO $main$
DECLARE
    v_v10_id UUID := 'b1d2e3f4-cf63-4163-8163-000000000163';
    v_v10_snapshot JSONB;
    v_target_id UUID;
    v_target_count INT := 0;
BEGIN
    SELECT components_snapshot INTO v_v10_snapshot
    FROM template WHERE id = v_v10_id;
    IF v_v10_snapshot IS NULL OR jsonb_array_length(v_v10_snapshot) <> 7 THEN
        RAISE EXCEPTION 'V199: v1.0 模板 % snapshot 长度异常 (期望 7)', v_v10_id;
    END IF;

    FOR v_target_id IN
        SELECT id FROM template
        WHERE name = '选配产品标准报价模板-组合产品'
          AND id <> v_v10_id
          AND status IN ('PUBLISHED', 'DRAFT')
          AND (
              components_snapshot IS NULL
              OR jsonb_array_length(components_snapshot) < 7
              OR components_snapshot::text NOT LIKE '%v_composite_child_materials%'
          )
    LOOP
        -- 覆盖 snapshot
        UPDATE template
        SET components_snapshot = v_v10_snapshot, updated_at = NOW()
        WHERE id = v_target_id;

        -- 重建 template_component
        DELETE FROM template_component WHERE template_id = v_target_id;
        INSERT INTO template_component (
            id, template_id, component_id, tab_name, sort_order,
            preset_rows, formula_assignments, created_at
        )
        SELECT
            gen_random_uuid(), v_target_id, component_id, tab_name, sort_order,
            preset_rows, formula_assignments, NOW()
        FROM template_component
        WHERE template_id = v_v10_id;

        v_target_count := v_target_count + 1;
        RAISE NOTICE 'V199: 已拉齐组合产品模板 % 到 7 组件 + v_composite_child_*', v_target_id;
    END LOOP;

    IF v_target_count = 0 THEN
        RAISE NOTICE 'V199: 无 stale 组合产品模板需要修补 (扫描通过)';
    ELSE
        RAISE NOTICE 'V199: 共修补 % 份 stale 组合产品模板', v_target_count;
    END IF;

    -- 自检: ab826fea 必修
    IF EXISTS (SELECT 1 FROM template WHERE id = 'ab826fea-2f4a-4844-af99-4d1de9930c8e') THEN
        IF NOT (
            (SELECT jsonb_array_length(components_snapshot) = 7
                AND components_snapshot::text LIKE '%v_composite_child_materials%'
             FROM template WHERE id = 'ab826fea-2f4a-4844-af99-4d1de9930c8e')
        ) THEN
            RAISE EXCEPTION 'V199 自检失败: v1.3 (ab826fea) 未被修补';
        END IF;
    END IF;
END
$main$;
