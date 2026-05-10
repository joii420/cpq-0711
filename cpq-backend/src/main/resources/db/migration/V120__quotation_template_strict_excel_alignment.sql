-- V120: 报价模板严格对齐报价 Excel sheet — 移除核价侧组件 + 补缺失组件
--
-- 用户要求:
--   1. 报价模板的内容只包含报价 Excel 文件的 sheet 对应组件
--   2. 移除核价侧组件 (工序成本 / 耗材包装 / 其他外加工)
--   3. 补缺失的「成品固定加工费」(报价 Excel sheet 10, 当前模板无对应组件)
--
-- 报价 Excel (data/template/报价系统功能基础数据功能结构所需字段（1.0版）.xlsx) 17 sheet:
--   1. 元素单价              ← 全局变量, 不做 tab
--   2. 客户料号与宏丰料号的关系  ✓
--   3. 来料BOM                ✓
--   4. 元素BOM                ✓
--   5. 元素回收折扣            ✓
--   6. 来料固定加工费          ✓
--   7. 来料其他费用            ✓
--   8. 来料年降                ✗ (V119 已删)
--   9. 来料回收折扣            ✓
--   10. 成品固定加工费          ⚠️ 当前模板缺 — 本次 V120 补
--   11. 成品其他费用           ✓
--   12. 组成件BOM及单价         ✓
--   13. 组装加工费              ✓
--   14. 组装加工费年降          ✗ (V119 已删)
--   15. 电镀方案                ✓
--   16. 电镀费用                ✓
--   17. 单重                    ✓
--
-- 最终报价模板 = 14 sheet tab + 1 报价单总成本 (SUBTOTAL) = 15 tab

-- ════════════════════════════════════════════════════════════════════════════
-- A. 移除核价侧组件 — 从报价模板 (template_kind='QUOTATION' 持有 COMP-Q-* 的) 解关联
-- ════════════════════════════════════════════════════════════════════════════
DELETE FROM template_component
WHERE component_id IN (
    SELECT id FROM component WHERE code IN (
        'COMP-Q-PROCESS-COST',     -- 工序成本 (核价侧 LABOR/能耗等, 报价 Excel 无)
        'COMP-Q-CONSUMABLE',       -- 耗材包装 (核价侧, 报价 Excel 无)
        'COMP-Q-OUTSOURCE'         -- 其他外加工 (核价侧, 报价 Excel 无)
    )
)
AND template_id IN (
    SELECT id FROM template WHERE template_kind='QUOTATION'
);

