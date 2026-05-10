-- V99: 给 V98 创建的 14 个 NORMAL 核价组件配置 BASIC_DATA path
--
-- 来源: V98 组件字段当前是 INPUT_NUMBER/INPUT_TEXT (用户手填), 现改 BASIC_DATA 路径
--       让核价单 / 报价单的产品卡片视图按当前 hf_part_no 自动带核价基础数据。
--
-- BNF 路径规则:
--   * 表带 hf_part_no 列 (costing_part_*, mat_fee, plating_fee) → ImplicitJoinRewriter 自动按当前
--     报价单 lineItem.productPartNo 注入 hf_part_no=X 过滤
--   * 表不带 hf_part_no (plating_plan, v_costing_exchange_rate) → 需要显式谓词
--   * 多 cost_type/fee_type 复用同一张表 → 用 [field='X'] 谓词区分
--
-- 字段类型变化: INPUT_NUMBER/INPUT_TEXT → BASIC_DATA (with basic_data_path)
-- FORMULA / FIXED_VALUE 字段保持不变 (前端公式计算 / 固定值)

-- ── 1. COMP-V4-RAW-BOM 来料BOM (5 个 BASIC_DATA + 1 INPUT 单价 + 1 FORMULA 行小计) ──
UPDATE component SET fields = $JSON$[
    {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.input_material_no","notes":"v4 来料BOM 列 C, 自动按 hf_part_no 注入"},
    {"name":"组成用量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.input_qty","notes":"v4 列 I; 边角料为负数"},
    {"name":"底数","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.output_qty","notes":"v4 列 K"},
    {"name":"来料损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.loss_rate","notes":"v4 列 M"},
    {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_material_bom.output_loss_rate","notes":"v4 列 O"},
    {"name":"单价(CNY/KG)","field_type":"INPUT_NUMBER","content":"","is_amount":true,"is_subtotal":false,"notes":"用户填 (元素含量×元素核价 OR 材料核价); 单价跨表拼接, 留 INPUT 由用户决定"},
    {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 组成用量÷底数×(1+不良率%/100)×单价"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-RAW-BOM';

-- ── 2. COMP-V4-ELEMENT-BOM 元素BOM (input_material_no 不通过 hf_part_no 过滤, 只能用 mat_bom 元素子集近似) ──
UPDATE component SET fields = $JSON$[
    {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].input_material_no","notes":"按 hf_part_no 注入, 列出本料号涉及的元素 BOM 来料料号"},
    {"name":"元素代码","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].element_name"},
    {"name":"组成含量(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].composition_pct"},
    {"name":"损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_bom[bom_type='ELEMENT'].loss_rate"},
    {"name":"元素单价(CNY/KG)","field_type":"INPUT_NUMBER","content":"","is_amount":true,"is_subtotal":false,"notes":"从 v_costing_element_price 手填或后续接 BNF 跨表"},
    {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-ELEMENT-BOM';

-- ── 3. COMP-V4-PROCESS-COST 工序成本(合并 4 类) — 4 个 cost_type 谓词 BASIC_DATA ──
UPDATE component SET fields = $JSON$[
    {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='LABOR'].process_no","notes":"用 LABOR 类的 process_no 作为工序锚点"},
    {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='LABOR'].process_name"},
    {"name":"人工标准单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='LABOR'].unit_price","notes":"v4 人工成本 sheet"},
    {"name":"折旧单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='DEPRECIATION'].unit_price","notes":"v4 设备折旧 sheet"},
    {"name":"生产能耗单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='ENERGY_DEDICATED'].unit_price","notes":"v4 生产设备能耗 sheet"},
    {"name":"辅助能耗单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='ENERGY_SHARED'].unit_price","notes":"v4 辅助设备能耗 sheet"},
    {"name":"工序加工费","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 4 项单价之和"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-PROCESS-COST';

-- ── 4. COMP-V4-TOOLING 模具工装成本 ──
UPDATE component SET fields = $JSON$[
    {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_tooling_cost.process_no"},
    {"name":"工艺次数","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_tooling_cost.seq_no"},
    {"name":"模具编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_tooling_cost.tooling_no"},
    {"name":"单个模具成本","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"costing_part_tooling_cost.tooling_unit_cost"},
    {"name":"寿命(次)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_tooling_cost.process_count"},
    {"name":"单循环产量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_tooling_cost.cycle_count"},
    {"name":"模具单价","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 单个成本÷寿命÷单循环产量"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-TOOLING';

-- ── 5. COMP-V4-CONSUMABLE 耗材包装 (cost_type=CONSUMABLE) ──
UPDATE component SET fields = $JSON$[
    {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='CONSUMABLE'].process_no"},
    {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='CONSUMABLE'].process_name"},
    {"name":"耗材成本单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"costing_part_process_cost[cost_type='CONSUMABLE'].unit_price"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-CONSUMABLE';

-- ── 6. COMP-V4-INCOMING-FEE 来料加工费 (cost_type=MATERIAL_PROC) ──
UPDATE component SET fields = $JSON$[
    {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='MATERIAL_PROC'].process_no","notes":"借用 process_no 作为来料加工费序号"},
    {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='MATERIAL_PROC'].process_name","notes":"借用 process_name 作为料号显示"},
    {"name":"加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"costing_part_process_cost[cost_type='MATERIAL_PROC'].unit_price"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-INCOMING-FEE';

-- ── 7. COMP-V4-INCOMING-OTHER 来料其他费用 (mat_fee[fee_type='INCOMING_OTHER']) ──
UPDATE component SET fields = $JSON$[
    {"name":"一级项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_OTHER'].seq_no"},
    {"name":"来料料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_OTHER'].dim_input_material_no"},
    {"name":"二级项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_OTHER'].dim_sub_seq_no"},
    {"name":"要素名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='INCOMING_OTHER'].dim_element_name"},
    {"name":"比例(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"mat_fee[fee_type='INCOMING_OTHER'].fee_ratio"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-INCOMING-OTHER';

-- ── 8. COMP-V4-FINISHED-FEE 成品加工费 (cost_type=SEMI_FINISHED_PROC) ──
UPDATE component SET fields = $JSON$[
    {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='SEMI_FINISHED_PROC'].process_no"},
    {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='SEMI_FINISHED_PROC'].process_name"},
    {"name":"加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='SEMI_FINISHED_PROC'].unit_price"},
    {"name":"不良率(%)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false,"notes":"来源待补 (mat_fee 或独立表)"},
    {"name":"行小计","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"= 加工费×(1+不良率%/100)"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-FINISHED-FEE';

-- ── 9. COMP-V4-FINISHED-OTHER 成品其他费用 (mat_fee[fee_type='FINISHED_OTHER']) ──
UPDATE component SET fields = $JSON$[
    {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='FINISHED_OTHER'].seq_no"},
    {"name":"要素名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"mat_fee[fee_type='FINISHED_OTHER'].dim_element_name","notes":"管理费/财务费/利润/税费"},
    {"name":"比例(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"mat_fee[fee_type='FINISHED_OTHER'].fee_ratio","notes":"V87 已 seed 4 行: 0.006/0.005/0.05/0.13"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-FINISHED-OTHER';

-- ── 10. COMP-V4-PLATING-SCHEME 电镀方案 (plating_plan 全局表, 不绑 hf_part_no, 通过 plating_fee 关联) ──
UPDATE component SET fields = $JSON$[
    {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.plating_plan_code","notes":"先从 plating_fee 拿当前料号绑定的方案编号"},
    {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.plan_version"},
    {"name":"项次","field_type":"INPUT_NUMBER","content":"1","is_amount":false,"is_subtotal":false,"notes":"plating_plan 是全局, 跨表查询暂不支持, 留 INPUT"},
    {"name":"电镀元素","field_type":"INPUT_TEXT","content":"","is_amount":false,"is_subtotal":false},
    {"name":"电镀面积(cm²)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false},
    {"name":"镀层厚度(μm)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":false},
    {"name":"密度(g/cm³)","field_type":"INPUT_NUMBER","content":"0","is_amount":false,"is_subtotal":true}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-PLATING-SCHEME';

-- ── 11. COMP-V4-PLATING-COST 电镀成本 (plating_fee 自动注入 hf_part_no) ──
UPDATE component SET fields = $JSON$[
    {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.plating_plan_code"},
    {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.plan_version"},
    {"name":"电镀加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"plating_fee.plating_process_fee"},
    {"name":"电镀材料费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"plating_fee.plating_material_fee"},
    {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"plating_fee.defect_rate"},
    {"name":"电镀成本","field_type":"FORMULA","content":"","is_amount":true,"is_subtotal":true,"notes":"v4 行 90: =(电镀加工费+电镀材料费)×(1+不良率%/100)"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-PLATING-COST';

-- ── 12. COMP-V4-OUTSOURCE 其他外加工 (cost_type=POST_PROC) ──
UPDATE component SET fields = $JSON$[
    {"name":"工序号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='POST_PROC'].process_no","notes":"如委外焊接"},
    {"name":"工序名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"costing_part_process_cost[cost_type='POST_PROC'].process_name"},
    {"name":"外加工费用","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":true,"basic_data_path":"costing_part_process_cost[cost_type='POST_PROC'].unit_price"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-OUTSOURCE';

-- ── 13. COMP-V4-WEIGHT 单重 ──
UPDATE component SET fields = $JSON$[
    {"name":"单重(g/pcs)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"costing_part_weight.weight_g_per_pcs","notes":"按 hf_part_no 自动注入"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-WEIGHT';

-- ── 14. COMP-V4-EXCHANGE-RATE 汇率 (全局视图, 显式谓词) ──
UPDATE component SET fields = $JSON$[
    {"name":"基础货币","field_type":"FIXED_VALUE","content":"CNY","is_amount":false,"is_subtotal":false},
    {"name":"核价货币","field_type":"FIXED_VALUE","content":"USD","is_amount":false,"is_subtotal":false},
    {"name":"核价汇率","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":true,"basic_data_path":"v_costing_exchange_rate[from_currency='CNY',to_currency='USD'].costing_rate","notes":"V87 已 seed: 0.138"}
]$JSON$::jsonb,
updated_at = now()
WHERE code = 'COMP-V4-EXCHANGE-RATE';

-- ============================================================
-- Step 2: 把模板「核价-完整公式版-组件版」从 DRAFT 改为 PUBLISHED
-- 使产品卡片视图能渲染
-- ============================================================
DO $$
DECLARE v_template_id UUID;
BEGIN
    SELECT id INTO v_template_id FROM template
    WHERE name = '核价-完整公式版-组件版' AND template_kind = 'COSTING';
    IF v_template_id IS NULL THEN
        RAISE NOTICE 'V99: 模板不存在, 跳过发布';
        RETURN;
    END IF;
    -- 已是 PUBLISHED 跳过
    IF EXISTS (SELECT 1 FROM template WHERE id = v_template_id AND status = 'PUBLISHED') THEN
        RAISE NOTICE 'V99: 模板已是 PUBLISHED 状态';
        RETURN;
    END IF;
    UPDATE template
    SET status = 'PUBLISHED',
        published_at = now(),
        components_snapshot = (
            -- 按 publish 时通常的做法: 把当前 template_component 的关联快照成 JSONB
            SELECT jsonb_agg(jsonb_build_object(
                'id', tc.id,
                'componentId', tc.component_id,
                'tabName', tc.tab_name,
                'sortOrder', tc.sort_order,
                'componentCode', c.code,
                'componentName', c.name,
                'componentType', c.component_type,
                'fields', c.fields,
                'formulas', c.formulas
            ) ORDER BY tc.sort_order)
            FROM template_component tc
            JOIN component c ON c.id = tc.component_id
            WHERE tc.template_id = v_template_id
        ),
        updated_at = now()
    WHERE id = v_template_id;
    RAISE NOTICE 'V99: 模板「核价-完整公式版-组件版」已发布 PUBLISHED, 含 components_snapshot';
END $$;
