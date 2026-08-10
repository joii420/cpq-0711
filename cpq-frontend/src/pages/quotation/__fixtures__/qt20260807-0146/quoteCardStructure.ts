/* eslint-disable */
/**
 * 真实单据夹具 —— repair-0808（前端页签算序假环致列小计归零）
 *
 *   单据号     QT-20260807-0146
 *   quotation  6d014a9a-fe27-432a-bce9-7f6c86c50775
 *   lineItem   5eb0f2de-dc0e-42bf-a979-9240940507ec（销售料号 3120011203）
 *   template   7fd1ecd8-ec52-4bc5-9104-98189d1e0761（「测试BUG-2」v1.0 / PUBLISHED）
 *   导出日期   2026-08-08（开发库 10.177.152.12:5432/cpq_db_0724，SQL 见 test.md §5）
 *
 * 【为什么用真实数据】本缺陷的根因是「产品」页签 INPUT_NUMBER 列「税率」被 component_subtotal
 * 引用时，旧的页签粒度建图把它也当成顺序依赖，与「产品」对「物料」的真实依赖（cross_tab_ref /
 * component_subtotal 到 FORMULA 列）拼出一个假环 `产品⇄物料`；topoOrderComponents 抛错后
 * 静默回退声明序，导致「物料」在它真正依赖的「材料成本/来料固定加工费/来料其他费用」三个源页签
 * 之前被处理 —— 此时这三个源页签还没写入 crossTabRows store，「物料」11 个 FORMULA 列的
 * cross_tab_ref 全部取不到源数据，列小计塌成 0。手搓玩具夹具只有几条公式，测不出「7 个页签
 * 相互引用、真假依赖混在一起」这种规模下的假环——必须用真实模板 + 真实 7 页签拓扑。
 *
 * 【裁剪口径】未裁剪：保留 quotation_view_structure（QUOTE_CARD）全部 8 个页签
 * （产品/物料/材料成本/组装加工费/来料固定加工费/来料其他费用/其他费用/报价），
 * 因为本缺陷的假环恰恰产生于这 8 个页签的完整交叉引用拓扑——裁掉任何一个页签都可能
 * 让假环消失，测不出问题。quote_card_values / row_data 同理全量保留（未删任何字段）。
 *
 * 🚨 请勿手工编辑本文件的数据体 —— 它是线上快照的逐字拷贝，改了就不再是对拍基准。
 * 如需重新导出，跑 test.md §5 的三条 SQL 覆盖 structure.raw.json / cardvalues.raw.json /
 * saved.raw.json，再用同目录 __gen__.py（如有）或手工套本文件头重新生成 .ts。
 */

