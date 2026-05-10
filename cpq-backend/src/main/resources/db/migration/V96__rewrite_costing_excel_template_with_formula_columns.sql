-- V96: 重写「核价Excel视图模板（完整公式版）」, 把 9 个成本列从 VARIABLE 改成真 FORMULA
--
-- 配合 V95 视图扩展 (新增 pure_material/recycle/material_loss/process_fee_total/
-- plating_*/unit_weight_g/exchange_rate_to_usd/mgmt|finance|profit|tax_ratio 等中间值字段),
-- 模板直接对应 v4 Excel 行 78-100 公式语义:
--
--   行 82 材料成本 = 纯材料 + 来料加工 + 来料其他 - 回收
--   行 83 材料损耗成本 = (视图直接给)
--   行 86 加工费 = (视图直接给, 已含 1+ 不良率)
--   行 90 电镀成本 = (电镀加工 + 电镀材料) × (1 + 不良率)
--   行 92-95 管理/财务/利润/税费 = 加价基数 × 比例 (已有逻辑)
--   行 97-100 总成本 = 各项相加 / 单位换算 / 汇率换算
--
-- 关键: 所有 9 个成本列 source_type=FORMULA, 隐藏列暴露视图的中间值供 [col_key] 引用
--
-- 列布局:
--   对外可见 16 列 (与 v4 「汇总」对齐):
--     A 宏丰料号 / B 材料成本(F) / C 材料损耗成本(F) / D 加工费(F) / E 电镀成本(F) /
--     F 其他外加工成本(F) / I 管理费(F) / K 财务费(F) / M 利润(F) / O 税费(F) /
--     P 总成本(CNY/KG)(F) / R 总成本(CNY/PCS)(F) / T 总成本(USD/KG)(F) / U 总成本(USD/PCS)(F) /
--     V 报价币种(V) / W 计量单位(V)
--   隐藏列 13 个 (中间值, 仅供 FORMULA 引用):
--     B_PURE / B_PROC / B_OTHER / B_RECYCLE              (材料成本拆解)
--     E_PROC / E_MAT / E_DEFECT                          (电镀拆解)
--     F_OUT                                              (外加工)
--     G                                                   (加价基数中间)
--     H/J/L/N                                            (4 个比例)
--     Q                                                   (单重)
--     S                                                   (汇率)
--   总计 29 列 (16 可见 + 13 隐藏)

