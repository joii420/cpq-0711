/* eslint-disable */
/**
 * 真实单据夹具 —— repair-0805 / BL-0112
 *
 *   单据号     QT-20260804-0068
 *   quotation  be95ded9-6bda-4c25-8c88-ea836acd5d0d
 *   lineItem   8205e1d8-ebe3-4a43-b6f9-4579afe94eec（销售料号 3120011203）
 *   template   88d5d815-385b-45ca-bd4b-de0e0bad8a30（测试客户-4 v1.0 / PUBLISHED）
 *   导出日期   2026-08-05（开发库 10.177.152.12:5432/cpq_db_0724）
 *
 * 【为什么用真实数据】本缺陷的两个根因都是「真实配置形状 vs 类型声明形状」的错配
 * （公式侧 `id` / 字段侧 `defaultSource` camel-snake 分歧）——手搓玩具夹具会把两边一起写对，
 * 测不出问题。见 dev-docs/repair-0803-BL0098-公式绑定改绑ID/repair-0805-… 目录下的「问题分析报告.md」。
 *
 * 【裁剪口径】只保留「物料」页签 + 它 cross_tab_ref 引用的 3 个源页签
 * （材料成本 / 来料固定加工费 / 来料其他费用）。该子图对外封闭：3 个源页签自身 formulas=[]，
 * 且「物料」的公式里没有 component_subtotal token，故裁剪不改变任何一个被断言的数值。
 * 另删掉了 resolvedRows / excelColumns / preset_rows / productAttributes（本轮用例不消费）。
 *
 * 🚨 请勿手工编辑本文件的数据体 —— 它是线上快照的逐字拷贝，改了就不再是对拍基准。
 */