/** `GET /api/cpq/quotations/{id}` → `data.quoteCardStructure`（结构快照 v2，冻结在报价单上）。 */
export const QT0146_QUOTE_CARD_STRUCTURE = {
  "tabs": [
    {
      "fields": [
        {
          "name": "销售料号",
          "label": "销售料号",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$cp_view._销售料号",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "客户料号名称",
          "label": "客户料号名称",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 1,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$cp_view._客户料号名称",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "客户产品编号",
          "label": "客户产品编号",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 2,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$cp_view._客户产品编号",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "报价货币",
          "label": "报价货币",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 3,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$cp_view._报价货币",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "汇率",
          "label": "汇率",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 4,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "1",
          "defaultSource": {
            "path": "$cp_view._汇率",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "管理费",
          "label": "管理费",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "formulaId": "27bbbf45-99f9-49cb-ae6c-a1c958f99c67",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": true,
          "formulaName": "管理费",
          "defaultValue": ""
        },
        {
          "name": "税率",
          "label": "税率",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": true,
          "defaultValue": "1.13"
        }
      ],
      "tabName": "产品",
      "formulas": [
        {
          "id": "27bbbf45-99f9-49cb-ae6c-a1c958f99c67",
          "name": "管理费",
          "expression": [
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [],
              "source": "5ac12540-aab2-42d3-953e-c0f1104b9b25",
              "target": "",
              "predicate": {
                "op": "=",
                "lhs": {
                  "kind": "sourceField",
                  "field": "类别"
                },
                "rhs": {
                  "kind": "literal",
                  "value": "管理费"
                }
              },
              "targetExpr": [
                {
                  "type": "field",
                  "value": "比例",
                  "source": "5ac12540-aab2-42d3-953e-c0f1104b9b25"
                }
              ],
              "sourceLabel": "其他费用"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [],
              "source": "7f7b57ac-b368-4250-969a-b5612b6f92fb",
              "target": "",
              "predicate": {
                "op": "=",
                "lhs": {
                  "kind": "sourceField",
                  "field": "产出类型"
                },
                "rhs": {
                  "kind": "literal",
                  "value": "非银点类"
                }
              },
              "targetExpr": [
                {
                  "type": "field",
                  "value": "材料成本",
                  "source": "7f7b57ac-b368-4250-969a-b5612b6f92fb"
                }
              ],
              "sourceLabel": "物料"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "component_subtotal",
              "label": "组装加工费",
              "value": "__amount_total__",
              "tab_name": "__amount_total__",
              "is_tab_total": true,
              "component_code": "COMP-0222"
            },
            {
              "type": "operator",
              "value": "-"
            },
            {
              "type": "component_subtotal",
              "label": "物料·回收成本",
              "value": "回收成本",
              "tab_name": "回收成本",
              "component_code": "COMP-0228"
            },
            {
              "type": "bracket_close"
            }
          ],
          "result_type": "NUMBER"
        }
      ],
      "sortOrder": 0,
      "componentId": "8e06c482-4ca7-47b1-85bb-694c077451c3",
      "rowKeyFields": [
        "销售料号"
      ],
      "componentCode": "COMP-0224",
      "componentType": "NORMAL",
      "dataDriverPath": "$cp_view",
      "formula_assignments": {}
    },
    {
      "fields": [
        {
          "name": "料件",
          "label": "料件",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$bom_view._料件",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "组成数量",
          "label": "组成数量",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 1,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$bom_view._组成数量",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "产出类型",
          "label": "产出类型",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 2,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$bom_view._产出类型",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "材料毛重",
          "label": "材料毛重",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 3,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$bom_view._材料毛重",
            "type": "BASIC_DATA"
          },
          "unitSourceField": "单位"
        },
        {
          "name": "材料净重",
          "label": "材料净重",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 4,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$bom_view._材料净重",
            "type": "BASIC_DATA"
          },
          "unitSourceField": "单位"
        },
        {
          "name": "单位",
          "label": "单位",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 5,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$bom_view._单位",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "材料占比",
          "label": "材料占比",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 6,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$bom_view._材料占比",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "损耗率",
          "label": "损耗率",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 7,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$bom_view._损耗率",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "来料回收费",
          "label": "来料回收费",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "formulaId": "a6e2b86e-b5e3-4649-a3c3-c04c7440654d",
          "sortOrder": 8,
          "isRequired": false,
          "isSubtotal": false,
          "formulaName": "来料回收费取值公式",
          "defaultValue": ""
        },
        {
          "name": "来料财务费",
          "label": "来料财务费",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "formulaId": "1b3687b6-91b2-4b8a-88b2-e32cc730d71f",
          "sortOrder": 9,
          "isRequired": false,
          "isSubtotal": false,
          "formulaName": "来料财务费取值公式",
          "defaultValue": ""
        },
        {
          "name": "材料成本",
          "label": "材料成本",
          "width": 0,
          "editable": false,
          "isAmount": true,
          "fieldType": "FORMULA",
          "sortOrder": 10,
          "isRequired": false,
          "isSubtotal": true,
          "defaultValue": "",
          "conditionalFormula": {
            "rules": [
              {
                "when": {
                  "kind": "group",
                  "logic": "and",
                  "children": [
                    {
                      "op": "eq",
                      "rhs": {
                        "type": "literal",
                        "value": "非银点类"
                      },
                      "kind": "leaf",
                      "left": "产出类型"
                    },
                    {
                      "op": "eq",
                      "rhs": {
                        "type": "literal",
                        "value": "0"
                      },
                      "kind": "leaf",
                      "left": "是否根"
                    }
                  ]
                },
                "formula": "非银点类材料成本公式",
                "formula_id": "cb3ea05c-74d4-4a69-870f-79cd5b74376a"
              },
              {
                "when": {
                  "kind": "group",
                  "logic": "and",
                  "children": [
                    {
                      "op": "eq",
                      "rhs": {
                        "type": "literal",
                        "value": "0"
                      },
                      "kind": "leaf",
                      "left": "是否叶子"
                    }
                  ]
                },
                "formula": "零件材料成本",
                "formula_id": "f-1785983131159-ps9rcjbk"
              }
            ],
            "default": "银点材料成本公式",
            "default_formula_id": "c9213b82-cd55-484d-87e1-f9d95c13718f"
          }
        },
        {
          "name": "材料损耗成本",
          "label": "材料损耗成本",
          "width": 0,
          "editable": false,
          "isAmount": true,
          "fieldType": "FORMULA",
          "formulaId": "a436b159-8cb6-4134-974f-05a190e05c94",
          "sortOrder": 11,
          "isRequired": false,
          "isSubtotal": true,
          "formulaName": "材料损耗成本",
          "defaultValue": ""
        },
        {
          "name": "来料损耗率",
          "label": "来料损耗率",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "formulaId": "1795a8e7-f44d-4bc2-9fb1-1cde8f6d3125",
          "sortOrder": 12,
          "isRequired": false,
          "isSubtotal": false,
          "formulaName": "来料损耗率取值公式",
          "defaultValue": ""
        },
        {
          "name": "来料加工费",
          "label": "来料加工费",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "formulaId": "b0e76441-4431-4436-844b-35c72b884867",
          "sortOrder": 13,
          "isRequired": false,
          "isSubtotal": false,
          "formulaName": "来料加工费取值公式",
          "defaultValue": ""
        },
        {
          "name": "回收价格",
          "label": "回收价格",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 14,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$bom_view._回收价格",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "回收成本",
          "label": "回收成本",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "formulaId": "4aee4475-668f-4eb5-94ff-6c887811a3d7",
          "sortOrder": 15,
          "isRequired": false,
          "isSubtotal": true,
          "formulaName": "回收成本",
          "defaultValue": ""
        },
        {
          "name": "公式10",
          "label": "公式10",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "formulaId": "bda241be-14b5-4138-915d-e1753ac832ed",
          "sortOrder": 16,
          "isRequired": false,
          "isSubtotal": false,
          "formulaName": "公式10",
          "defaultValue": ""
        },
        {
          "name": "原材料成本",
          "label": "原材料成本",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "formulaId": "a67fe74f-d320-4241-9cff-0084689b0765",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": true,
          "formulaName": "公式13",
          "defaultValue": ""
        },
        {
          "name": "材料价格",
          "label": "材料价格",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "formulaId": "ed98e428-d125-4a39-9091-9cd3cdc62b98",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": true,
          "formulaName": "公式14",
          "defaultValue": ""
        },
        {
          "name": "铆钉额外费用",
          "label": "铆钉额外费用",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "formulaId": "0d2bbf8d-17bb-4f44-966b-28150aab258f",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": true,
          "formulaName": "铆钉额外费用",
          "defaultValue": ""
        }
      ],
      "tabName": "物料",
      "formulas": [
        {
          "id": "a6e2b86e-b5e3-4649-a3c3-c04c7440654d",
          "name": "来料回收费取值公式",
          "expression": [
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [],
              "source": "00783228-b913-41a5-8e7e-d486acbffa78",
              "target": "",
              "predicate": {
                "bool": "AND",
                "children": [
                  {
                    "op": "=",
                    "lhs": {
                      "kind": "sourceField",
                      "field": "要素"
                    },
                    "rhs": {
                      "kind": "literal",
                      "value": "来料回收费"
                    }
                  },
                  {
                    "op": "=",
                    "lhs": {
                      "kind": "sourceField",
                      "field": "料件"
                    },
                    "rhs": {
                      "kind": "hostField",
                      "field": "料件"
                    }
                  }
                ]
              },
              "targetExpr": [
                {
                  "type": "field",
                  "value": "比例",
                  "source": "00783228-b913-41a5-8e7e-d486acbffa78"
                }
              ],
              "sourceLabel": "来料其他费用"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "1b3687b6-91b2-4b8a-88b2-e32cc730d71f",
          "name": "来料财务费取值公式",
          "expression": [
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [],
              "source": "00783228-b913-41a5-8e7e-d486acbffa78",
              "target": "",
              "predicate": {
                "bool": "AND",
                "children": [
                  {
                    "op": "=",
                    "lhs": {
                      "kind": "sourceField",
                      "field": "要素"
                    },
                    "rhs": {
                      "kind": "literal",
                      "value": "来料财务费"
                    }
                  },
                  {
                    "op": "=",
                    "lhs": {
                      "kind": "sourceField",
                      "field": "料件"
                    },
                    "rhs": {
                      "kind": "hostField",
                      "field": "料件"
                    }
                  }
                ]
              },
              "targetExpr": [
                {
                  "type": "field",
                  "value": "比例",
                  "source": "00783228-b913-41a5-8e7e-d486acbffa78"
                }
              ],
              "sourceLabel": "来料其他费用"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "c9213b82-cd55-484d-87e1-f9d95c13718f",
          "name": "银点材料成本公式",
          "expression": [
            {
              "type": "bracket_open"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "field",
              "value": "材料占比"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "1054217f-059b-43d3-9c6b-8e41062ebf07",
              "target": "",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "组成含量(%)",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "field",
                  "value": "税后单价",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "bracket_open"
                },
                {
                  "type": "number",
                  "value": "1"
                },
                {
                  "type": "operator",
                  "value": "+"
                },
                {
                  "type": "b_field",
                  "value": "来料损耗率"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "bracket_close"
                },
                {
                  "type": "operator",
                  "value": "+"
                },
                {
                  "type": "b_field",
                  "value": "来料加工费"
                }
              ],
              "sourceLabel": "材料成本"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "component_subtotal",
              "label": "产品·税率",
              "value": "税率",
              "tab_name": "COMP-0051",
              "component_code": "COMP-0224"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "材料净重"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "组成数量"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "cb3ea05c-74d4-4a69-870f-79cd5b74376a",
          "name": "非银点类材料成本公式",
          "expression": [
            {
              "type": "field",
              "value": "材料毛重"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "bracket_open"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "1054217f-059b-43d3-9c6b-8e41062ebf07",
              "target": "",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "组成含量(%)",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "field",
                  "value": "税后单价",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                }
              ],
              "sourceLabel": "材料成本"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "number",
              "value": "1"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "来料损耗率"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "agg": "NONE",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "d6b5add7-b8f4-4ff5-bc95-6a112a206682",
              "target": "加工费",
              "sourceLabel": "来料固定加工费"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "component_subtotal",
              "label": "产品·税率",
              "value": "税率",
              "tab_name": "COMP-0051",
              "component_code": "COMP-0224"
            },
            {
              "type": "bracket_close"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "a436b159-8cb6-4134-974f-05a190e05c94",
          "name": "材料损耗成本",
          "expression": [
            {
              "type": "field",
              "value": "材料净重"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "1054217f-059b-43d3-9c6b-8e41062ebf07",
              "target": "",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "组成含量(%)",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "field",
                  "value": "税后单价",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                }
              ],
              "sourceLabel": "材料成本"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "损耗率"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "组成数量"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "5402f829-ae3f-42e5-949e-9379ee06ab03",
          "name": "默认公式0",
          "expression": [
            {
              "type": "number",
              "value": "0"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "1795a8e7-f44d-4bc2-9fb1-1cde8f6d3125",
          "name": "来料损耗率取值公式",
          "expression": [
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [],
              "source": "00783228-b913-41a5-8e7e-d486acbffa78",
              "target": "",
              "predicate": {
                "bool": "AND",
                "children": [
                  {
                    "op": "=",
                    "lhs": {
                      "kind": "sourceField",
                      "field": "要素"
                    },
                    "rhs": {
                      "kind": "literal",
                      "value": "来料损耗率"
                    }
                  },
                  {
                    "op": "=",
                    "lhs": {
                      "kind": "sourceField",
                      "field": "料件"
                    },
                    "rhs": {
                      "kind": "hostField",
                      "field": "料件"
                    }
                  }
                ]
              },
              "targetExpr": [
                {
                  "type": "field",
                  "value": "比例",
                  "source": "00783228-b913-41a5-8e7e-d486acbffa78"
                }
              ],
              "sourceLabel": "来料其他费用"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "b0e76441-4431-4436-844b-35c72b884867",
          "name": "来料加工费取值公式",
          "expression": [
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [],
              "source": "00783228-b913-41a5-8e7e-d486acbffa78",
              "target": "",
              "predicate": {
                "bool": "AND",
                "children": [
                  {
                    "op": "=",
                    "lhs": {
                      "kind": "sourceField",
                      "field": "要素"
                    },
                    "rhs": {
                      "kind": "literal",
                      "value": "来料加工费"
                    }
                  },
                  {
                    "op": "=",
                    "lhs": {
                      "kind": "sourceField",
                      "field": "料件"
                    },
                    "rhs": {
                      "kind": "hostField",
                      "field": "料件"
                    }
                  }
                ]
              },
              "targetExpr": [
                {
                  "type": "field",
                  "value": "费用",
                  "source": "00783228-b913-41a5-8e7e-d486acbffa78"
                }
              ],
              "sourceLabel": "来料其他费用"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "4aee4475-668f-4eb5-94ff-6c887811a3d7",
          "name": "回收成本",
          "expression": [
            {
              "type": "bracket_open"
            },
            {
              "type": "field",
              "value": "材料毛重"
            },
            {
              "type": "operator",
              "value": "-"
            },
            {
              "type": "field",
              "value": "材料净重"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "bracket_open"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "1054217f-059b-43d3-9c6b-8e41062ebf07",
              "target": "",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "组成含量(%)",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "field",
                  "value": "税后单价",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                }
              ],
              "sourceLabel": "材料成本"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "number",
              "value": "1"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "来料损耗率"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "agg": "NONE",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "d6b5add7-b8f4-4ff5-bc95-6a112a206682",
              "target": "加工费",
              "sourceLabel": "来料固定加工费"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "component_subtotal",
              "label": "产品·税率",
              "value": "税率",
              "tab_name": "COMP-0051",
              "component_code": "COMP-0224"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "回收价格"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "bda241be-14b5-4138-915d-e1753ac832ed",
          "name": "公式10",
          "expression": [
            {
              "type": "bracket_open"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "1054217f-059b-43d3-9c6b-8e41062ebf07",
              "target": "",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "组成含量(%)",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "field",
                  "value": "元素单价",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                }
              ],
              "sourceLabel": "材料成本"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "number",
              "value": "1"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "来料损耗率"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "来料加工费"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "1.13"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "8be08ad7-9804-410e-9fd3-26f806ae420c",
          "name": "公式11",
          "expression": [
            {
              "type": "bracket_open"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "1054217f-059b-43d3-9c6b-8e41062ebf07",
              "target": "",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "组成含量(%)",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "field",
                  "value": "元素单价",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                }
              ],
              "sourceLabel": "材料成本"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "number",
              "value": "1"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "来料损耗率"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "1.13"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "b625d188-3d4a-4c0f-8531-80f3f0c656cb",
          "name": "公式12",
          "expression": [
            {
              "agg": "NONE",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "d6b5add7-b8f4-4ff5-bc95-6a112a206682",
              "target": "加工费",
              "sourceLabel": "来料固定加工费"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "1.13"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "a67fe74f-d320-4241-9cff-0084689b0765",
          "name": "公式13",
          "expression": [
            {
              "type": "bracket_open"
            },
            {
              "type": "field",
              "value": "材料占比"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "损耗率"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "1054217f-059b-43d3-9c6b-8e41062ebf07",
              "target": "",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "组成含量(%)",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "field",
                  "value": "元素单价",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "bracket_open"
                },
                {
                  "type": "number",
                  "value": "1"
                },
                {
                  "type": "operator",
                  "value": "+"
                },
                {
                  "type": "b_field",
                  "value": "来料损耗率"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "bracket_close"
                }
              ],
              "sourceLabel": "材料成本"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "来料加工费"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "1.13"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "ed98e428-d125-4a39-9091-9cd3cdc62b98",
          "name": "公式14",
          "expression": [
            {
              "type": "bracket_open"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "field",
              "value": "材料占比"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "损耗率"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "1054217f-059b-43d3-9c6b-8e41062ebf07",
              "target": "",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "组成含量(%)",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "field",
                  "value": "元素单价",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "bracket_open"
                },
                {
                  "type": "number",
                  "value": "1"
                },
                {
                  "type": "operator",
                  "value": "+"
                },
                {
                  "type": "b_field",
                  "value": "来料损耗率"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "bracket_close"
                }
              ],
              "sourceLabel": "材料成本"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "来料加工费"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "1.13"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "材料净重"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "组成数量"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "0d2bbf8d-17bb-4f44-966b-28150aab258f",
          "name": "铆钉额外费用",
          "expression": [
            {
              "type": "bracket_open"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "field",
              "value": "材料占比"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "损耗率"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "1054217f-059b-43d3-9c6b-8e41062ebf07",
              "target": "",
              "targetExpr": [
                {
                  "type": "bracket_open"
                },
                {
                  "type": "field",
                  "value": "组成含量(%)",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "field",
                  "value": "元素单价",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "bracket_open"
                },
                {
                  "type": "number",
                  "value": "1"
                },
                {
                  "type": "operator",
                  "value": "+"
                },
                {
                  "type": "b_field",
                  "value": "来料损耗率"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "bracket_close"
                },
                {
                  "type": "operator",
                  "value": "+"
                },
                {
                  "type": "b_field",
                  "value": "来料加工费"
                },
                {
                  "type": "bracket_close"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "1.13"
                }
              ],
              "sourceLabel": "材料成本"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "field",
              "value": "来料财务费"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "来料回收费"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "agg": "NONE",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "d6b5add7-b8f4-4ff5-bc95-6a112a206682",
              "target": "加工费",
              "sourceLabel": "来料固定加工费"
            },
            {
              "type": "bracket_close"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "ab5ae48d-35b6-485b-8aa0-1d5b64a3c4c4",
          "name": "银点1.0",
          "expression": [
            {
              "type": "bracket_open"
            },
            {
              "type": "field",
              "value": "材料占比"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "agg": "SUM",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "1054217f-059b-43d3-9c6b-8e41062ebf07",
              "target": "",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "组成含量(%)",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "field",
                  "value": "元素单价",
                  "source": "1054217f-059b-43d3-9c6b-8e41062ebf07"
                },
                {
                  "type": "operator",
                  "value": "*"
                },
                {
                  "type": "bracket_open"
                },
                {
                  "type": "number",
                  "value": "1"
                },
                {
                  "type": "operator",
                  "value": "+"
                },
                {
                  "type": "b_field",
                  "value": "来料损耗率"
                },
                {
                  "type": "operator",
                  "value": "/"
                },
                {
                  "type": "number",
                  "value": "100"
                },
                {
                  "type": "bracket_close"
                },
                {
                  "type": "operator",
                  "value": "+"
                },
                {
                  "type": "b_field",
                  "value": "来料加工费"
                }
              ],
              "sourceLabel": "材料成本"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "1.13"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "材料净重"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "组成数量"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "f-1785983131159-ps9rcjbk",
          "name": "零件材料成本",
          "expression": [
            {
              "type": "bracket_open"
            },
            {
              "type": "bracket_open"
            },
            {
              "agg": "SUM",
              "dir": "CHILD",
              "type": "tree_ref",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "材料成本"
                }
              ]
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "agg": "SUM",
              "dir": "CHILD",
              "type": "tree_ref",
              "targetExpr": [
                {
                  "type": "field",
                  "value": "材料损耗成本"
                }
              ]
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "bracket_open"
            },
            {
              "type": "field",
              "value": "来料回收费"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "field",
              "value": "来料财务费"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "number",
              "value": "100"
            },
            {
              "type": "bracket_close"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "agg": "NONE",
              "type": "cross_tab_ref",
              "match": [
                {
                  "a": "料件",
                  "b": "料件"
                }
              ],
              "source": "d6b5add7-b8f4-4ff5-bc95-6a112a206682",
              "target": "加工费",
              "sourceLabel": "来料固定加工费"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "材料净重"
            },
            {
              "type": "operator",
              "value": "*"
            },
            {
              "type": "field",
              "value": "组成数量"
            }
          ],
          "result_type": "NUMBER"
        }
      ],
      "sortOrder": 1,
      "componentId": "7f7b57ac-b368-4250-969a-b5612b6f92fb",
      "rowKeyFields": [
        "料件"
      ],
      "componentCode": "COMP-0228",
      "componentType": "NORMAL",
      "dataDriverPath": "$bom_view",
      "formula_assignments": {}
    },
    {
      "fields": [
        {
          "name": "销售料号",
          "label": "销售料号",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view._销售料号",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "料号",
          "label": "料号",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 1,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view._料号",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "料件",
          "label": "料件",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 2,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view._料件",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "项次",
          "label": "项次",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 4,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view._项次",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "元素",
          "label": "元素",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 5,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view._元素",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "组成含量(%)",
          "label": "组成含量(%)",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 6,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view._组成含量",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "损耗率%",
          "label": "损耗率%",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 7,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view._损耗率",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "毛重",
          "label": "毛重",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 8,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view._毛重",
            "type": "BASIC_DATA"
          },
          "unitSourceField": "毛用量单位"
        },
        {
          "name": "毛用量单位",
          "label": "毛用量单位",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 9,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view._毛用量单位",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "元素单价",
          "label": "元素单价",
          "width": 0,
          "editable": true,
          "isAmount": true,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 10,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view.元素单价",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "货币",
          "label": "货币",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 11,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$mc_view.货币",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "税后单价",
          "label": "税后单价",
          "width": 0,
          "editable": false,
          "isAmount": false,
          "fieldType": "FORMULA",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "conditionalFormula": {
            "rules": [
              {
                "when": {
                  "kind": "group",
                  "logic": "and",
                  "children": [
                    {
                      "op": "eq",
                      "rhs": {
                        "type": "literal",
                        "value": "Ni"
                      },
                      "kind": "leaf",
                      "left": "元素"
                    }
                  ]
                },
                "formula": "不扣税单价公式",
                "formula_id": "f-1786157401729-ahdxljh4"
              }
            ],
            "default": "税后单价公式",
            "default_formula_id": "f-1786157355657-c8st5myv"
          }
        }
      ],
      "tabName": "材料成本",
      "formulas": [
        {
          "id": "f-1786157355657-c8st5myv",
          "name": "税后单价公式",
          "expression": [
            {
              "type": "field",
              "value": "元素单价"
            },
            {
              "type": "operator",
              "value": "/"
            },
            {
              "type": "component_subtotal",
              "label": "产品·税率",
              "value": "税率",
              "tab_name": "COMP-0051",
              "component_code": "COMP-0224"
            }
          ],
          "result_type": "NUMBER"
        },
        {
          "id": "f-1786157401729-ahdxljh4",
          "name": "不扣税单价公式",
          "expression": [
            {
              "type": "field",
              "value": "元素单价"
            }
          ],
          "result_type": "NUMBER"
        }
      ],
      "sortOrder": 2,
      "componentId": "1054217f-059b-43d3-9c6b-8e41062ebf07",
      "rowKeyFields": [
        "销售料号",
        "元素",
        "料件"
      ],
      "componentCode": "COMP-0227",
      "componentType": "NORMAL",
      "dataDriverPath": "$mc_view",
      "elementCodeField": "元素",
      "elementPriceField": "元素单价",
      "formula_assignments": {}
    },
    {
      "fields": [
        {
          "name": "销售料号",
          "label": "销售料号",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$zz_view._销售料号",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "料件",
          "label": "料件",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 1,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$zz_view._料件",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "项次",
          "label": "项次",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 2,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$zz_view._项次",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "工序",
          "label": "工序",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 3,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$zz_view._工序",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "加工费",
          "label": "加工费",
          "width": 0,
          "editable": true,
          "isAmount": true,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 4,
          "isRequired": false,
          "isSubtotal": true,
          "defaultValue": "",
          "defaultSource": {
            "path": "$zz_view._加工费",
            "type": "BASIC_DATA"
          },
          "unitSourceField": "单位"
        },
        {
          "name": "单位",
          "label": "单位",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 5,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$zz_view._单位",
            "type": "BASIC_DATA"
          }
        }
      ],
      "tabName": "组装加工费",
      "formulas": [],
      "sortOrder": 3,
      "componentId": "0f4ac193-822c-4f53-81ca-1567c8d05923",
      "rowKeyFields": [
        "项次",
        "工序"
      ],
      "componentCode": "COMP-0222",
      "componentType": "NORMAL",
      "dataDriverPath": "$zz_view",
      "formula_assignments": {}
    },
    {
      "fields": [
        {
          "name": "销售料号",
          "label": "销售料号",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$ll_view._销售料号",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "料件",
          "label": "料件",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 1,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$ll_view._料件",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "项次",
          "label": "项次",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 2,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$ll_view._项次",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "加工费",
          "label": "加工费",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 4,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$ll_view._加工费",
            "type": "BASIC_DATA"
          },
          "unitSourceField": "单位"
        },
        {
          "name": "单位",
          "label": "单位",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 5,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$ll_view._单位",
            "type": "BASIC_DATA"
          }
        }
      ],
      "tabName": "来料固定加工费",
      "formulas": [],
      "sortOrder": 4,
      "componentId": "d6b5add7-b8f4-4ff5-bc95-6a112a206682",
      "rowKeyFields": [
        "销售料号",
        "料件"
      ],
      "componentCode": "COMP-0220",
      "componentType": "NORMAL",
      "dataDriverPath": "$ll_view",
      "formula_assignments": {}
    },
    {
      "fields": [
        {
          "name": "销售料号",
          "label": "销售料号",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$lqt_view._销售料号",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "料号",
          "label": "料号",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 1,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$lqt_view._料号",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "料件",
          "label": "料件",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 2,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$lqt_view._料件",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "项次",
          "label": "项次",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 3,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$lqt_view._项次",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "要素",
          "label": "要素",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 4,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$lqt_view._要素",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "费用",
          "label": "费用",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 5,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$lqt_view._费用",
            "type": "BASIC_DATA"
          },
          "unitSourceField": "计价单位"
        },
        {
          "name": "比例",
          "label": "比例",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 6,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$lqt_view._比例",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "计价单位",
          "label": "计价单位",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 7,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$lqt_view._计价单位",
            "type": "BASIC_DATA"
          }
        }
      ],
      "tabName": "来料其他费用",
      "formulas": [],
      "sortOrder": 5,
      "componentId": "00783228-b913-41a5-8e7e-d486acbffa78",
      "rowKeyFields": [
        "销售料号",
        "料件",
        "要素"
      ],
      "componentCode": "COMP-0215",
      "componentType": "NORMAL",
      "dataDriverPath": "$lqt_view",
      "formula_assignments": {}
    },
    {
      "fields": [
        {
          "name": "销售料号",
          "label": "销售料号",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$qt_view._销售料号",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "项次",
          "label": "项次",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 1,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$qt_view._项次",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "类别",
          "label": "类别",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 2,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$qt_view._类别",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "费用",
          "label": "费用",
          "width": 0,
          "editable": true,
          "isAmount": true,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 3,
          "isRequired": false,
          "isSubtotal": true,
          "defaultValue": "",
          "defaultSource": {
            "path": "$qt_view._费用",
            "type": "BASIC_DATA"
          },
          "unitSourceField": "单位"
        },
        {
          "name": "单位",
          "label": "单位",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_TEXT",
          "sortOrder": 4,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": "",
          "defaultSource": {
            "path": "$qt_view._单位",
            "type": "BASIC_DATA"
          }
        },
        {
          "name": "比例",
          "label": "比例",
          "width": 0,
          "editable": true,
          "isAmount": false,
          "fieldType": "INPUT_NUMBER",
          "sortOrder": 0,
          "isRequired": false,
          "isSubtotal": false,
          "defaultValue": ""
        }
      ],
      "tabName": "其他费用",
      "formulas": [],
      "sortOrder": 6,
      "componentId": "5ac12540-aab2-42d3-953e-c0f1104b9b25",
      "rowKeyFields": [
        "项次",
        "类别"
      ],
      "componentCode": "COMP-0221",
      "componentType": "NORMAL",
      "dataDriverPath": "$qt_view",
      "formula_assignments": {}
    },
    {
      "fields": [],
      "tabName": "报价",
      "formulas": [
        {
          "id": "46b99bc5-11a0-4df8-8010-df077007b604",
          "name": "公式1",
          "expression": [
            {
              "type": "component_subtotal",
              "label": "物料·材料成本",
              "value": "材料成本",
              "tab_name": "材料成本",
              "component_code": "COMP-0228"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "component_subtotal",
              "label": "物料·材料损耗成本",
              "value": "材料损耗成本",
              "tab_name": "材料损耗成本",
              "component_code": "COMP-0228"
            },
            {
              "type": "operator",
              "value": "-"
            },
            {
              "type": "component_subtotal",
              "label": "物料·回收成本",
              "value": "回收成本",
              "tab_name": "回收成本",
              "component_code": "COMP-0228"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "component_subtotal",
              "label": "其他费用",
              "value": "__amount_total__",
              "tab_name": "__amount_total__",
              "is_tab_total": true,
              "component_code": "COMP-0221"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "component_subtotal",
              "label": "产品·管理费",
              "value": "管理费",
              "tab_name": "管理费",
              "component_code": "COMP-0224"
            },
            {
              "type": "operator",
              "value": "+"
            },
            {
              "type": "component_subtotal",
              "label": "组装加工费",
              "value": "__amount_total__",
              "tab_name": "__amount_total__",
              "is_tab_total": true,
              "component_code": "COMP-0222"
            }
          ],
          "result_type": "NUMBER"
        }
      ],
      "sortOrder": 7,
      "componentId": "fedc4207-ca54-4989-9f5e-c7fe494f3059",
      "rowKeyFields": [],
      "componentCode": "COMP-0225",
      "componentType": "SUBTOTAL",
      "dataDriverPath": "",
      "formula_assignments": {}
    }
  ],
  "version": 2,
  "templateId": "7fd1ecd8-ec52-4bc5-9104-98189d1e0761",
  "templateKind": "QUOTATION",
  "productAttributes": []
};