UPDATE costing_template
SET columns = $JSON$[
        {"col_key":"A","title":"宏丰料号","source_type":"VARIABLE","variable_path":"{hf_part_no}"},

        {"col_key":"B_PURE","title":"纯材料成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.pure_material_cost","hidden":true},
        {"col_key":"B_PROC","title":"来料加工费","source_type":"VARIABLE","variable_path":"v_costing_summary_full.incoming_process_fee","hidden":true},
        {"col_key":"B_OTHER","title":"来料其他费用","source_type":"VARIABLE","variable_path":"v_costing_summary_full.incoming_other_fee","hidden":true},
        {"col_key":"B_RECYCLE","title":"回收成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.recycle_cost","hidden":true},
        {"col_key":"B","title":"材料成本","source_type":"FORMULA","formula":"=[B_PURE]+[B_PROC]+[B_OTHER]-[B_RECYCLE]","comparison_tag":"MATERIAL_COST"},

        {"col_key":"C_LOSS","title":"材料损耗源","source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_loss_cost","hidden":true},
        {"col_key":"C","title":"材料损耗成本","source_type":"FORMULA","formula":"=[C_LOSS]","comparison_tag":"MATERIAL_LOSS"},

        {"col_key":"D_PROC","title":"加工费源","source_type":"VARIABLE","variable_path":"v_costing_summary_full.process_fee_total","hidden":true},
        {"col_key":"D","title":"加工费","source_type":"FORMULA","formula":"=[D_PROC]","comparison_tag":"PROCESS_FEE"},

        {"col_key":"E_PROC","title":"电镀加工费","source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_process_fee","hidden":true},
        {"col_key":"E_MAT","title":"电镀材料费","source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_material_fee","hidden":true},
        {"col_key":"E_DEFECT","title":"电镀不良率","source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_defect_rate","hidden":true},
        {"col_key":"E","title":"电镀成本","source_type":"FORMULA","formula":"=([E_PROC]+[E_MAT])*(1+[E_DEFECT])","comparison_tag":"PLATING_COST"},

        {"col_key":"F_OUT","title":"外加工源","source_type":"VARIABLE","variable_path":"v_costing_summary_full.outsource_fee_total","hidden":true},
        {"col_key":"F","title":"其他外加工成本","source_type":"FORMULA","formula":"=[F_OUT]","comparison_tag":"OUTSOURCE_COST"},

        {"col_key":"G","title":"加价基数","source_type":"FORMULA","formula":"=[B]+[C]+[D]+[E]+[F]","hidden":true},

        {"col_key":"H","title":"管理费比例","source_type":"VARIABLE","variable_path":"v_costing_summary_full.mgmt_fee_ratio","hidden":true},
        {"col_key":"I","title":"管理费","source_type":"FORMULA","formula":"=[G]*[H]","comparison_tag":"MGMT_FEE"},

        {"col_key":"J","title":"财务费比例","source_type":"VARIABLE","variable_path":"v_costing_summary_full.finance_fee_ratio","hidden":true},
        {"col_key":"K","title":"财务费","source_type":"FORMULA","formula":"=[G]*[J]","comparison_tag":"FINANCE_FEE"},

        {"col_key":"L","title":"利润比例","source_type":"VARIABLE","variable_path":"v_costing_summary_full.profit_ratio","hidden":true},
        {"col_key":"M","title":"利润","source_type":"FORMULA","formula":"=[G]*[L]","comparison_tag":"PROFIT"},

        {"col_key":"N","title":"税费比例","source_type":"VARIABLE","variable_path":"v_costing_summary_full.tax_ratio","hidden":true},
        {"col_key":"O","title":"税费","source_type":"FORMULA","formula":"=[G]*[N]","comparison_tag":"TAX"},

        {"col_key":"P","title":"总成本(CNY/KG)","source_type":"FORMULA","formula":"=[G]+[I]+[K]+[M]+[O]","comparison_tag":"TOTAL_CNY_KG"},

        {"col_key":"Q","title":"单重(g/pcs)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.unit_weight_g","hidden":true},
        {"col_key":"R","title":"总成本(CNY/PCS)","source_type":"FORMULA","formula":"=[P]/1000/[Q]","comparison_tag":"TOTAL_CNY_PCS"},

        {"col_key":"S","title":"核价汇率","source_type":"VARIABLE","variable_path":"v_costing_summary_full.exchange_rate_to_usd","hidden":true},
        {"col_key":"T","title":"总成本(USD/KG)","source_type":"FORMULA","formula":"=[P]*[S]","comparison_tag":"TOTAL_USD_KG"},
        {"col_key":"U","title":"总成本(USD/PCS)","source_type":"FORMULA","formula":"=[T]/1000/[Q]","comparison_tag":"TOTAL_USD_PCS"},

        {"col_key":"V","title":"报价币种","source_type":"VARIABLE","variable_path":"v_costing_summary_full.quote_currency"},
        {"col_key":"W","title":"计量单位","source_type":"VARIABLE","variable_path":"v_costing_summary_full.weight_unit"}
    ]$JSON$::jsonb,
    referenced_variables = $JSON$[
        "{hf_part_no}",
        "v_costing_summary_full.pure_material_cost",
        "v_costing_summary_full.incoming_process_fee",
        "v_costing_summary_full.incoming_other_fee",
        "v_costing_summary_full.recycle_cost",
        "v_costing_summary_full.material_loss_cost",
        "v_costing_summary_full.process_fee_total",
        "v_costing_summary_full.plating_process_fee",
        "v_costing_summary_full.plating_material_fee",
        "v_costing_summary_full.plating_defect_rate",
        "v_costing_summary_full.outsource_fee_total",
        "v_costing_summary_full.unit_weight_g",
        "v_costing_summary_full.exchange_rate_to_usd",
        "v_costing_summary_full.mgmt_fee_ratio",
        "v_costing_summary_full.finance_fee_ratio",
        "v_costing_summary_full.profit_ratio",
        "v_costing_summary_full.tax_ratio",
        "v_costing_summary_full.quote_currency",
        "v_costing_summary_full.weight_unit"
    ]$JSON$::jsonb,
    description = 'V96: 全公式版核价 Excel 视图模板。9 个成本列(B/C/D/E/F + I/K/M/O + P/R/T/U) 全部 FORMULA 类型,'
               || '公式直接对应 data/template/核价系统计算公式和取值（示例）.xlsx 行 78-100 语义。'
               || '中间值由 V95 扩展的 v_costing_summary_full 视图直接 SQL ∑ 聚合(纯材料/回收/损耗/电镀加工/电镀材料/'
               || '4 加价比例/单重/汇率), 模板 FORMULA 列只做 scalar 算术。'
               || '对外可见 16 列与 v4 Excel 「汇总」严格对齐, 13 个 hidden 中间列仅供 FORMULA 引用。',
    updated_at = now()
WHERE name = '核价Excel视图模板（完整公式版）';
