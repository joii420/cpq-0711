-- V216: 派生「报价标准模板-Excel基础结构 v1.0」v1.7 DRAFT (从 v1.6 PUBLISHED 派生)
--
-- 落地方案文档:docs/方案-报价标准模板v1.7-重配方案.md
--
-- 改动维度 (方案 §0.2):
--   1) 11 字段升级为 INPUT_NUMBER + default_source.BNF_PATH
--      汇率 / 损耗率 / 不良率 / 回收折扣 / 比例(%) / 材料结算涨幅 / 组成含量(%)
--      / 拒收率/不良率(%) / 组成数量 / 电镀面积(cm2) / 镀层厚度(um)
--   2) 5 字段标记 is_subtotal=true (被根级 subtotal_formula 聚合)
--      来料.值 / 成品.值 / 组装加工费 / 电镀加工费 / 组件其他费用.值
--   3) 模板根级 subtotal_formula 写「总成本」(5 个 component_subtotal 聚合)
--   4) 模板 excel_view_config 写 23 列 (11 隐藏中间列 + 12 业务列)
--
-- 架构选择 (避开红线):
--   - 字段升级走 template_component.fields_override (publish() L225 优先用 override)
--     ✅ 不动 8 个共享 component.fields → 0 影响其他 10 个引用模板
--     ✅ v1.7 DRAFT 自带升级后字段
--   - components_snapshot 同步预填 (与 publish() rebuild 逻辑等价)
--     让 v1.7 即便未 publish 也能在 UI 上完整渲染评审
--
-- 同 series DRAFT 处理:
--   旧 DRAFT bc0a5dc6 (2026-05-13 无版本号遗留 DRAFT) 置 ARCHIVED, 否则 publish 时 series 多 DRAFT 报错
--

-- ============================================================
-- 0. 准备:复用源 v1.6 + 同 series + 客户 + 类别
-- ============================================================

DO $V216$
DECLARE
  v_source_id      UUID := 'f6dae2b1-8b92-4de3-a93f-e78139af3b35';  -- v1.6
  v_new_id         UUID := gen_random_uuid();                        -- v1.7 NEW
  v_series_id      UUID;
  v_customer_id    UUID;
  v_category_id    UUID;
  v_category       VARCHAR;
  v_kind           VARCHAR;
  v_description    TEXT;
  v_usage_note     TEXT;
  v_product_attrs  JSONB;
  v_subtotal       JSONB;
  v_excel_view     JSONB;
  v_snapshot       JSONB;
  v_legacy_draft   UUID := 'bc0a5dc6-8da6-4a90-8c72-333a389fb890';   -- 遗留 DRAFT (同 series)
  -- 7 个 NORMAL component 的 fields_override(升级后 fields)
  v_fo_part_info   JSONB;
  v_fo_incoming    JSONB;
  v_fo_element     JSONB;
  v_fo_finished    JSONB;
  v_fo_component   JSONB;
  v_fo_assembly    JSONB;
  v_fo_plating     JSONB;
  v_fo_comp_fee    JSONB;
