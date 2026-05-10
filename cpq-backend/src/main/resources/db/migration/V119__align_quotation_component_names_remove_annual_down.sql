-- V119: 报价单组件名称对齐 Excel sheet 名称 + 移除「年降」相关 3 个 tab
--
-- 起因:
--   用户拍板: 报价单产品卡片模板的组件名要跟 Excel 文件 sheet 名一对一相同; 年降相关 tab 全部移除.
--
-- Excel 实际 sheet 名 (data/template/报价系统功能基础数据功能结构所需字段（1.0版）.xlsx, 17 sheet):
--   1. 元素单价  2. 客户料号与宏丰料号的关系  3. 来料BOM  4. 元素BOM  5. 元素回收折扣
--   6. 来料固定加工费  7. 来料其他费用  8. 来料年降  9. 来料回收折扣
--   10. 成品固定加工费  11. 成品其他费用  12. 组成件BOM及单价
--   13. 组装加工费  14. 组装加工费年降  15. 电镀方案  16. 电镀费用  17. 单重
--
-- 改动:
--   A. 改组件 name 对齐 sheet 名 (7 个):
--      COMP-Q-CUSTOMER-MAPPING:  客户料号关系       → 客户料号与宏丰料号的关系
--      COMP-Q-MATERIAL-FEE:      材料固定加工费     → 来料固定加工费
--      COMP-Q-MATERIAL-OTHER:    材料其他费用       → 来料其他费用
--      COMP-Q-MATERIAL-RECYCLE:  材料回收折扣       → 来料回收折扣
--      COMP-Q-COMPONENT-BOM:     组成件BOM及零部件  → 组成件BOM及单价
--      COMP-Q-PLATING-SCHEME:    产品电镀方案       → 电镀方案
--      COMP-Q-PLATING-COST:      产品电镀成本       → 电镀费用
--
--   B. 移除「年降」相关 3 个 tab (从持有此组件的模板 template_component 关联表里删):
--      COMP-Q-MATERIAL-DECREASE   材料年降
--      COMP-Q-ASSEMBLY-DECREASE   组装加工费年降
--      COMP-Q-ANNUAL-DECREASE     年降系数
--   组件实体 (component 表) **保留**, 仅删除模板关联; 其他模板若引用不受影响.
--
-- C. 范围: 所有持有这些组件的 PUBLISHED + DRAFT 模板都同步; 重建 components_snapshot.

-- ════════════════════════════════════════════════════════════════════════════
-- A. 改组件 name (component.name) — 同时更新 template_component.tab_name
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_pair RECORD;
BEGIN
    FOR v_pair IN
        SELECT * FROM (VALUES
            ('COMP-Q-CUSTOMER-MAPPING', '客户料号与宏丰料号的关系'),
            ('COMP-Q-MATERIAL-FEE',     '来料固定加工费'),
            ('COMP-Q-MATERIAL-OTHER',   '来料其他费用'),
            ('COMP-Q-MATERIAL-RECYCLE', '来料回收折扣'),
            ('COMP-Q-COMPONENT-BOM',    '组成件BOM及单价'),
            ('COMP-Q-PLATING-SCHEME',   '电镀方案'),
            ('COMP-Q-PLATING-COST',     '电镀费用')
        ) AS m(code, new_name)
    LOOP
        -- 改 component name
        UPDATE component SET name = v_pair.new_name, updated_at = now() WHERE code = v_pair.code;
        -- 同步 template_component.tab_name (所有持有此组件的模板 tab 显示名一并更新)
        UPDATE template_component SET tab_name = v_pair.new_name
        WHERE component_id = (SELECT id FROM component WHERE code = v_pair.code);
        RAISE NOTICE 'V119-A: 改名 % → %', v_pair.code, v_pair.new_name;
    END LOOP;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- B. 移除年降 tab — 从 template_component 删关联 (组件本身保留)
-- ════════════════════════════════════════════════════════════════════════════
DELETE FROM template_component
WHERE component_id IN (
    SELECT id FROM component
    WHERE code IN ('COMP-Q-MATERIAL-DECREASE','COMP-Q-ASSEMBLY-DECREASE','COMP-Q-ANNUAL-DECREASE')
);

