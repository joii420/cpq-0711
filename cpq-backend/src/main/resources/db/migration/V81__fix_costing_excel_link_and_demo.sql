-- V81: 修复「核价-汇总演示模板」的关联 + 补 3 个料号的 demo summary
--
-- 问题:
--   1) V80 把 Excel 模板的 linked_template_id 指向了「默认核价模板 v1.2」(2fbe064e),
--      但用户当前的报价单 QT-20260505-1335 实际绑的是「核价-演示模板 v1.2」(d5f4dab0)
--      LinkedExcelView 按 costingCardTemplateId 反查 -> 0 命中 -> 显示"未找到关联的 Excel 模板"
--   2) V80 只插了 hf_part_no='3100080003' 的 demo summary, 但报价单的 lineItem 料号是
--      3100090136 / 3120012574 / 3120012575 -> 视图全 NULL, demo 效果不可见
--
-- 修复:
--   1) 切 Excel 模板 linked_template_id -> d5f4dab0(用户报价单实际用的核价模板)
--   2) 给 3 个 lineItem 料号各插一条 PUBLISHED summary + 7 metric (CS-DEMO-0002/0003/0004)
--      数值有所差异以演示 Excel 视图能区分行

-- ========================================================
-- 1) 修复 Excel 模板关联
--    把「核价-汇总演示模板」(0a8441c0) 的 linked_template_id 切到 d5f4dab0
-- ========================================================
UPDATE costing_template
SET linked_template_id = 'd5f4dab0-0f96-47c1-8f62-5455ab50244f',
    updated_at = now()
WHERE id = '0a8441c0-1f15-4d97-ba83-66cd5c880ed7';

-- ========================================================
-- 2) 给 lineItem 的 3 个料号各插一条演示 summary + 7 metric
-- ========================================================
DO $$
DECLARE
    v_element_id  UUID;
    v_material_id UUID;
    v_exchange_id UUID;
    r RECORD;
    v_summary_id UUID;
BEGIN
    SELECT id INTO v_element_id  FROM costing_price_version
        WHERE version_kind='ELEMENT'  AND is_default = true AND status = 'PUBLISHED' LIMIT 1;
    SELECT id INTO v_material_id FROM costing_price_version
        WHERE version_kind='MATERIAL' AND is_default = true AND status = 'PUBLISHED' LIMIT 1;
    SELECT id INTO v_exchange_id FROM costing_price_version
        WHERE version_kind='EXCHANGE' AND is_default = true AND status = 'PUBLISHED' LIMIT 1;

    IF v_element_id IS NULL OR v_material_id IS NULL OR v_exchange_id IS NULL THEN
        RAISE NOTICE 'V81 demo: 缺少默认 ELEMENT/MATERIAL/EXCHANGE version, 跳过演示数据插入';
        RETURN;
    END IF;

    FOR r IN
        SELECT * FROM (VALUES
            ('CS-DEMO-0002', '3100090136', 102, 7,  3, 1),
            ('CS-DEMO-0003', '3120012574', 88,  4,  2, 1),
            ('CS-DEMO-0004', '3120012575', 76,  6,  2, 0)
        ) AS t(no, part_no, mat, proc, tool, des)
    LOOP
        IF EXISTS (SELECT 1 FROM costing_summary WHERE summary_no = r.no) THEN
            CONTINUE;
        END IF;
        v_summary_id := gen_random_uuid();
        INSERT INTO costing_summary (
            id, summary_no, hf_part_no,
            element_version_id, material_version_id, exchange_version_id,
            status, quote_currency, notes,
            created_at, updated_at, computed_at, published_at
        ) VALUES (
            v_summary_id, r.no, r.part_no,
            v_element_id, v_material_id, v_exchange_id,
            'PUBLISHED', 'CNY', '汇总演示数据 (V81)',
            now(), now(), now(), now()
        );
        INSERT INTO costing_summary_result (id, summary_id, metric_code, metric_label, value, currency, sort_order)
        VALUES
          (gen_random_uuid(), v_summary_id, 'MATERIAL_COST',    '材料成本',           r.mat,             'CNY', 1),
          (gen_random_uuid(), v_summary_id, 'PROCESS_FEE',      '加工费',              r.proc,            'CNY', 2),
          (gen_random_uuid(), v_summary_id, 'TOOLING_FEE',      '模具工装费',          r.tool,            'CNY', 3),
          (gen_random_uuid(), v_summary_id, 'DESIGN_COST',      '设计成本',            r.des,             'CNY', 4),
          (gen_random_uuid(), v_summary_id, 'UNIT_TOTAL_COST',  '单位总成本',          r.mat+r.proc+r.tool+r.des, 'CNY', 5),
          (gen_random_uuid(), v_summary_id, 'UNIT_TOTAL_QUOTE', '单位总成本(报价币种)', r.mat+r.proc+r.tool+r.des, 'CNY', 6),
          (gen_random_uuid(), v_summary_id, 'UNIT_PER_PCS',     '单件成本',            (r.mat+r.proc+r.tool+r.des) * 0.0001, 'CNY', 7);
    END LOOP;
END $$;