BEGIN
  -- 拉源模板根属性
  SELECT template_series_id, customer_id, category_id, category, template_kind,
         description, usage_note, product_attributes
    INTO v_series_id, v_customer_id, v_category_id, v_category, v_kind,
         v_description, v_usage_note, v_product_attrs
    FROM template
   WHERE id = v_source_id;

  IF v_series_id IS NULL THEN
    RAISE EXCEPTION 'V216 abort: source template % not found', v_source_id;
  END IF;

  -- 归档同 series 的遗留 DRAFT (避免一会 publish 时报 "series 多 DRAFT")
  UPDATE template
     SET status = 'ARCHIVED',
         description = COALESCE(description,'') || E'\n[V216] ARCHIVED by V216 (legacy draft pre-v1.7)',
         updated_at = now()
   WHERE id = v_legacy_draft AND status = 'DRAFT';

  -- ============================================================
  -- 1. 7 个 NORMAL component 的 fields_override JSON
  --    源自 v1.6 components_snapshot 的对应 tab.fields, 按方案 §1 升级
  --    (组成件 BOM / 小计1 没有 INPUT_NUMBER 或 is_subtotal 改动? BOM 有 组成数量 升级 INPUT_NUMBER)
  -- ============================================================

  -- Tab 1 「料件」(汇率→INPUT_NUMBER)
  v_fo_part_info := '[
    {"name":"宏丰料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_part_info_merged.hf_part_no"},
    {"name":"客户料号名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_part_info_merged.customer_part_name"},
    {"name":"客户产品编号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_part_info_merged.customer_product_no"},
    {"name":"客户图号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_part_info_merged.customer_drawing_no"},
    {"name":"付款方式","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_part_info_merged.payment_method"},
    {"name":"基础货币","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_part_info_merged.base_currency"},
    {"name":"报价货币","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_part_info_merged.quote_currency"},
    {"name":"汇率","content":"1.0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_part_info_merged.exchange_rate"}},
    {"name":"单重(g/pcs)","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_part_info_merged.unit_weight"},
    {"name":"重量单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_part_info_merged.weight_unit"}
  ]'::jsonb;

  -- Tab 2 「来料」(损耗率/不良率/回收折扣/比例/材料结算涨幅 → INPUT_NUMBER; 值 → is_subtotal)
  v_fo_incoming := '[
    {"name":"项次","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.seq_no"},
    {"name":"项次2","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.sub_seq_no"},
    {"name":"宏丰料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.hf_part_no"},
    {"name":"来源","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.source_type"},
    {"name":"投入料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.input_material_no"},
    {"name":"投入料号名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.input_material_name"},
    {"name":"产出料号类型","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.output_material_type"},
    {"name":"要素名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.element_name"},
    {"name":"材料毛重","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.gross_qty"},
    {"name":"材料净重","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.net_qty"},
    {"name":"重量单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.weight_unit"},
    {"name":"损耗率(%)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_incoming_merged.loss_rate"}},
    {"name":"不良率(%)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_incoming_merged.defect_rate"}},
    {"name":"回收折扣(%)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_incoming_merged.recycle_pct"}},
    {"name":"比例(%)","content":"100","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_incoming_merged.fee_ratio"}},
    {"name":"值","content":"","is_amount":true,"field_type":"BASIC_DATA","is_subtotal":true,"basic_data_path":"v_q_incoming_merged.fee_value"},
    {"name":"货币","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.currency"},
    {"name":"计价单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.price_unit"},
    {"name":"是否随材料价格波动","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.price_floating"},
    {"name":"材料结算涨幅比例(%)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_incoming_merged.settlement_rise_ratio"}},
    {"name":"材料固定涨幅值","content":"","is_amount":true,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.fixed_rise_value"},
    {"name":"涨幅货币","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.rise_currency"},
    {"name":"涨幅单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_incoming_merged.rise_unit"}
  ]'::jsonb;

  -- Tab 3 「元素」(组成含量/损耗率/回收折扣 → INPUT_NUMBER)
  v_fo_element := '[
    {"name":"项次","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_element_merged.seq_no"},
    {"name":"宏丰料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_element_merged.hf_part_no"},
    {"name":"来源","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_element_merged.source_type"},
    {"name":"投入料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_element_merged.input_material_no"},
    {"name":"投入料号名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_element_merged.input_material_name"},
    {"name":"元素","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_element_merged.element_name"},
    {"name":"组成含量(%)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_element_merged.composition_pct"}},
    {"name":"毛用量","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_element_merged.gross_qty"},
    {"name":"毛用量单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_element_merged.gross_unit"},
    {"name":"净用量","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_element_merged.net_qty"},
    {"name":"净用量单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_element_merged.net_unit"},
    {"name":"损耗率(%)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_element_merged.loss_rate"}},
    {"name":"回收折扣(%)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_element_merged.recycle_pct"}}
  ]'::jsonb;

  -- Tab 4 「成品」(比例 → INPUT_NUMBER; 值 → is_subtotal)
  v_fo_finished := '[
    {"name":"项次","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_finished_merged.seq_no"},
    {"name":"宏丰料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_finished_merged.hf_part_no"},
    {"name":"来源","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_finished_merged.source_type"},
    {"name":"要素名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_finished_merged.element_name"},
    {"name":"比例(%)","content":"100","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_finished_merged.fee_ratio"}},
    {"name":"值","content":"","is_amount":true,"field_type":"BASIC_DATA","is_subtotal":true,"basic_data_path":"v_q_finished_merged.fee_value"},
    {"name":"货币","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_finished_merged.currency"},
    {"name":"计价单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_finished_merged.price_unit"}
  ]'::jsonb;

  -- Tab 5 「组成件BOM」(组成数量 → INPUT_NUMBER)
  v_fo_component := '[
    {"name":"项次","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_merged.seq_no"},
    {"name":"项次2","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_merged.sub_seq_no"},
    {"name":"宏丰料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_merged.hf_part_no"},
    {"name":"组成件料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_merged.component_part_no"},
    {"name":"组成件名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_merged.component_name"},
    {"name":"组成数量","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_component_merged.quantity"}},
    {"name":"组成单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_merged.quantity_unit"},
    {"name":"组装工序","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_merged.assembly_process"},
    {"name":"工序编号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_merged.process_code"}
  ]'::jsonb;

  -- Tab 6 「组装加工」(拒收率/不良率 → INPUT_NUMBER; 组装加工费 → is_subtotal)
  v_fo_assembly := '[
    {"name":"项次","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_assembly_merged.seq_no"},
    {"name":"宏丰料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_assembly_merged.hf_part_no"},
    {"name":"组装工序","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_assembly_merged.assembly_process"},
    {"name":"拒收率/不良率(%)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_assembly_merged.reject_rate"}},
    {"name":"组装加工费","content":"","is_amount":true,"field_type":"BASIC_DATA","is_subtotal":true,"basic_data_path":"v_q_assembly_merged.fee_value"},
    {"name":"货币","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_assembly_merged.currency"},
    {"name":"计价单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_assembly_merged.price_unit"}
  ]'::jsonb;

  -- Tab 7 「电镀」(电镀面积/镀层厚度/不良率 → INPUT_NUMBER; 电镀加工费 → is_subtotal)
  v_fo_plating := '[
    {"name":"项次","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_plating_merged.seq_no"},
    {"name":"宏丰料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_plating_merged.hf_part_no"},
    {"name":"来源","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_plating_merged.source_type"},
    {"name":"方案编号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_plating_merged.plan_code"},
    {"name":"版本","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_plating_merged.plan_version"},
    {"name":"电镀元素名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_plating_merged.plating_element"},
    {"name":"电镀要求","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_plating_merged.plating_requirement"},
    {"name":"电镀面积(cm2)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_plating_merged.plating_area"}},
    {"name":"镀层厚度(um)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_plating_merged.coating_thickness"}},
    {"name":"不良率(%)","content":"0","is_amount":false,"field_type":"INPUT_NUMBER","is_subtotal":false,"default_source":{"type":"BNF_PATH","bnf_path":"v_q_plating_merged.defect_rate"}},
    {"name":"电镀材料费","content":"","is_amount":true,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_plating_merged.plating_material_fee"},
    {"name":"电镀加工费","content":"","is_amount":true,"field_type":"BASIC_DATA","is_subtotal":true,"basic_data_path":"v_q_plating_merged.plating_process_fee"},
    {"name":"货币","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_plating_merged.currency"},
    {"name":"计价单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_plating_merged.price_unit"}
  ]'::jsonb;

  -- Tab 9 「组成件其他费用」(值 → is_subtotal)
  v_fo_comp_fee := '[
    {"name":"项次","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.seq_no"},
    {"name":"组件项次","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.sub_seq_no"},
    {"name":"宏丰料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.hf_part_no"},
    {"name":"组成件料号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.component_part_no"},
    {"name":"组成件名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.component_name"},
    {"name":"组装工序","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.assembly_process"},
    {"name":"要素名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.element_name"},
    {"name":"值","content":"","is_amount":true,"field_type":"BASIC_DATA","is_subtotal":true,"basic_data_path":"v_q_component_fee_merged.fee_value"},
    {"name":"货币","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.currency"},
    {"name":"计价单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"v_q_component_fee_merged.price_unit"}
  ]'::jsonb;

  -- ============================================================
  -- 2. 模板根级 subtotal_formula (总成本聚合)
  --    component_subtotal token 引用 COMP-QX-* code 与 tab_name 双键, 渲染时按其取该组件的 is_subtotal=true 列求和
  -- ============================================================
  v_subtotal := '[
    {
      "name": "总成本",
      "expression": [
        { "type": "component_subtotal", "component_code": "COMP-QX-INCOMING",      "tab_name": "来料",           "value": "来料·值",           "label": "来料·值" },
        { "type": "operator", "value": "+" },
        { "type": "component_subtotal", "component_code": "COMP-QX-FINISHED",      "tab_name": "成品",           "value": "成品·值",           "label": "成品·值" },
        { "type": "operator", "value": "+" },
        { "type": "component_subtotal", "component_code": "COMP-QX-ASSEMBLY",      "tab_name": "组装加工",       "value": "组装加工·组装加工费","label": "组装加工·组装加工费" },
        { "type": "operator", "value": "+" },
        { "type": "component_subtotal", "component_code": "COMP-QX-PLATING",       "tab_name": "电镀",           "value": "电镀·电镀加工费",    "label": "电镀·电镀加工费" },
        { "type": "operator", "value": "+" },
        { "type": "component_subtotal", "component_code": "COMP-QX-COMPONENT-FEE", "tab_name": "组成件其他费用", "value": "组成件其他费用·值",  "label": "组成件其他费用·值" }
      ],
      "result_type": "NUMBER"
    }
  ]'::jsonb;

  -- ============================================================
  -- 3. excel_view_config (11 隐藏中间列 + 11 业务列 + 1 H_SUBTOTAL = 12 业务列)
  --    模式仿 c965b250 (选配产品标准报价模板-组合产品 v1.26)
  -- ============================================================
  v_excel_view := '[
    { "col_key":"H_HF_NO",       "col_name":"宏丰料号(中间)",   "title":"宏丰料号(中间)",   "source_type":"VARIABLE", "variable_path":"{hf_part_no}",                                "visible":false, "hidden":true },
    { "col_key":"H_CUST_NAME",   "col_name":"客户料号(中间)",   "title":"客户料号(中间)",   "source_type":"VARIABLE", "variable_path":"v_q_part_info_merged.customer_part_name",     "visible":false, "hidden":true },
    { "col_key":"H_PROD_NAME",   "col_name":"品名(中间)",       "title":"品名(中间)",       "source_type":"VARIABLE", "variable_path":"{product_name}",                              "visible":false, "hidden":true },
    { "col_key":"H_SPEC",        "col_name":"规格(中间)",       "title":"规格(中间)",       "source_type":"VARIABLE", "variable_path":"{specification}",                             "visible":false, "hidden":true },
    { "col_key":"H_WEIGHT",      "col_name":"单重(中间)",       "title":"单重(中间)",       "source_type":"VARIABLE", "variable_path":"v_q_part_info_merged.unit_weight",            "visible":false, "hidden":true },
    { "col_key":"H_WEIGHT_UNIT", "col_name":"重量单位(中间)",   "title":"重量单位(中间)",   "source_type":"VARIABLE", "variable_path":"v_q_part_info_merged.weight_unit",            "visible":false, "hidden":true },
    { "col_key":"H_BASE_CCY",    "col_name":"基础货币(中间)",   "title":"基础货币(中间)",   "source_type":"VARIABLE", "variable_path":"v_q_part_info_merged.base_currency",          "visible":false, "hidden":true },
    { "col_key":"H_QUOTE_CCY",   "col_name":"报价货币(中间)",   "title":"报价货币(中间)",   "source_type":"VARIABLE", "variable_path":"v_q_part_info_merged.quote_currency",         "visible":false, "hidden":true },
    { "col_key":"H_FX",          "col_name":"汇率(中间)",       "title":"汇率(中间)",       "source_type":"VARIABLE", "variable_path":"v_q_part_info_merged.exchange_rate",          "visible":false, "hidden":true },
    { "col_key":"H_PAY",         "col_name":"付款方式(中间)",   "title":"付款方式(中间)",   "source_type":"VARIABLE", "variable_path":"v_q_part_info_merged.payment_method",         "visible":false, "hidden":true },
    { "col_key":"H_SUBTOTAL",    "col_name":"报价行小计(中间)", "title":"报价行小计(中间)", "source_type":"VARIABLE", "variable_path":"{subtotal}",                                  "visible":false, "hidden":true },
    { "col_key":"A", "col_index":"A", "col_name":"宏丰料号",   "title":"宏丰料号",   "source_type":"FORMULA", "formula":"=[H_HF_NO]",       "visible":true, "hidden":false, "comparison_tag":"HF_PART_NO" },
    { "col_key":"B", "col_index":"B", "col_name":"客户料号",   "title":"客户料号",   "source_type":"FORMULA", "formula":"=[H_CUST_NAME]",   "visible":true, "hidden":false, "comparison_tag":"CUSTOMER_PART_NAME" },
    { "col_key":"C", "col_index":"C", "col_name":"品名",       "title":"品名",       "source_type":"FORMULA", "formula":"=[H_PROD_NAME]",   "visible":true, "hidden":false, "comparison_tag":"PRODUCT_NAME" },
    { "col_key":"D", "col_index":"D", "col_name":"规格",       "title":"规格",       "source_type":"FORMULA", "formula":"=[H_SPEC]",        "visible":true, "hidden":false },
    { "col_key":"E", "col_index":"E", "col_name":"单重(g/pcs)","title":"单重(g/pcs)","source_type":"FORMULA", "formula":"=[H_WEIGHT]",      "visible":true, "hidden":false, "comparison_tag":"UNIT_WEIGHT" },
    { "col_key":"F", "col_index":"F", "col_name":"重量单位",   "title":"重量单位",   "source_type":"FORMULA", "formula":"=[H_WEIGHT_UNIT]", "visible":true, "hidden":false },
    { "col_key":"G", "col_index":"G", "col_name":"基础货币",   "title":"基础货币",   "source_type":"FORMULA", "formula":"=[H_BASE_CCY]",    "visible":true, "hidden":false },
    { "col_key":"H", "col_index":"H", "col_name":"报价货币",   "title":"报价货币",   "source_type":"FORMULA", "formula":"=[H_QUOTE_CCY]",   "visible":true, "hidden":false },
    { "col_key":"I", "col_index":"I", "col_name":"汇率",       "title":"汇率",       "source_type":"FORMULA", "formula":"=[H_FX]",          "visible":true, "hidden":false, "comparison_tag":"FX_RATE" },
    { "col_key":"J", "col_index":"J", "col_name":"付款方式",   "title":"付款方式",   "source_type":"FORMULA", "formula":"=[H_PAY]",         "visible":true, "hidden":false },
    { "col_key":"K", "col_index":"K", "col_name":"单价",       "title":"单价",       "source_type":"FORMULA", "formula":"=[H_SUBTOTAL]",    "visible":true, "hidden":false, "comparison_tag":"UNIT_PRICE" }
  ]'::jsonb;

  -- ============================================================
  -- 4. INSERT v1.7 DRAFT (同 series / 同客户 / 同分类, components_snapshot 暂留 NULL)
  -- ============================================================
  INSERT INTO template (
    id, template_series_id, name, version, category, description, usage_note,
    product_attributes, subtotal_formula, components_snapshot, status,
    created_by, published_at, created_at, updated_at,
    excel_view_config, customer_id, category_id, template_kind, formulas, is_default,
    referenced_variables
  ) VALUES (
    v_new_id, v_series_id,
    '报价标准模板-Excel基础结构 v1.0',
    'v1.7',
    v_category,
    COALESCE(v_description,'') || E'\n[V216] v1.7 DRAFT 派生自 v1.6 — INPUT_NUMBER 11 + is_subtotal 5 + subtotal_formula + excel_view_config',
    v_usage_note,
    COALESCE(v_product_attrs, '[]'::jsonb),
    v_subtotal,
    NULL,                  -- snapshot 末步与 publish 等价生成
    'DRAFT',
    NULL,
    NULL,
    now(), now(),
    v_excel_view,
    v_customer_id,
    v_category_id,
    v_kind,
    '[]'::jsonb,
    false,
    '[]'::jsonb
  );

  -- ============================================================
  -- 5. INSERT 9 个 template_component (从 v1.6 拷贝, fields_override 写入升级 JSON)
  --    料件 / 来料 / 元素 / 成品 / 组成件BOM / 组装加工 / 电镀 → fields_override 非空
  --    小计1 / 组成件其他费用 → 看下面 (小计1 fields_override=NULL; 组件其他费用 写)
  --    注:本方案"组件其他费用"对应的 component code 是 COMP-QX-COMPONENT-FEE, tabName="组成件其他费用"
  -- ============================================================
  INSERT INTO template_component (
    id, template_id, component_id, sort_order, tab_name,
    preset_rows, formula_assignments,
    data_driver_path_override, fields_override, data_driver_path_composite
  )
  SELECT
    gen_random_uuid(), v_new_id, tc.component_id, tc.sort_order, tc.tab_name,
    COALESCE(tc.preset_rows, '[]'::jsonb),
    COALESCE(tc.formula_assignments, '{}'::jsonb),
    tc.data_driver_path_override,
    -- fields_override: 按 tabName 派发升级后的 fields
    CASE tc.tab_name
      WHEN '料件'           THEN v_fo_part_info
      WHEN '来料'           THEN v_fo_incoming
      WHEN '元素'           THEN v_fo_element
      WHEN '成品'           THEN v_fo_finished
      WHEN '组成件BOM'      THEN v_fo_component
      WHEN '组装加工'       THEN v_fo_assembly
      WHEN '电镀'           THEN v_fo_plating
      WHEN '组成件其他费用' THEN v_fo_comp_fee
      ELSE tc.fields_override     -- 小计1 (SUBTOTAL) 保持 NULL
    END,
    tc.data_driver_path_composite
    FROM template_component tc
   WHERE tc.template_id = v_source_id
   ORDER BY tc.sort_order ASC;

  -- ============================================================
  -- 6. 同步预填 components_snapshot (与 publish() rebuild 等价)
  --    让 v1.7 DRAFT 在 UI 上即可完整渲染评审, 不必依赖 publish
  -- ============================================================
  SELECT jsonb_agg(
           jsonb_build_object(
             'id',               tc.id::text,
             'componentId',      c.id::text,
             'componentName',    c.name,
             'componentCode',    c.code,
             'componentType',    c.component_type,
             'tabName',          tc.tab_name,
             'sortOrder',        tc.sort_order,
             'fields',           CASE
                                   WHEN tc.fields_override IS NOT NULL
                                   THEN tc.fields_override
                                   ELSE COALESCE(c.fields, '[]'::jsonb)
                                 END,
             'formulas',         COALESCE(c.formulas, '[]'::jsonb),
             'preset_rows',      COALESCE(tc.preset_rows, '[]'::jsonb),
             'data_driver_path', COALESCE(tc.data_driver_path_override, c.data_driver_path),
             'formula_assignments', COALESCE(tc.formula_assignments, '{}'::jsonb)
           )
           ORDER BY tc.sort_order ASC
         )
    INTO v_snapshot
    FROM template_component tc
    JOIN component c ON c.id = tc.component_id
   WHERE tc.template_id = v_new_id;

  UPDATE template SET components_snapshot = v_snapshot WHERE id = v_new_id;

  RAISE NOTICE 'V216 OK: v1.7 DRAFT id=%, series=%, customer=%, snapshot_tabs=%',
    v_new_id, v_series_id, v_customer_id,
    jsonb_array_length(v_snapshot);