-- ════════════════════════════════════════════════════════════════════════════
-- C. 重建 components_snapshot — 所有持有 COMP-Q-* 组件的模板都同步刷新
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE v_tpl RECORD;
BEGIN
    FOR v_tpl IN
        SELECT DISTINCT t.id
        FROM template t
        JOIN template_component tc ON tc.template_id = t.id
        JOIN component c ON c.id = tc.component_id
        WHERE c.code LIKE 'COMP-Q-%'
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
        RAISE NOTICE 'V119-C: 重建 template % snapshot', v_tpl.id;
    END LOOP;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- 提示: V118 创建的 basic_data_config 别名「材料固定加工费」/「材料其他费用」/「材料回收折扣」
--      实际 Excel sheet 名是「来料*」, 这些别名在用户当前 Excel 文件里**不会被使用** (V5 import
--      看不到这些 sheet 名的行). 但保留无害 — 未来若有 Excel 用「材料*」名也能识别.
--      用户的「来料固定加工费」/「来料其他费用」/「来料回收折扣」(QUOTATION) 注册同样有效:
--      - 「来料固定加工费」kind=BOTH 已注册 (覆盖 QUOTATION)
--      - 「来料其他费用」kind=QUOTATION 已注册 (V118 之前)
--      - 「来料回收折扣」⚠️ 当前未注册! V5 import 不会写入. 需要补一行注册.
--
--      同理「组成件BOM及单价」kind=BOTH 已注册 ✓; 「电镀方案」kind=BOTH ✓; 「电镀费用」kind=BOTH ✓
-- ════════════════════════════════════════════════════════════════════════════

-- D. 补「来料回收折扣」basic_data_config (kind=QUOTATION; 物理表 mat_fee + fee_type=MATERIAL_RECYCLE)
DO $$
DECLARE v_new_id UUID := gen_random_uuid();
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM basic_data_config
        WHERE sheet_name='来料回收折扣' AND template_kind IN ('BOTH','QUOTATION') AND status='ACTIVE'
    ) THEN
        INSERT INTO basic_data_config (id, sheet_name, target_table, target_discriminator, template_kind,
                                       header_row_index, data_start_row_index, sort_order, status, description,
                                       created_at, updated_at)
        VALUES (v_new_id, '来料回收折扣', 'mat_fee',
                '{"fee_type":"MATERIAL_RECYCLE"}'::jsonb, 'QUOTATION',
                1, 2, 100, 'ACTIVE',
                'V119: 报价侧「来料回收折扣」 — Excel 实际 sheet 名 (V118 误注册成「材料回收折扣」, 此处补正确名);'
                || ' 物理表 mat_fee + fee_type=MATERIAL_RECYCLE; fee_ratio 字段存折扣百分比',
                now(), now());

        INSERT INTO basic_data_attribute (id, config_id, column_letter, column_title, variable_code, variable_label,
                                          data_type, status, sort_order, importance_level, affects_calculation, is_required,
                                          created_at, updated_at) VALUES
            (gen_random_uuid(), v_new_id, 'A', '宏丰料号',     'hf_part_no',              '宏丰料号',   'IDENTIFIER', 'ACTIVE', 1, 'IMPORTANT', false, true,  now(), now()),
            (gen_random_uuid(), v_new_id, 'B', '序号',         'seq_no',                  '序号',       'VALUE',      'ACTIVE', 2, 'NORMAL',    false, false, now(), now()),
            (gen_random_uuid(), v_new_id, 'C', '投料号',       'dim_input_material_no',   '投料号',     'VALUE',      'ACTIVE', 3, 'NORMAL',    false, false, now(), now()),
            (gen_random_uuid(), v_new_id, 'D', '投料号名称',   'dim_input_material_name', '投料号名称', 'VALUE',      'ACTIVE', 4, 'NORMAL',    false, false, now(), now()),
            (gen_random_uuid(), v_new_id, 'E', '回收折扣(%)',  'fee_ratio',               '回收折扣',   'VALUE',      'ACTIVE', 5, 'IMPORTANT', true,  false, now(), now());

        RAISE NOTICE 'V119-D: 注册「来料回收折扣」 → %', v_new_id;
    END IF;
END $$;
