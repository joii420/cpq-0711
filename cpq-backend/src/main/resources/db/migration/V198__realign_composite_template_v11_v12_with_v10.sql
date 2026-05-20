-- V198: 修补 b3b0b65f / 2d196350 "选配产品标准报价模板-组合产品 v1.1/v1.2" 配置错位
--
-- 背景 (2026-05-19 QT-20260519-1410 排查):
--   两个名为「组合产品」的派生模板 (v1.1=2d196350, v1.2=b3b0b65f) 实际继承自
--   单一产品系列 (series=000000000164), 只有 5 个组件、driver_path 用直接物理表
--   (mat_bom, mat_process). 用于 COMPOSITE 产品时:
--     - 父级 hf_part_no 直接查 mat_bom/mat_process 全部返 0 行
--     - auto-hide 把 4 个空 Tab 全部隐藏 → 用户只看到「组合工艺」一个 Tab
--   而 v1.0 (b1d2e3f4...163) 的 V195 已把 4 个 Tab 改用 v_composite_child_* 聚合视图,
--   配 7 组件 (含 CHILD-PARTS + WEIGHT) — 这才是真组合产品模板.
--
-- 修复策略:
--   1) 把 v1.0 (b1d2e3f4...163) 的 components_snapshot 整体覆盖到 v1.1/v1.2 模板.
--      包含 V195 的 v_composite_child_* 路径 + 7 个组件 (CHILD-PARTS + WEIGHT 已补齐).
--   2) 同步重建 template_component 关联表 — 删 v1.1/v1.2 的旧 5 行, 复制 v1.0 的 7 行 link.
--   3) 保留各自的 id / version / created_at / customer_id 等元数据不变.
--
-- 影响范围:
--   - QT-20260519-1410 这类已用 b3b0b65f 的草稿: 重进 Step2 后会看到 v_composite_child_*
--     聚合的子件数据 (材质/元素/工序/单重 4 个 Tab + CHILD-PARTS 子配件 Tab).
--   - SIMPLE 产品 (e.g. CFG-AuAg-000001) 用同模板时: v_composite_child_*  视图依靠
--     mat_bom.bom_type='ASSEMBLY' 行筛, SIMPLE 产品没 ASSEMBLY 行 → 4 个 Tab 返 0
--     行 → 走 auto-hide 一并隐藏. 不退化 — 5 组件的旧 SIMPLE 行为也是 0 数据.
--     (建议用户后续把 SIMPLE 产品换成单一产品模板 af4a834f.)
--
-- 自检:
--   - 两个目标模板 components_snapshot 长度 = 7
--   - components_snapshot 含 'v_composite_child_materials'
--   - template_component 每个目标模板有 7 行

DO $main$
DECLARE
    v_v10_id UUID := 'b1d2e3f4-cf63-4163-8163-000000000163';  -- 标准 v1.0
    v_target_ids UUID[] := ARRAY[
        '2d196350-2096-402d-bb1b-119cb2dec9bc'::UUID,  -- v1.1
        'b3b0b65f-d201-45b0-94c7-caef352d4398'::UUID   -- v1.2
    ];
    v_target UUID;
    v_v10_snapshot JSONB;
    v_snap_len INT;
    v_link_count INT;
BEGIN
    SELECT components_snapshot INTO v_v10_snapshot
    FROM template WHERE id = v_v10_id;
    IF v_v10_snapshot IS NULL THEN
        RAISE EXCEPTION 'V198: v1.0 组合产品模板 % 不存在', v_v10_id;
    END IF;
    IF jsonb_array_length(v_v10_snapshot) <> 7 THEN
        RAISE EXCEPTION 'V198: v1.0 模板 components_snapshot 长度=%, 期望 7', jsonb_array_length(v_v10_snapshot);
    END IF;

    FOREACH v_target IN ARRAY v_target_ids
    LOOP
        -- 仅当模板存在才操作 (兼容某些环境只创建过 v1.2 没 v1.1)
        IF NOT EXISTS (SELECT 1 FROM template WHERE id = v_target) THEN
            RAISE NOTICE 'V198: 目标模板 % 不存在, 跳过', v_target;
            CONTINUE;
        END IF;

        -- 1. 覆盖 components_snapshot
        UPDATE template
        SET components_snapshot = v_v10_snapshot,
            updated_at = NOW()
        WHERE id = v_target;

        -- 2. 重建 template_component 关联表
        DELETE FROM template_component WHERE template_id = v_target;

        INSERT INTO template_component (
            id, template_id, component_id, tab_name, sort_order,
            preset_rows, formula_assignments, created_at
        )
        SELECT
            gen_random_uuid(),
            v_target,
            component_id,
            tab_name,
            sort_order,
            preset_rows,
            formula_assignments,
            NOW()
        FROM template_component
        WHERE template_id = v_v10_id;

        SELECT COUNT(*) INTO v_link_count
        FROM template_component WHERE template_id = v_target;
        IF v_link_count <> 7 THEN
            RAISE EXCEPTION 'V198: 目标模板 % template_component 行数=%, 期望 7', v_target, v_link_count;
        END IF;
        RAISE NOTICE 'V198: 已修补模板 % — snapshot 7 组件 + link 7 行', v_target;
    END LOOP;

    -- 3. 自检 - 路径含 v_composite_child_*
    SELECT jsonb_array_length(components_snapshot) INTO v_snap_len
    FROM template WHERE id = 'b3b0b65f-d201-45b0-94c7-caef352d4398';
    IF v_snap_len <> 7 THEN
        RAISE EXCEPTION 'V198 自检失败: b3b0b65f snapshot 长度=%, 期望 7', v_snap_len;
    END IF;
    IF NOT (
        (SELECT components_snapshot::text FROM template WHERE id = 'b3b0b65f-d201-45b0-94c7-caef352d4398')
        LIKE '%v_composite_child_materials%'
    ) THEN
        RAISE EXCEPTION 'V198 自检失败: b3b0b65f snapshot 未含 v_composite_child_materials';
    END IF;

    RAISE NOTICE 'V198 自检通过: v1.1/v1.2 组合产品模板已拉齐到 v1.0 (7 组件 + v_composite_child_* 路径)';
END
$main$;