/** `GET /api/cpq/templates/88d5d815-...` → `data.componentsSnapshot`（模板快照路径 enrichComponentData 的输入，snake_case 字段）。 */
export const QT0068_TEMPLATE_COMPONENTS_SNAPSHOT = [
  {
    "id": "0f98cca5-0b5e-406f-8e31-0a839452c49f",
    "fields": [
      {
        "name": "料件",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 0,
        "is_subtotal": false,
        "default_source": {"path": "$bom_view._料件", "type": "BASIC_DATA"}
      },
      {
        "name": "组成数量",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 1,
        "is_subtotal": false,
        "default_source": {"path": "$bom_view._组成数量", "type": "BASIC_DATA"}
      },
      {
        "name": "产出类型",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 2,
        "is_subtotal": false,
        "default_source": {"path": "$bom_view._产出类型", "type": "BASIC_DATA"}
      },
      {
        "name": "材料毛重",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 3,
        "is_subtotal": false,
        "default_source": {"path": "$bom_view._材料毛重", "type": "BASIC_DATA"},
        "unit_source_field": "单位"
      },
      {
        "name": "材料净重",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 4,
        "is_subtotal": false,
        "default_source": {"path": "$bom_view._材料净重", "type": "BASIC_DATA"},
        "unit_source_field": "单位"
      },
      {
        "name": "单位",
        "notes": "重量单位 material_bom_item.weight_unit(用户 2026-07-27 确认)",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 5,
        "is_subtotal": false,
        "default_source": {"path": "$bom_view._单位", "type": "BASIC_DATA"}
      },
      {
        "name": "材料占比",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 6,
        "is_subtotal": false,
        "default_source": {"path": "$bom_view._材料占比", "type": "BASIC_DATA"}
      },
      {
        "name": "损耗率",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 7,
        "is_subtotal": false,
        "default_source": {"path": "$bom_view._损耗率", "type": "BASIC_DATA"}
      },
      {
        "name": "来料回收费",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "FORMULA",
        "formula_id": "a6e2b86e-b5e3-4649-a3c3-c04c7440654d",
        "sort_order": 8,
        "is_subtotal": false,
        "formula_name": "来料回收费取值公式"
      },
      {
        "name": "来料财务费",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "FORMULA",
        "formula_id": "1b3687b6-91b2-4b8a-88b2-e32cc730d71f",
        "sort_order": 9,
        "is_subtotal": false,
        "formula_name": "来料财务费取值公式"
      },
      {
        "name": "材料成本",
        "notes": "",
        "content": "",
        "is_amount": true,
        "field_type": "FORMULA",
        "sort_order": 10,
        "is_subtotal": true,
        "conditional_formula": {"rules": [{"when": {"kind": "group", "logic": "and", "children": [{"op": "eq", "rhs": {"type": "literal", "value": "非银点类"}, "kind": "leaf", "left": "产出类型"}]}, "formula": "非银点类材料成本公式", "formula_id": "cb3ea05c-74d4-4a69-870f-79cd5b74376a"}], "default": "银点材料成本公式", "default_formula_id": "c9213b82-cd55-484d-87e1-f9d95c13718f"}
      },
      {
        "name": "材料损耗成本",
        "notes": "",
        "content": "",
        "is_amount": true,
        "field_type": "FORMULA",
        "formula_id": "a436b159-8cb6-4134-974f-05a190e05c94",
        "sort_order": 11,
        "is_subtotal": true,
        "formula_name": "材料损耗成本"
      },
      {
        "name": "来料损耗率",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "FORMULA",
        "formula_id": "1795a8e7-f44d-4bc2-9fb1-1cde8f6d3125",
        "sort_order": 12,
        "is_subtotal": false,
        "formula_name": "来料损耗率取值公式"
      },
      {
        "name": "来料加工费",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "FORMULA",
        "formula_id": "b0e76441-4431-4436-844b-35c72b884867",
        "sort_order": 13,
        "is_subtotal": false,
        "formula_name": "来料加工费取值公式"
      },
      {
        "name": "回收价格",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 14,
        "is_subtotal": false,
        "default_source": {"path": "$bom_view._回收价格", "type": "BASIC_DATA"}
      },
      {
        "name": "回收成本",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "FORMULA",
        "formula_id": "4aee4475-668f-4eb5-94ff-6c887811a3d7",
        "sort_order": 15,
        "is_subtotal": true,
        "formula_name": "回收成本"
      },
      {
        "name": "公式10",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "FORMULA",
        "formula_id": "bda241be-14b5-4138-915d-e1753ac832ed",
        "sort_order": 16,
        "is_subtotal": false,
        "formula_name": "公式10"
      },
      {
        "name": "原材料成本",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "FORMULA",
        "formula_id": "a67fe74f-d320-4241-9cff-0084689b0765",
        "is_subtotal": true,
        "formula_name": "公式13"
      },
      {
        "name": "材料价格",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "FORMULA",
        "formula_id": "ed98e428-d125-4a39-9091-9cd3cdc62b98",
        "is_subtotal": true,
        "formula_name": "公式14"
      },
      {
        "name": "铆钉额外费用",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "FORMULA",
        "formula_id": "0d2bbf8d-17bb-4f44-966b-28150aab258f",
        "is_subtotal": true,
        "formula_name": "铆钉额外费用"
      }
    ],
    "tabName": "物料",
    "formulas": [
      {
        "id": "a6e2b86e-b5e3-4649-a3c3-c04c7440654d",
        "name": "来料回收费取值公式",
        "expression": [{"agg": "SUM", "type": "cross_tab_ref", "match": [], "source": "554bdcda-64dd-41cc-9ca6-99b3d716a388", "target": "", "predicate": {"bool": "AND", "children": [{"op": "=", "lhs": {"kind": "sourceField", "field": "要素"}, "rhs": {"kind": "literal", "value": "来料回收费"}}, {"op": "=", "lhs": {"kind": "sourceField", "field": "料件"}, "rhs": {"kind": "hostField", "field": "料件"}}]}, "targetExpr": [{"type": "field", "value": "比例", "source": "554bdcda-64dd-41cc-9ca6-99b3d716a388"}], "sourceLabel": "来料其他费用"}],
        "result_type": "NUMBER"
      },
      {
        "id": "1b3687b6-91b2-4b8a-88b2-e32cc730d71f",
        "name": "来料财务费取值公式",
        "expression": [{"agg": "SUM", "type": "cross_tab_ref", "match": [], "source": "554bdcda-64dd-41cc-9ca6-99b3d716a388", "target": "", "predicate": {"bool": "AND", "children": [{"op": "=", "lhs": {"kind": "sourceField", "field": "要素"}, "rhs": {"kind": "literal", "value": "来料财务费"}}, {"op": "=", "lhs": {"kind": "sourceField", "field": "料件"}, "rhs": {"kind": "hostField", "field": "料件"}}]}, "targetExpr": [{"type": "field", "value": "比例", "source": "554bdcda-64dd-41cc-9ca6-99b3d716a388"}], "sourceLabel": "来料其他费用"}],
        "result_type": "NUMBER"
      },
      {
        "id": "c9213b82-cd55-484d-87e1-f9d95c13718f",
        "name": "银点材料成本公式",
        "expression": [{"type": "bracket_open"}, {"type": "bracket_open"}, {"type": "field", "value": "材料占比"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"agg": "SUM", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56", "target": "", "targetExpr": [{"type": "field", "value": "组成含量(%)", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "元素单价", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "number", "value": "1"}, {"type": "operator", "value": "+"}, {"type": "b_field", "value": "来料损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}], "sourceLabel": "材料成本"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "来料加工费"}, {"type": "bracket_close"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}, {"type": "bracket_close"}, {"type": "operator", "value": "*"}, {"type": "number", "value": "1000"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "21000"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "组成数量"}],
        "result_type": "NUMBER"
      },
      {
        "id": "cb3ea05c-74d4-4a69-870f-79cd5b74376a",
        "name": "非银点类材料成本公式",
        "expression": [{"type": "field", "value": "材料毛重"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "bracket_open"}, {"agg": "SUM", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56", "target": "", "targetExpr": [{"type": "field", "value": "组成含量(%)", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "元素单价", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}], "sourceLabel": "材料成本"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "number", "value": "1"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "来料损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "bracket_close"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}, {"type": "operator", "value": "+"}, {"agg": "NONE", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "2a3ded4a-be05-420d-b5a9-450e85eb1ed1", "target": "加工费", "sourceLabel": "来料固定加工费"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}, {"type": "bracket_close"}],
        "result_type": "NUMBER"
      },
      {
        "id": "a436b159-8cb6-4134-974f-05a190e05c94",
        "name": "材料损耗成本",
        "expression": [{"type": "number", "value": "1000"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "21000"}, {"type": "operator", "value": "*"}, {"agg": "SUM", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56", "target": "", "targetExpr": [{"type": "bracket_open"}, {"type": "field", "value": "组成含量(%)", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "元素单价", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "*"}, {"type": "b_field", "value": "损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}], "sourceLabel": "材料成本"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "组成数量"}],
        "result_type": "NUMBER"
      },
      {
        "id": "5402f829-ae3f-42e5-949e-9379ee06ab03",
        "name": "默认公式0",
        "expression": [{"type": "number", "value": "0"}],
        "result_type": "NUMBER"
      },
      {
        "id": "1795a8e7-f44d-4bc2-9fb1-1cde8f6d3125",
        "name": "来料损耗率取值公式",
        "expression": [{"agg": "SUM", "type": "cross_tab_ref", "match": [], "source": "554bdcda-64dd-41cc-9ca6-99b3d716a388", "target": "", "predicate": {"bool": "AND", "children": [{"op": "=", "lhs": {"kind": "sourceField", "field": "要素"}, "rhs": {"kind": "literal", "value": "来料损耗率"}}, {"op": "=", "lhs": {"kind": "sourceField", "field": "料件"}, "rhs": {"kind": "hostField", "field": "料件"}}]}, "targetExpr": [{"type": "field", "value": "比例", "source": "554bdcda-64dd-41cc-9ca6-99b3d716a388"}], "sourceLabel": "来料其他费用"}],
        "result_type": "NUMBER"
      },
      {
        "id": "b0e76441-4431-4436-844b-35c72b884867",
        "name": "来料加工费取值公式",
        "expression": [{"agg": "SUM", "type": "cross_tab_ref", "match": [], "source": "554bdcda-64dd-41cc-9ca6-99b3d716a388", "target": "", "predicate": {"bool": "AND", "children": [{"op": "=", "lhs": {"kind": "sourceField", "field": "要素"}, "rhs": {"kind": "literal", "value": "来料加工费"}}, {"op": "=", "lhs": {"kind": "sourceField", "field": "料件"}, "rhs": {"kind": "hostField", "field": "料件"}}]}, "targetExpr": [{"type": "field", "value": "费用", "source": "554bdcda-64dd-41cc-9ca6-99b3d716a388"}], "sourceLabel": "来料其他费用"}],
        "result_type": "NUMBER"
      },
      {
        "id": "4aee4475-668f-4eb5-94ff-6c887811a3d7",
        "name": "回收成本",
        "expression": [{"type": "bracket_open"}, {"type": "field", "value": "材料毛重"}, {"type": "operator", "value": "-"}, {"type": "field", "value": "材料净重"}, {"type": "bracket_close"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "bracket_open"}, {"agg": "SUM", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56", "target": "", "targetExpr": [{"type": "field", "value": "组成含量(%)", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "元素单价", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}], "sourceLabel": "材料成本"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "number", "value": "1"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "来料损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "bracket_close"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}, {"type": "operator", "value": "+"}, {"agg": "NONE", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "2a3ded4a-be05-420d-b5a9-450e85eb1ed1", "target": "加工费", "sourceLabel": "来料固定加工费"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}, {"type": "bracket_close"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "回收价格"}],
        "result_type": "NUMBER"
      },
      {
        "id": "bda241be-14b5-4138-915d-e1753ac832ed",
        "name": "公式10",
        "expression": [{"type": "bracket_open"}, {"agg": "SUM", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56", "target": "", "targetExpr": [{"type": "field", "value": "组成含量(%)", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "元素单价", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}], "sourceLabel": "材料成本"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "number", "value": "1"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "来料损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "来料加工费"}, {"type": "bracket_close"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}],
        "result_type": "NUMBER"
      },
      {
        "id": "8be08ad7-9804-410e-9fd3-26f806ae420c",
        "name": "公式11",
        "expression": [{"type": "bracket_open"}, {"agg": "SUM", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56", "target": "", "targetExpr": [{"type": "field", "value": "组成含量(%)", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "元素单价", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}], "sourceLabel": "材料成本"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "number", "value": "1"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "来料损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "bracket_close"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}],
        "result_type": "NUMBER"
      },
      {
        "id": "b625d188-3d4a-4c0f-8531-80f3f0c656cb",
        "name": "公式12",
        "expression": [{"agg": "NONE", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "2a3ded4a-be05-420d-b5a9-450e85eb1ed1", "target": "加工费", "sourceLabel": "来料固定加工费"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}],
        "result_type": "NUMBER"
      },
      {
        "id": "a67fe74f-d320-4241-9cff-0084689b0765",
        "name": "公式13",
        "expression": [{"type": "bracket_open"}, {"type": "field", "value": "材料占比"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"agg": "SUM", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56", "target": "", "targetExpr": [{"type": "field", "value": "组成含量(%)", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "元素单价", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "number", "value": "1"}, {"type": "operator", "value": "+"}, {"type": "b_field", "value": "来料损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}], "sourceLabel": "材料成本"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "来料加工费"}, {"type": "bracket_close"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}],
        "result_type": "NUMBER"
      },
      {
        "id": "ed98e428-d125-4a39-9091-9cd3cdc62b98",
        "name": "公式14",
        "expression": [{"type": "bracket_open"}, {"type": "bracket_open"}, {"type": "field", "value": "材料占比"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"agg": "SUM", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56", "target": "", "targetExpr": [{"type": "field", "value": "组成含量(%)", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "元素单价", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "number", "value": "1"}, {"type": "operator", "value": "+"}, {"type": "b_field", "value": "来料损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}], "sourceLabel": "材料成本"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "来料加工费"}, {"type": "bracket_close"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}, {"type": "bracket_close"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "材料净重"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "组成数量"}],
        "result_type": "NUMBER"
      },
      {
        "id": "0d2bbf8d-17bb-4f44-966b-28150aab258f",
        "name": "铆钉额外费用",
        "expression": [{"type": "bracket_open"}, {"type": "bracket_open"}, {"type": "field", "value": "材料占比"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "operator", "value": "*"}, {"agg": "SUM", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56", "target": "", "targetExpr": [{"type": "bracket_open"}, {"type": "field", "value": "组成含量(%)", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "元素单价", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "number", "value": "1"}, {"type": "operator", "value": "+"}, {"type": "b_field", "value": "来料损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "operator", "value": "+"}, {"type": "b_field", "value": "来料加工费"}, {"type": "bracket_close"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}], "sourceLabel": "材料成本"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "field", "value": "来料财务费"}, {"type": "operator", "value": "+"}, {"type": "field", "value": "来料回收费"}, {"type": "bracket_close"}, {"type": "operator", "value": "+"}, {"agg": "NONE", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "2a3ded4a-be05-420d-b5a9-450e85eb1ed1", "target": "加工费", "sourceLabel": "来料固定加工费"}, {"type": "bracket_close"}],
        "result_type": "NUMBER"
      },
      {
        "id": "ab5ae48d-35b6-485b-8aa0-1d5b64a3c4c4",
        "name": "银点1.0",
        "expression": [{"type": "bracket_open"}, {"type": "field", "value": "材料占比"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"agg": "SUM", "type": "cross_tab_ref", "match": [{"a": "料件", "b": "料件"}], "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56", "target": "", "targetExpr": [{"type": "field", "value": "组成含量(%)", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "元素单价", "source": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56"}, {"type": "operator", "value": "*"}, {"type": "bracket_open"}, {"type": "number", "value": "1"}, {"type": "operator", "value": "+"}, {"type": "b_field", "value": "来料损耗率"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "100"}, {"type": "bracket_close"}, {"type": "operator", "value": "+"}, {"type": "b_field", "value": "来料加工费"}], "sourceLabel": "材料成本"}, {"type": "bracket_close"}, {"type": "operator", "value": "/"}, {"type": "number", "value": "1.13"}, {"type": "bracket_close"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "材料净重"}, {"type": "operator", "value": "*"}, {"type": "field", "value": "组成数量"}],
        "result_type": "NUMBER"
      }
    ],
    "tab_type": "BOM",
    "sortOrder": 1,
    "componentId": "2db185d6-2b5f-4617-bbc5-6957d6b735e2",
    "tree_config": null,
    "componentCode": "COMP-0185",
    "componentName": "物料",
    "componentType": "NORMAL",
    "part_no_field": null,
    "part_name_field": "料件",
    "data_driver_path": "$bom_view",
    "formula_assignments": {},
    "bom_recursive_expand": true
  },
  {
    "id": "8efce30d-5d67-4eae-b78a-26c33acfea21",
    "fields": [
      {
        "name": "销售料号",
        "notes": "所属销售料号(element_bom_item.material_no)；BOM 闭包后区分主件/子件行，已进行键",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 0,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view._销售料号", "type": "BASIC_DATA"}
      },
      {
        "name": "料号",
        "notes": "材质料号(element_bom_item.material_part_no)=材质元素页签的语义料号(料号铁律 §3.4)",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 1,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view._料号", "type": "BASIC_DATA"}
      },
      {
        "name": "料件",
        "notes": "材质名，逐行取自本行 material_part_no(一号多材质铁律)",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 2,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view._料件", "type": "BASIC_DATA"}
      },
      {
        "name": "项次",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 4,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view._项次", "type": "BASIC_DATA"}
      },
      {
        "name": "元素",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 5,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view._元素", "type": "BASIC_DATA"}
      },
      {
        "name": "组成含量(%)",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 6,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view._组成含量", "type": "BASIC_DATA"}
      },
      {
        "name": "损耗率%",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 7,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view._损耗率", "type": "BASIC_DATA"}
      },
      {
        "name": "毛重",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 8,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view._毛重", "type": "BASIC_DATA"},
        "unit_source_field": "毛用量单位"
      },
      {
        "name": "毛用量单位",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 9,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view._毛用量单位", "type": "BASIC_DATA"}
      },
      {
        "name": "元素单价",
        "notes": "接客户价格策略 f_customer_element_price(无价留 NULL 手填，禁 COALESCE 兜 0)；别名逐字不加 _",
        "content": "",
        "is_amount": true,
        "field_type": "INPUT_NUMBER",
        "sort_order": 10,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view.元素单价", "type": "BASIC_DATA"}
      },
      {
        "name": "货币",
        "notes": "价格策略列，别名逐字不加 _",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 11,
        "is_subtotal": false,
        "default_source": {"path": "$mc_view.货币", "type": "BASIC_DATA"}
      }
    ],
    "tabName": "材料成本",
    "formulas": [],
    "tab_type": "材质元素",
    "sortOrder": 2,
    "componentId": "4a193e48-5ce0-4a6a-a36c-60aafadd9a56",
    "tree_config": null,
    "componentCode": "COMP-0045",
    "componentName": "材料成本",
    "componentType": "NORMAL",
    "part_no_field": "料号",
    "part_name_field": "料件",
    "data_driver_path": "$mc_view",
    "formula_assignments": {},
    "bom_recursive_expand": false
  },
  {
    "id": "d77306bd-353f-4169-96be-bf11898a3d98",
    "fields": [
      {
        "name": "销售料号",
        "notes": "所属销售料号(unit_price.finished_material_no)；已进行键",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 0,
        "is_subtotal": false,
        "default_source": {"path": "$ll_view._销售料号", "type": "BASIC_DATA"}
      },
      {
        "name": "料件",
        "notes": "来料件名称，经 up.code 取名",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 1,
        "is_subtotal": false,
        "default_source": {"path": "$ll_view._料件", "type": "BASIC_DATA"}
      },
      {
        "name": "项次",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 2,
        "is_subtotal": false,
        "default_source": {"path": "$ll_view._项次", "type": "BASIC_DATA"}
      },
      {
        "name": "加工费",
        "notes": "COALESCE(pricing_price, base_value)：现网来料加工费金额存在 base_value",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 4,
        "is_subtotal": false,
        "default_source": {"path": "$ll_view._加工费", "type": "BASIC_DATA"},
        "unit_source_field": "单位"
      },
      {
        "name": "单位",
        "notes": "",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 5,
        "is_subtotal": false,
        "default_source": {"path": "$ll_view._单位", "type": "BASIC_DATA"}
      }
    ],
    "tabName": "来料固定加工费",
    "formulas": [],
    "tab_type": null,
    "sortOrder": 4,
    "componentId": "2a3ded4a-be05-420d-b5a9-450e85eb1ed1",
    "tree_config": null,
    "componentCode": "COMP-0047",
    "componentName": "来料固定加工费",
    "componentType": "NORMAL",
    "part_no_field": null,
    "part_name_field": null,
    "data_driver_path": "$ll_view",
    "formula_assignments": {},
    "bom_recursive_expand": false
  },
  {
    "id": "dd19a2f8-40f1-4f18-95cb-4e20042d1a4e",
    "fields": [
      {
        "name": "销售料号",
        "notes": "所属成品料号(unit_price.finished_material_no)；已进行键，兼作 §3.8.4 归属料号(闭包后同料件可能挂多个成品)",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 0,
        "is_subtotal": false,
        "default_source": {"path": "$lqt_view._销售料号", "type": "BASIC_DATA"}
      },
      {
        "name": "料号",
        "notes": "投入料号(unit_price.code)；模板未勾「料号列」→ partNoField 留空(§3.4)，此列仅作可见列 + 取名 JOIN 键",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 1,
        "is_subtotal": false,
        "default_source": {"path": "$lqt_view._料号", "type": "BASIC_DATA"}
      },
      {
        "name": "料件",
        "notes": "投入料件名称，经 up.code 取 COALESCE(material_master.material_name, material_recipe.name)；已进行键",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 2,
        "is_subtotal": false,
        "default_source": {"path": "$lqt_view._料件", "type": "BASIC_DATA"}
      },
      {
        "name": "项次",
        "notes": "unit_price.seq_no；模板「排序列」留空，按 §3.1「多行页签建议配项次」推导为 sortField",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 3,
        "is_subtotal": false,
        "default_source": {"path": "$lqt_view._项次", "type": "BASIC_DATA"}
      },
      {
        "name": "要素",
        "notes": "unit_price.cost_type(来料管理费等)；已进行键。模板备注：来料管理费 - 来自来料其他费用",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 4,
        "is_subtotal": false,
        "default_source": {"path": "$lqt_view._要素", "type": "BASIC_DATA"}
      },
      {
        "name": "费用",
        "notes": "固定金额 up.pricing_price(Q07 口径：按比例登记的费用项此列为 NULL，值在「比例」列)；R7 可沿行累加→is_subtotal=true",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 5,
        "is_subtotal": false,
        "default_source": {"path": "$lqt_view._费用", "type": "BASIC_DATA"},
        "unit_source_field": "计价单位"
      },
      {
        "name": "比例",
        "notes": "up.cost_ratio；比率非金额 → is_amount=false(R6)，不参与页签金额合计",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_NUMBER",
        "sort_order": 6,
        "is_subtotal": false,
        "default_source": {"path": "$lqt_view._比例", "type": "BASIC_DATA"}
      },
      {
        "name": "计价单位",
        "notes": "unit_price.unit",
        "content": "",
        "is_amount": false,
        "field_type": "INPUT_TEXT",
        "sort_order": 7,
        "is_subtotal": false,
        "default_source": {"path": "$lqt_view._计价单位", "type": "BASIC_DATA"}
      }
    ],
    "tabName": "来料其他费用",
    "formulas": [],
    "tab_type": null,
    "sortOrder": 5,
    "componentId": "554bdcda-64dd-41cc-9ca6-99b3d716a388",
    "tree_config": null,
    "componentCode": "COMP-0046",
    "componentName": "来料其他费用",
    "componentType": "NORMAL",
    "part_no_field": null,
    "part_name_field": null,
    "data_driver_path": "$lqt_view",
    "formula_assignments": {},
    "bom_recursive_expand": false
  }
] as any;
