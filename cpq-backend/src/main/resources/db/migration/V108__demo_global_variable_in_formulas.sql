-- V108: 演示性配置 — 全局变量同时用于 Excel 模板公式 & 组件公式
--
-- 目的: 用户验证"全局变量在两处都能用". 选两个最小可验证的接入点:
--
--   A. Excel 模板「核价 Excel 视图模板（完整公式版）v1.2」(cd58c933)
--      列 S "核价汇率(CNY→USD)" 当前路径: v_costing_summary_full.exchange_rate_to_usd
--      改为直接走全局变量 EXCHANGE_RATE: v_costing_exchange_rate[...].costing_rate
--      意义: 跳过 part-level summary 中转, 直接读全局变量 — 用户在「全局变量配置」改汇率
--           将立即影响所有 DRAFT 报价单的列 T(总成本 USD/KG) 与 U(总成本 USD/PCS) 计算
--
--   B. 组件 COMP-V4-PLATING-COST 加一个新 FORMULA 字段「电镀成本(USD)」
--      公式: 电镀成本 × global_variable(EXCHANGE_RATE, CNY→USD)
--      意义: 演示组件公式 token 系统直接消费全局变量 (无需走 BASIC_DATA path)

-- ═════════════════════════════════════════════════════════════════════
-- A. Excel 模板列 S 切到全局变量
-- ═════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_template_id UUID;
    v_columns JSONB;
    v_new_columns JSONB;
BEGIN
    -- 取目标 v1.2 模板
    SELECT id, columns INTO v_template_id, v_columns
    FROM costing_template
    WHERE id = 'cd58c933-119f-40f2-9a79-a571bb8cebf0';
    IF v_template_id IS NULL THEN
        RAISE NOTICE 'V108-A: 目标模板不存在, 跳过';
        RETURN;
    END IF;

    -- 用 jsonb_set / map 改列 S 的 variable_path
    -- 使用 jsonb_array_elements 重建数组
    SELECT jsonb_agg(
        CASE
            WHEN col->>'col_key' = 'S' THEN
                col || jsonb_build_object(
                    'variable_path', $j$v_costing_exchange_rate[from_currency='CNY' AND to_currency='USD'].costing_rate$j$,
                    'title', '核价汇率(CNY→USD) [全局变量]'
                )
            ELSE col
        END
        ORDER BY ord
    )
    INTO v_new_columns
    FROM jsonb_array_elements(v_columns) WITH ORDINALITY t(col, ord);

    UPDATE costing_template
    SET columns = v_new_columns,
        updated_at = now()
    WHERE id = v_template_id;
    RAISE NOTICE 'V108-A: Excel 模板 % 列 S 已切到全局变量 EXCHANGE_RATE', v_template_id;
END $$;

-- ═════════════════════════════════════════════════════════════════════
-- B. COMP-V4-PLATING-COST 加「电镀成本(USD)」FORMULA 字段
-- ═════════════════════════════════════════════════════════════════════
UPDATE component
SET fields = $JSON$[
        {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.plating_plan_code"},
        {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.plan_version"},
        {"name":"电镀加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"plating_fee.plating_process_fee"},
        {"name":"电镀材料费","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":false,"notes":"V103: =∑(PLATING-SCHEME.行电镀材料费)"},
        {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.defect_rate","notes":"DB 存小数 (0.005 = 0.5%)"},
        {"name":"电镀成本","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"=(电镀加工费 + 电镀材料费) × (1 + 不良率)"},
        {"name":"电镀成本(USD)","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":false,"notes":"V108 演示: = 电镀成本 × global_variable(EXCHANGE_RATE, CNY→USD)"}
    ]$JSON$::jsonb,
    formulas = $JSON$[
        {"name":"电镀材料费","result_type":"AMOUNT","expression":[
            {"type":"component_subtotal","label":"电镀方案·行电镀材料费","value":"行电镀材料费","tab_name":"电镀方案","component_code":"COMP-V4-PLATING-SCHEME"}
        ]},
        {"name":"电镀成本","result_type":"AMOUNT","expression":[
            {"type":"bracket_open","label":"(","value":"("},
            {"type":"field","label":"电镀加工费","value":"电镀加工费"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"field","label":"电镀材料费","value":"电镀材料费"},
            {"type":"bracket_close","label":")","value":")"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"bracket_open","label":"(","value":"("},
            {"type":"number","label":"1","value":"1"},
            {"type":"operator","label":"+","value":"+"},
            {"type":"field","label":"不良率(%)","value":"不良率(%)"},
            {"type":"bracket_close","label":")","value":")"}
        ]},
        {"name":"电镀成本(USD)","result_type":"AMOUNT","expression":[
            {"type":"field","label":"电镀成本","value":"电镀成本"},
            {"type":"operator","label":"×","value":"*"},
            {"type":"global_variable","label":"核价汇率[CNY:USD]","code":"EXCHANGE_RATE","key_values":{"from_currency":"CNY","to_currency":"USD"},"path":"v_costing_exchange_rate[from_currency='CNY' AND to_currency='USD'].costing_rate"}
        ]}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE code = 'COMP-V4-PLATING-COST';

-- ═════════════════════════════════════════════════════════════════════
-- 同步重建模板 components_snapshot
-- ═════════════════════════════════════════════════════════════════════
DO $$
DECLARE v_template_id UUID;
BEGIN
    SELECT id INTO v_template_id FROM template
    WHERE name = '核价-完整公式版-组件版' AND template_kind = 'COSTING';
    IF v_template_id IS NULL THEN RETURN; END IF;
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
            WHERE tc.template_id = v_template_id
        ),
        updated_at = now()
    WHERE id = v_template_id;
    RAISE NOTICE 'V108: 已重建模板 components_snapshot';
END $$;
