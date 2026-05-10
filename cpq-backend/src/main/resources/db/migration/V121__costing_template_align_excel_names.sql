-- V121: 核价模板 tab 名严格对齐核价 Excel sheet 名
--
-- 用户要求: 核价模板配置内容与核价 Excel 一致
--
-- 核价 Excel (data/template/核价系统功能基础数据功能结构所需字段（4.0版）.xlsx) 22 sheet:
--   1. 元素核价价格表       ← 全局变量 ELEM_PRICE 已暴露, 不作 tab
--   2. 材料核价价格表       ← 全局变量 MAT_PRICE 已暴露, 不作 tab
--   3. 汇率管理表           ← 全局变量 EXCHANGE_RATE, 但 Excel 业务侧仍想看, 保留 tab → 「汇率管理表」
--   4. 核价版本             ← 元数据 sheet, 不作 tab
--   5. 宏丰-客户料号对应关系 ← (本次不补, 用户未明说)
--   6. 来料BOM              → tab 「来料BOM」 ✓
--   7. 来料与元素BOM         → tab 当前「元素BOM」, 改名「来料与元素BOM」
--   8-11. 人工成本/设备折旧/生产设备能耗/辅助设备能耗  → 当前合并到「工序成本」单 tab
--         拆分会破坏 COMP-V4-TOTAL-CNY 引用 (TOTAL-CNY 引 COMP-V4-PROCESS-COST 的 subtotal)
--         先**保留合成结构**, 改名「工序成本(8类)」让用户知道这是聚合
--   12. 模具工装成本         → 当前「模具工装」, 改名「模具工装成本」
--   13. 耗材与包装材料        → 当前「耗材包装」, 改名「耗材与包装材料」
--   14. 来料加工费           ✓
--   15. 来料其他费用          ✓
--   16. 成品加工费&组装费    → 当前「成品加工费」, 改名「成品加工费&组装费」
--   17. 成品其他费用          ✓
--   18. 电镀方案              ✓
--   19. 电镀成本              ✓
--   20. 其他外加工成本        → 当前「其他外加工」, 改名「其他外加工成本」
--   21. 单重                  ✓
--   22. 汇总                 → 当前「总公式(CNY)」, 改名「汇总」
--
-- 待用户拍板的项 (本次不做):
--   ① 是否拆「工序成本」为 4 个独立 tab + 调整 TOTAL-CNY 公式从引用 PROCESS-COST 改为引用 4 个细分组件 subtotal
--   ② 是否补「宏丰-客户料号对应关系」tab (新建组件 COMP-V4-CUSTOMER-MAPPING)

-- ════════════════════════════════════════════════════════════════════════════
-- A. 改组件 name + 同步 template_component.tab_name (7 个改名)
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE v_pair RECORD;
BEGIN
    FOR v_pair IN
        SELECT * FROM (VALUES
            ('COMP-V4-EXCHANGE-RATE',  '汇率管理表'),
            ('COMP-V4-ELEMENT-BOM',    '来料与元素BOM'),
            ('COMP-V4-PROCESS-COST',   '工序成本(8类)'),   -- 标注是聚合 tab
            ('COMP-V4-TOOLING',        '模具工装成本'),
            ('COMP-V4-CONSUMABLE',     '耗材与包装材料'),
            ('COMP-V4-FINISHED-FEE',   '成品加工费&组装费'),
            ('COMP-V4-OUTSOURCE',      '其他外加工成本'),
            ('COMP-V4-TOTAL-CNY',      '汇总')
        ) AS m(code, new_name)
    LOOP
        UPDATE component SET name = v_pair.new_name, updated_at = now() WHERE code = v_pair.code;
        UPDATE template_component SET tab_name = v_pair.new_name
        WHERE component_id = (SELECT id FROM component WHERE code = v_pair.code);
        RAISE NOTICE 'V121-A: 改名 % → %', v_pair.code, v_pair.new_name;
    END LOOP;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- B. 重建 components_snapshot (所有持有 COMP-V4-* 组件的 PUBLISHED COSTING 模板)
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE v_tpl RECORD;
BEGIN
    FOR v_tpl IN
        SELECT DISTINCT t.id
        FROM template t
        JOIN template_component tc ON tc.template_id = t.id
        JOIN component c ON c.id = tc.component_id
        WHERE c.code LIKE 'COMP-V4-%' AND t.template_kind = 'COSTING'
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
        RAISE NOTICE 'V121-B: 重建 % snapshot', v_tpl.id;
    END LOOP;
END $$;