END
$V216$;

-- ============================================================
-- 7. 验证 (跑完 Flyway 自检)
-- ============================================================
DO $V216_CHK$
DECLARE
  v_v17 RECORD;
  v_input_cnt INT;
  v_subtotal_cnt INT;
  v_legacy_draft_status VARCHAR;
BEGIN
  -- 验证 v1.7 DRAFT 已生成
  SELECT t.id, t.version, t.status,
         (t.subtotal_formula IS NOT NULL AND jsonb_array_length(t.subtotal_formula) > 0) AS has_subtotal,
         (t.excel_view_config IS NOT NULL AND jsonb_array_length(t.excel_view_config) > 0) AS has_evc,
         jsonb_array_length(t.components_snapshot) AS tabs_cnt,
         (SELECT COUNT(*) FROM template_component WHERE template_id = t.id) AS tc_cnt
    INTO v_v17
    FROM template t
   WHERE t.template_series_id = '66eb97f0-ec24-4455-933b-c8c26a3190f6'
     AND t.version = 'v1.7' AND t.status = 'DRAFT'
   ORDER BY t.created_at DESC LIMIT 1;

  IF v_v17.id IS NULL THEN
    RAISE EXCEPTION 'V216 verify FAIL: v1.7 DRAFT not created';
  END IF;

  IF NOT v_v17.has_subtotal THEN
    RAISE EXCEPTION 'V216 verify FAIL: subtotal_formula empty on v1.7';
  END IF;

  IF NOT v_v17.has_evc THEN
    RAISE EXCEPTION 'V216 verify FAIL: excel_view_config empty on v1.7';
  END IF;

  IF v_v17.tabs_cnt <> 9 THEN
    RAISE EXCEPTION 'V216 verify FAIL: expected 9 tabs in snapshot, got %', v_v17.tabs_cnt;
  END IF;

  IF v_v17.tc_cnt <> 9 THEN
    RAISE EXCEPTION 'V216 verify FAIL: expected 9 template_component, got %', v_v17.tc_cnt;
  END IF;

  -- 验证 15 个 INPUT_NUMBER 字段
  -- (料件 汇率 1 + 来料 5 + 元素 3 + 成品 1 + BOM 1 + 组装 1 + 电镀 3 = 15)
  SELECT COUNT(*) INTO v_input_cnt
    FROM template t,
         jsonb_array_elements(t.components_snapshot) AS arr(tab),
         jsonb_array_elements(arr.tab->'fields') AS f
   WHERE t.id = v_v17.id AND f->>'field_type' = 'INPUT_NUMBER';

  IF v_input_cnt <> 15 THEN
    RAISE EXCEPTION 'V216 verify FAIL: expected 15 INPUT_NUMBER fields, got %', v_input_cnt;
  END IF;

  -- 验证 5 个 is_subtotal=true 字段
  SELECT COUNT(*) INTO v_subtotal_cnt
    FROM template t,
         jsonb_array_elements(t.components_snapshot) AS arr(tab),
         jsonb_array_elements(arr.tab->'fields') AS f
   WHERE t.id = v_v17.id AND (f->>'is_subtotal')::boolean = true;

  IF v_subtotal_cnt <> 5 THEN
    RAISE EXCEPTION 'V216 verify FAIL: expected 5 is_subtotal=true fields, got %', v_subtotal_cnt;
  END IF;

  -- 验证遗留 DRAFT 已 ARCHIVED
  SELECT status INTO v_legacy_draft_status
    FROM template WHERE id = 'bc0a5dc6-8da6-4a90-8c72-333a389fb890';
  IF v_legacy_draft_status IS NOT NULL AND v_legacy_draft_status = 'DRAFT' THEN
    RAISE EXCEPTION 'V216 verify FAIL: legacy DRAFT bc0a5dc6 still DRAFT (should be ARCHIVED)';
  END IF;

  RAISE NOTICE 'V216 verify OK: v1.7 DRAFT id=%, tabs=%, INPUT_NUMBER=%, is_subtotal=%, legacy_draft_status=%',
    v_v17.id, v_v17.tabs_cnt, v_input_cnt, v_subtotal_cnt, COALESCE(v_legacy_draft_status,'(deleted)');
END
$V216_CHK$;
