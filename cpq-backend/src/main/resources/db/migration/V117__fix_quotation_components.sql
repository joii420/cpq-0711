-- V117: 修复 V116 报价模板组件的两类配置问题 (报价单 QT-20260507-1351 暴露)
--
-- ═════════════════════════════════════════════════════════════════════
-- 问题 1: COMP-Q-CUSTOMER-MAPPING 字段名错配
-- ═════════════════════════════════════════════════════════════════════
--   实际表 mat_customer_part_mapping schema:
--     customer_part_name      ← V116 误写成 customer_part_no
--     customer_product_no     ← V116 字段"客户产品编码"误指 customer_part_name
--     customer_drawing_no     ✓
--     hf_part_no              ✓
--     payment_method          ← V116 配成 INPUT_TEXT 占位, 实际表已存
--     base_currency           ← 同上
--     quote_currency          ← 同上
--   后果: 报价单产品卡片视图调用 customer_part_no 路径返 PG 报错
--          "字段 customer_part_no 不存在; 也许您想要 customer_part_name"
--
-- 修复: 把 8 个字段全部改为正确的 BASIC_DATA + 实际表列名.
--      汇率字段固化为 CNY→USD 静态绑定 (基础/报价币种动态切换需扩展视图列名别名, 后续 V_XX 补).
--
-- ═════════════════════════════════════════════════════════════════════
-- 问题 2: 「{fee_ratio:null} (共 7 项)」 (信息记录 - 此处不修)
-- ═════════════════════════════════════════════════════════════════════
--   COMP-Q-FINISHED-OTHER (driver=mat_fee[fee_type='FINISHED_OTHER']) 是多行 driver 组件,
--   产品卡片视图下应展开 N 行渲染 (如 partNo=3120012575 共 7 行 FINISHED_OTHER 数据).
--   如果 UI 没展开而是显示成单 cell "{fee_ratio:null}（共7项）", 是前端 useDriverExpansions
--   或 ProductCard 的 driver 展开链路问题, 不是组件配置问题.
--   组件配置无问题 (与核价 COMP-V4-FINISHED-OTHER 完全一致).

-- ═════════════════════════════════════════════════════════════════════
-- 修复 1: 重写 COMP-Q-CUSTOMER-MAPPING 字段配置
-- ═════════════════════════════════════════════════════════════════════
UPDATE component
SET fields = $JSON$[
        {"name":"客户料号编码","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.customer_part_name","notes":"V117: 表字段名是 customer_part_name 但语义为客户料号编码"},
        {"name":"客户产品编码","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.customer_product_no"},
        {"name":"客户图号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.customer_drawing_no"},
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.hf_part_no"},
        {"name":"贸易方式","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.payment_method","notes":"V117: 全包/半包/料号"},
        {"name":"基础币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.base_currency"},
        {"name":"报价币种","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_customer_part_mapping.quote_currency"},
        {"name":"汇率","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_costing_exchange_rate[from_currency='CNY' AND to_currency='USD'].costing_rate","global_variable_code":"EXCHANGE_RATE","notes":"V117: 静态绑定 CNY→USD; 动态切换 from=base_currency to=quote_currency 需要先给视图加 base_currency/quote_currency 别名列"}
    ]$JSON$::jsonb,
    column_count = 8,
    updated_at = now()
WHERE code = 'COMP-Q-CUSTOMER-MAPPING';

-- ═════════════════════════════════════════════════════════════════════
-- 重建持有该组件的所有 PUBLISHED 模板的 components_snapshot
-- ═════════════════════════════════════════════════════════════════════
DO $$
DECLARE v_tpl RECORD;
BEGIN
    FOR v_tpl IN
        SELECT DISTINCT t.id
        FROM template t
        JOIN template_component tc ON tc.template_id = t.id
        JOIN component c ON c.id = tc.component_id
        WHERE c.code = 'COMP-Q-CUSTOMER-MAPPING'
          AND t.status = 'PUBLISHED'
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
        RAISE NOTICE 'V117: 已重建模板 % components_snapshot', v_tpl.id;
    END LOOP;
END $$;
