-- V85: 把「核价Excel视图模板（完整公式版）」的硬编码字面量改为 BNF 引用
--
-- 用户反馈: V83/V84 把 4 个加价比例(0.006/0.005/0.05/0.13)与核价汇率(0.138)写成了
-- 字面量, 当基础数据里这些比例发生变化时, 模板公式不会跟随变化, 是错误设计。
-- 应该走 BNF 路径引用, 与 Excel 模板「F74=*L49/100」式的"引用单元格"语义对齐。
--
-- 关键设计变化:
--   * 4 个加价比例新增 4 个 VARIABLE 列(H/J/L/N), BNF 路径指向 mat_fee 成品其他费用 sheet
--   * 核价汇率新增 1 个 VARIABLE 列(S), BNF 路径指向 v_costing_exchange_rate
--   * 4 个加价 FORMULA 列(I/K/M/O)改为引用对应的比例列: =[G]*[H] 等
--   * 总成本(USD/KG) 改为 =[P]*[S]
--   * 列数从 19 增到 23
--
-- 列结构(23 列):
--   A 宏丰料号           VARIABLE  {hf_part_no}                                              由 lineItem 注入
--   B 材料成本           VARIABLE  v_costing_summary_full.material_cost
--   C 材料损耗成本       VARIABLE  v_costing_summary_full.material_loss_cost
--   D 加工费             VARIABLE  v_costing_summary_full.processing_cost
--   E 电镀成本           VARIABLE  v_costing_summary_full.plating_cost
--   F 其他外加工成本     VARIABLE  v_costing_summary_full.other_outsource_cost
--   G 加价基数           FORMULA   =[B]+[C]+[D]+[E]+[F]
--   H 管理费比例         VARIABLE  mat_fee[fee_type='FINISHED_OTHER',dim_element_name='管理费'].fee_ratio
--   I 管理费             FORMULA   =[G]*[H]                                                  Excel 行 92
--   J 财务费比例         VARIABLE  mat_fee[fee_type='FINISHED_OTHER',dim_element_name='财务费'].fee_ratio
--   K 财务费             FORMULA   =[G]*[J]                                                  Excel 行 93
--   L 利润比例           VARIABLE  mat_fee[fee_type='FINISHED_OTHER',dim_element_name='利润'].fee_ratio
--   M 利润               FORMULA   =[G]*[L]                                                  Excel 行 94
--   N 税费比例           VARIABLE  mat_fee[fee_type='FINISHED_OTHER',dim_element_name='税费'].fee_ratio
--   O 税费               FORMULA   =[G]*[N]                                                  Excel 行 95
--   P 总成本(CNY/KG)     FORMULA   =[G]+[I]+[K]+[M]+[O]                                      Excel 行 97
--   Q 单重(g/pcs)        VARIABLE  mat_part.unit_weight
--   R 总成本(CNY/PCS)    FORMULA   =[P]/1000/[Q]                                             Excel 行 98
--   S 核价汇率           VARIABLE  v_costing_exchange_rate[from_currency='CNY',to_currency='USD'].costing_rate
--   T 总成本(USD/KG)     FORMULA   =[P]*[S]                                                  Excel 行 99
--   U 总成本(USD/PCS)    FORMULA   =[T]/1000/[Q]                                             Excel 行 100
--   V 报价币种           VARIABLE  v_costing_summary_full.quote_currency
--   W 计量单位           VARIABLE  v_costing_summary_full.weight_unit
--
-- 数据预期:
--   * fee_ratio 在 mat_fee 表中以小数存储(0.05 = 5%), 公式里直接 [G]*[H] 不需要再除 100
--   * 当前 DB 种子数据中 fee_type='FINISHED_OTHER' 仅有 3 条且 dim_element_name 是
--     「财务管理费/回收费/材料管理费」, 与 Excel 设计的「管理费/财务费/利润/税费」不一致,
--     所以 H/J/L/N 这 4 列暂时会解析到 null, 加价 FORMULA 列(I/K/M/O)显示 0, 总成本只算基础部分。
--     待 admin 在基础数据中按 Excel 命名补全 4 行后, 整链路自动联动。
--   * 核价汇率 BNF 已能取到 0.138(DB 已有数据), USD 总成本列立即可用。
--
-- 同步更新:
--   * referenced_variables 增加 5 个新引用
--   * description 改为说明"BNF 引用"语义