-- ════════════════════════════════════════════════════════════════════════════
-- B. 创建组件 COMP-Q-FINISHED-FIXED 「成品固定加工费」
--    数据源: mat_fee[fee_type='FINISHED_FIXED']
--    Excel 列: A 宏丰料号 / B 序号 / C 值 / D 比例(%) / E 货币 / F 计价单位
-- ════════════════════════════════════════════════════════════════════════════
INSERT INTO component (id, code, name, component_type, directory_id, fields, formulas, data_driver_path, status, column_count, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'COMP-Q-FINISHED-FIXED',
    '成品固定加工费',
    'NORMAL',
    'c1d2e3f4-0001-4001-8001-000000000001'::uuid,
    $JSON$[
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='FINISHED_FIXED'].seq_no"},
        {"name":"值","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='FINISHED_FIXED'].fee_value"},
        {"name":"比例(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='FINISHED_FIXED'].fee_ratio"},
        {"name":"货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='FINISHED_FIXED'].currency"},
        {"name":"计价单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='FINISHED_FIXED'].price_unit"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    'mat_fee[fee_type=''FINISHED_FIXED'']',
    'ACTIVE', 5, now(), now())
ON CONFLICT (code) DO UPDATE SET
    fields = EXCLUDED.fields,
    formulas = EXCLUDED.formulas,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count = EXCLUDED.column_count,
    updated_at = now();

-- ════════════════════════════════════════════════════════════════════════════
-- C. 把 COMP-Q-FINISHED-FIXED 加到所有报价模板的 tab 列表 (插入到「成品其他费用」之前)
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_tpl       RECORD;
    v_comp_id   UUID;
    v_other_sort INT;
BEGIN
    SELECT id INTO v_comp_id FROM component WHERE code = 'COMP-Q-FINISHED-FIXED';

    FOR v_tpl IN
        SELECT DISTINCT t.id
        FROM template t
        JOIN template_component tc ON tc.template_id = t.id
        JOIN component c ON c.id = tc.component_id
        WHERE c.code LIKE 'COMP-Q-%' AND t.template_kind = 'QUOTATION'
    LOOP
        -- 已存在不重复加
        IF EXISTS (
            SELECT 1 FROM template_component
            WHERE template_id = v_tpl.id AND component_id = v_comp_id
        ) THEN
            CONTINUE;
        END IF;

        -- 找「成品其他费用」位置, 在它之前插入 (sort_order = 该位置 - 0.5)
        SELECT tc.sort_order INTO v_other_sort
        FROM template_component tc
        JOIN component c ON c.id = tc.component_id
        WHERE tc.template_id = v_tpl.id AND c.code = 'COMP-Q-FINISHED-OTHER';

        IF v_other_sort IS NULL THEN
            -- 没有「成品其他费用」就追加到末尾 (在 SUBTOTAL 之前)
            SELECT COALESCE(MAX(tc.sort_order), 0) - 1 INTO v_other_sort
            FROM template_component tc
            JOIN component c ON c.id = tc.component_id
            WHERE tc.template_id = v_tpl.id AND c.component_type = 'SUBTOTAL';
        END IF;

        INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, created_at)
        VALUES (gen_random_uuid(), v_tpl.id, v_comp_id, '成品固定加工费',
                COALESCE(v_other_sort, 0) - 1,  -- 直接放到 FINISHED_OTHER 之前 (sort_order 小)
                now());
        RAISE NOTICE 'V120-C: 模板 % 加入「成品固定加工费」 (sort_order=%)', v_tpl.id, COALESCE(v_other_sort, 0) - 1;
    END LOOP;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- D. 重整 sort_order 让所有报价模板的 tab 顺序按 Excel sheet 业务顺序连续
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_tpl       RECORD;
    v_tc        RECORD;
    v_new_sort  INT;
    v_order_map jsonb := '{
        "COMP-Q-CUSTOMER-MAPPING": 10,
        "COMP-Q-RAW-BOM":          20,
        "COMP-Q-ELEMENT-BOM":      30,
        "COMP-Q-ELEMENT-RECYCLE":  40,
        "COMP-Q-MATERIAL-FEE":     50,
        "COMP-Q-MATERIAL-OTHER":   60,
        "COMP-Q-MATERIAL-RECYCLE": 70,
        "COMP-Q-FINISHED-FIXED":   80,
        "COMP-Q-FINISHED-OTHER":   90,
        "COMP-Q-COMPONENT-BOM":   100,
        "COMP-Q-ASSEMBLY-FEE":    110,
        "COMP-Q-PLATING-SCHEME":  120,
        "COMP-Q-PLATING-COST":    130,
        "COMP-Q-WEIGHT":          140,
        "COMP-Q-TOTAL":           150
    }'::jsonb;
BEGIN
    FOR v_tpl IN
        SELECT DISTINCT t.id
        FROM template t
        JOIN template_component tc ON tc.template_id = t.id
        JOIN component c ON c.id = tc.component_id
        WHERE c.code LIKE 'COMP-Q-%' AND t.template_kind = 'QUOTATION'
    LOOP
        FOR v_tc IN
            SELECT tc.id, c.code FROM template_component tc
            JOIN component c ON c.id = tc.component_id
            WHERE tc.template_id = v_tpl.id
        LOOP
            v_new_sort := COALESCE((v_order_map->>v_tc.code)::int, 999);
            UPDATE template_component SET sort_order = v_new_sort WHERE id = v_tc.id;
        END LOOP;
        RAISE NOTICE 'V120-D: 模板 % 重排 sort_order', v_tpl.id;
    END LOOP;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- E. 重建 components_snapshot
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE v_tpl RECORD;
BEGIN
    FOR v_tpl IN
        SELECT DISTINCT t.id
        FROM template t
        JOIN template_component tc ON tc.template_id = t.id
        JOIN component c ON c.id = tc.component_id
        WHERE c.code LIKE 'COMP-Q-%' AND t.template_kind = 'QUOTATION'
    LOOP
        UPDATE template
        SET components_snapshot = (
            SELECT jsonb_agg(jsonb_build_object(
                'id', tc.id,
                'componentId', tc.component_id,
                'tabName', tc.tab_name,
                'sortOrder', tc.sort_order,
                'componentCode', c.code,
                'componentName', c.name,
                'componentType', c.component_type,
                'data_driver_path', c.data_driver_path,
                'fields', c.fields,
                'formulas', c.formulas
            ) ORDER BY tc.sort_order)
            FROM template_component tc
            JOIN component c ON c.id = tc.component_id
            WHERE tc.template_id = v_tpl.id
        ),
        updated_at = now()
        WHERE id = v_tpl.id;
        RAISE NOTICE 'V120-E: 重建 % snapshot', v_tpl.id;
    END LOOP;
END $$;
