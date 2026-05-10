-- V97: 「核价Excel视图模板（完整公式版）」中间值列改可见 + 标 Excel 行号
--
-- 用户诉求: V96 的公式 =[B_PURE]+[B_PROC]+[B_OTHER]-[B_RECYCLE] 概念上等价 Excel 行 82,
--           但中间值列 hidden, 用户看不到 2449.572 + 5.5 + 213.11 - 0 = 2668.18 的拆解链路,
--           缺乏可读性 / 验证性 / 教育性。
--
-- 方案 2: 把所有中间值列改成可见, 用户在核价单 Excel 视图能看到每个公式的输入数据,
--         数值能逐项溯源到 v4 Excel 公式行号。
--
-- 同时简化:
--   - C/D/F 的恒等公式 (=[C_LOSS], =[D_PROC], =[F_OUT]) 改回 VARIABLE 直读视图字段, 删 hidden 中间列
--   - 列标题加 Excel 行号引用 (例: "纯材料成本 (行78)"), 用户一看就知道对应 Excel 哪条公式
--
-- 列布局 (30 列, 全部可见, 按 v4 Excel 公式逻辑分组):
--   A 标识        : A
--   材料成本组    : B_PURE/B_PROC/B_OTHER/B_RECYCLE → B (FORMULA 行 82)
--   单列         : C 材料损耗 (行 83) / D 加工费 (行 86)
--   电镀组       : E_PROC/E_MAT/E_DEFECT → E (FORMULA 行 90)
--   单列         : F 其他外加工
--   加价组       : G 加价基数 (FORMULA, 可见) / H/I 管理 / J/K 财务 / L/M 利润 / N/O 税费 (行 92-95)
--   总成本组      : P (CNY/KG, 行 97) / Q 单重 / R (CNY/PCS, 行 98) / S 汇率 / T (USD/KG, 行 99) / U (USD/PCS, 行 100)
--   信息         : V 报价币种 / W 计量单位

UPDATE costing_template
SET columns = $JSON$[
        {"col_key":"A","title":"宏丰料号","source_type":"VARIABLE","variable_path":"{hf_part_no}"},

        {"col_key":"B_PURE","title":"纯材料成本 (行78)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.pure_material_cost"},
        {"col_key":"B_PROC","title":"来料加工费 (行79)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.incoming_process_fee"},
        {"col_key":"B_OTHER","title":"来料其他费用 (行80)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.incoming_other_fee"},
        {"col_key":"B_RECYCLE","title":"回收成本 (行81)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.recycle_cost"},
        {"col_key":"B","title":"材料成本 (行82)","source_type":"FORMULA","formula":"=[B_PURE]+[B_PROC]+[B_OTHER]-[B_RECYCLE]","comparison_tag":"MATERIAL_COST"},

        {"col_key":"C","title":"材料损耗成本 (行83)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_loss_cost","comparison_tag":"MATERIAL_LOSS"},

        {"col_key":"D","title":"加工费 (行86)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.process_fee_total","comparison_tag":"PROCESS_FEE"},

        {"col_key":"E_PROC","title":"电镀加工费","source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_process_fee"},
        {"col_key":"E_MAT","title":"电镀材料费 (行89)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_material_fee"},
        {"col_key":"E_DEFECT","title":"电镀不良率","source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_defect_rate"},
        {"col_key":"E","title":"电镀成本 (行90)","source_type":"FORMULA","formula":"=([E_PROC]+[E_MAT])*(1+[E_DEFECT])","comparison_tag":"PLATING_COST"},

        {"col_key":"F","title":"其他外加工成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.outsource_fee_total","comparison_tag":"OUTSOURCE_COST"},

        {"col_key":"G","title":"加价基数 (=B+C+D+E+F)","source_type":"FORMULA","formula":"=[B]+[C]+[D]+[E]+[F]"},

        {"col_key":"H","title":"管理费比例","source_type":"VARIABLE","variable_path":"v_costing_summary_full.mgmt_fee_ratio"},
        {"col_key":"I","title":"管理费 (行92)","source_type":"FORMULA","formula":"=[G]*[H]","comparison_tag":"MGMT_FEE"},

        {"col_key":"J","title":"财务费比例","source_type":"VARIABLE","variable_path":"v_costing_summary_full.finance_fee_ratio"},
        {"col_key":"K","title":"财务费 (行93)","source_type":"FORMULA","formula":"=[G]*[J]","comparison_tag":"FINANCE_FEE"},

        {"col_key":"L","title":"利润比例","source_type":"VARIABLE","variable_path":"v_costing_summary_full.profit_ratio"},
        {"col_key":"M","title":"利润 (行94)","source_type":"FORMULA","formula":"=[G]*[L]","comparison_tag":"PROFIT"},

        {"col_key":"N","title":"税费比例","source_type":"VARIABLE","variable_path":"v_costing_summary_full.tax_ratio"},
        {"col_key":"O","title":"税费 (行95)","source_type":"FORMULA","formula":"=[G]*[N]","comparison_tag":"TAX"},

        {"col_key":"P","title":"总成本(CNY/KG) (行97)","source_type":"FORMULA","formula":"=[G]+[I]+[K]+[M]+[O]","comparison_tag":"TOTAL_CNY_KG"},

        {"col_key":"Q","title":"单重(g/pcs)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.unit_weight_g"},
        {"col_key":"R","title":"总成本(CNY/PCS) (行98)","source_type":"FORMULA","formula":"=[P]/1000/[Q]","comparison_tag":"TOTAL_CNY_PCS"},

        {"col_key":"S","title":"核价汇率(CNY→USD)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.exchange_rate_to_usd"},
        {"col_key":"T","title":"总成本(USD/KG) (行99)","source_type":"FORMULA","formula":"=[P]*[S]","comparison_tag":"TOTAL_USD_KG"},
        {"col_key":"U","title":"总成本(USD/PCS) (行100)","source_type":"FORMULA","formula":"=[T]/1000/[Q]","comparison_tag":"TOTAL_USD_PCS"},

        {"col_key":"V","title":"报价币种","source_type":"VARIABLE","variable_path":"v_costing_summary_full.quote_currency"},
        {"col_key":"W","title":"计量单位","source_type":"VARIABLE","variable_path":"v_costing_summary_full.weight_unit"}
    ]$JSON$::jsonb,
    description = 'V97: 全公式 + 中间值可见版核价 Excel 视图模板。30 列全部对外可见, ' ||
                  '每个 FORMULA 列的输入(纯材料/来料加工/来料其他/回收 / 电镀加工/电镀材料/电镀不良率 / 4 加价比例 / 单重 / 汇率)' ||
                  '都作为前置 VARIABLE 列暴露, 用户能逐项验证: 例 材料成本 = 纯材料 + 来料加工 + 来料其他 - 回收。' ||
                  '列标题带 Excel 行号 (行78~100) 直接对应 data/template/核价系统计算公式和取值（示例）.xlsx 中的公式定义。',
    updated_at = now()
WHERE name = '核价Excel视图模板（完整公式版）';