UPDATE costing_template
SET columns = $JSON$[
        {"col_key":"A","title":"宏丰料号","source_type":"VARIABLE","variable_path":"{hf_part_no}"},
        {"col_key":"B","title":"材料成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_cost","comparison_tag":"MATERIAL_COST"},
        {"col_key":"C","title":"材料损耗成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_loss_cost","comparison_tag":"MATERIAL_LOSS"},
        {"col_key":"D","title":"加工费","source_type":"VARIABLE","variable_path":"v_costing_summary_full.processing_cost","comparison_tag":"PROCESS_FEE"},
        {"col_key":"E","title":"电镀成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_cost","comparison_tag":"PLATING_COST"},
        {"col_key":"F","title":"其他外加工成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.other_outsource_cost","comparison_tag":"OUTSOURCE_COST"},
        {"col_key":"G","title":"加价基数","source_type":"FORMULA","formula":"=[B]+[C]+[D]+[E]+[F]"},
        {"col_key":"H","title":"管理费比例","source_type":"VARIABLE","variable_path":"mat_fee[fee_type='FINISHED_OTHER',dim_element_name='管理费'].fee_ratio"},
        {"col_key":"I","title":"管理费","source_type":"FORMULA","formula":"=[G]*[H]","comparison_tag":"MGMT_FEE"},
        {"col_key":"J","title":"财务费比例","source_type":"VARIABLE","variable_path":"mat_fee[fee_type='FINISHED_OTHER',dim_element_name='财务费'].fee_ratio"},
        {"col_key":"K","title":"财务费","source_type":"FORMULA","formula":"=[G]*[J]","comparison_tag":"FINANCE_FEE"},
        {"col_key":"L","title":"利润比例","source_type":"VARIABLE","variable_path":"mat_fee[fee_type='FINISHED_OTHER',dim_element_name='利润'].fee_ratio"},
        {"col_key":"M","title":"利润","source_type":"FORMULA","formula":"=[G]*[L]","comparison_tag":"PROFIT"},
        {"col_key":"N","title":"税费比例","source_type":"VARIABLE","variable_path":"mat_fee[fee_type='FINISHED_OTHER',dim_element_name='税费'].fee_ratio"},
        {"col_key":"O","title":"税费","source_type":"FORMULA","formula":"=[G]*[N]","comparison_tag":"TAX"},
        {"col_key":"P","title":"总成本(CNY/KG)","source_type":"FORMULA","formula":"=[G]+[I]+[K]+[M]+[O]","comparison_tag":"TOTAL_CNY_KG"},
        {"col_key":"Q","title":"单重(g/pcs)","source_type":"VARIABLE","variable_path":"mat_part.unit_weight"},
        {"col_key":"R","title":"总成本(CNY/PCS)","source_type":"FORMULA","formula":"=[P]/1000/[Q]","comparison_tag":"TOTAL_CNY_PCS"},
        {"col_key":"S","title":"核价汇率","source_type":"VARIABLE","variable_path":"v_costing_exchange_rate[from_currency='CNY',to_currency='USD'].costing_rate"},
        {"col_key":"T","title":"总成本(USD/KG)","source_type":"FORMULA","formula":"=[P]*[S]","comparison_tag":"TOTAL_USD_KG"},
        {"col_key":"U","title":"总成本(USD/PCS)","source_type":"FORMULA","formula":"=[T]/1000/[Q]","comparison_tag":"TOTAL_USD_PCS"},
        {"col_key":"V","title":"报价币种","source_type":"VARIABLE","variable_path":"v_costing_summary_full.quote_currency"},
        {"col_key":"W","title":"计量单位","source_type":"VARIABLE","variable_path":"v_costing_summary_full.weight_unit"}
    ]$JSON$::jsonb,
    referenced_variables = $JSON$[
        "{hf_part_no}",
        "v_costing_summary_full.material_cost",
        "v_costing_summary_full.material_loss_cost",
        "v_costing_summary_full.processing_cost",
        "v_costing_summary_full.plating_cost",
        "v_costing_summary_full.other_outsource_cost",
        "mat_fee[fee_type='FINISHED_OTHER',dim_element_name='管理费'].fee_ratio",
        "mat_fee[fee_type='FINISHED_OTHER',dim_element_name='财务费'].fee_ratio",
        "mat_fee[fee_type='FINISHED_OTHER',dim_element_name='利润'].fee_ratio",
        "mat_fee[fee_type='FINISHED_OTHER',dim_element_name='税费'].fee_ratio",
        "mat_part.unit_weight",
        "v_costing_exchange_rate[from_currency='CNY',to_currency='USD'].costing_rate",
        "v_costing_summary_full.quote_currency",
        "v_costing_summary_full.weight_unit"
    ]$JSON$::jsonb,
    description = '基于 data/template/核价系统计算公式和取值（示例）.xlsx 的全套计算公式构建。' ||
                  '所有数值均通过 BNF 路径引用基础数据(无硬编码字面量)：' ||
                  '6 个 VARIABLE 列读 v_costing_summary_full(基础成本) + 4 个 VARIABLE 列读 mat_fee(成品其他费用比例) + ' ||
                  '1 个 mat_part.unit_weight + 1 个 v_costing_exchange_rate(核价汇率) + ' ||
                  '11 个 FORMULA 列计算加价(管理/财务/利润/税)与总成本(CNY/KG, CNY/PCS, USD/KG, USD/PCS)。' ||
                  '基础数据维护变更时模板自动联动, 无需修改公式。' ||
                  '详细 mapping 见 docs/templates/核价Excel模板-完整公式版-mapping.md。',
    updated_at = now()
WHERE name = '核价Excel视图模板（完整公式版）';
