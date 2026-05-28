-- ============================================================
-- V258: v1.2 Excel 视图列配置重写
--
-- 把 v1.2 模板的 excel_view_config 35 列 variable_path 全部从
--   v_costing_summary_full.xxx / v_c_summary_agg.xxx
-- 改为
--   $summary_material.xxx / $summary_part.xxx /
--   $summary_plating_cost.xxx / $summary_mgmt_fee_ratio.xxx /
--   $summary_finance_fee_ratio.xxx / $summary_profit_tax_ratio.xxx /
--   $summary_unit_weight_rate.xxx
-- 36 列总数 = 18 隐藏 VARIABLE + 18 可见列
--   （其中 13 FORMULA + 5 VARIABLE 可见 + 1 A 行 lineItem + 1 BASE 隐藏 FORMULA）
--
-- 幂等：WHERE template_series_id=... AND version='v1.2' AND status='DRAFT'
-- ============================================================

DO $$ BEGIN RAISE NOTICE 'V258: 开始改写 v1.2 Excel 视图列配置'; END $$;

UPDATE template
SET
    excel_view_config = $JSON$[
        {"col_key":"B_PURE",   "title":"纯材料成本",           "col_name":"纯材料成本",           "source_type":"VARIABLE","variable_path":"$summary_material.pure_material_cost",          "hidden":true,"visible":false},
        {"col_key":"B_PROC",   "title":"来料加工费",           "col_name":"来料加工费",           "source_type":"VARIABLE","variable_path":"$summary_material.incoming_process_fee",        "hidden":true,"visible":false},
        {"col_key":"B_OTHER",  "title":"来料其他比例费用",     "col_name":"来料其他比例费用",     "source_type":"VARIABLE","variable_path":"$summary_material.incoming_other_fee",           "hidden":true,"visible":false},
        {"col_key":"B_FIX",    "title":"来料其他固定费用",     "col_name":"来料其他固定费用",     "source_type":"VARIABLE","variable_path":"$summary_part.incoming_fixed_fee",               "hidden":true,"visible":false},
        {"col_key":"B_RECYCLE","title":"回收成本",             "col_name":"回收成本",             "source_type":"VARIABLE","variable_path":"$summary_material.recycle_cost",                "hidden":true,"visible":false},
        {"col_key":"C_LOSS",   "title":"材料损耗成本源",       "col_name":"材料损耗成本源",       "source_type":"VARIABLE","variable_path":"$summary_material.material_loss_cost",           "hidden":true,"visible":false},
        {"col_key":"D_PKG",    "title":"包装材料费源",         "col_name":"包装材料费源",         "source_type":"VARIABLE","variable_path":"$summary_part.packaging_fee",                   "hidden":true,"visible":false},
        {"col_key":"E_PROC",   "title":"加工费源",             "col_name":"加工费源",             "source_type":"VARIABLE","variable_path":"$summary_material.process_fee_total",           "hidden":true,"visible":false},
        {"col_key":"L_EPROC",  "title":"电镀加工费",           "col_name":"电镀加工费",           "source_type":"VARIABLE","variable_path":"$summary_plating_cost.plating_process_fee",    "hidden":true,"visible":false},
        {"col_key":"L_EMAT",   "title":"电镀材料费",           "col_name":"电镀材料费",           "source_type":"VARIABLE","variable_path":"$summary_plating_cost.plating_material_fee",   "hidden":true,"visible":false},
        {"col_key":"L_EDEF",   "title":"电镀不良率",           "col_name":"电镀不良率",           "source_type":"VARIABLE","variable_path":"$summary_plating_cost.plating_defect_rate",    "hidden":true,"visible":false},
        {"col_key":"M_OUT",    "title":"外加工成本源",         "col_name":"外加工成本源",         "source_type":"VARIABLE","variable_path":"$summary_part.outsource_fee",                  "hidden":true,"visible":false},
        {"col_key":"F_MGMT",   "title":"管理费比例",           "col_name":"管理费比例",           "source_type":"VARIABLE","variable_path":"$summary_mgmt_fee_ratio.mgmt_fee_ratio",       "hidden":true,"visible":false},
        {"col_key":"G_FIN",    "title":"财务费比例",           "col_name":"财务费比例",           "source_type":"VARIABLE","variable_path":"$summary_finance_fee_ratio.finance_fee_ratio", "hidden":true,"visible":false},
        {"col_key":"H_PROFIT", "title":"利润比例",             "col_name":"利润比例",             "source_type":"VARIABLE","variable_path":"$summary_profit_tax_ratio.profit_ratio",       "hidden":true,"visible":false},
        {"col_key":"I_TAX",    "title":"税费比例",             "col_name":"税费比例",             "source_type":"VARIABLE","variable_path":"$summary_profit_tax_ratio.tax_ratio",          "hidden":true,"visible":false},
        {"col_key":"Q_WT",     "title":"单重(g/pcs)",          "col_name":"单重(g/pcs)",          "source_type":"VARIABLE","variable_path":"$summary_unit_weight_rate.unit_weight_g",      "hidden":true,"visible":false},
        {"col_key":"Q_RATE",   "title":"核价汇率(CNY到USD)",   "col_name":"核价汇率(CNY到USD)",   "source_type":"VARIABLE","variable_path":"$summary_unit_weight_rate.exchange_rate_to_usd","hidden":true,"visible":false},

        {"col_key":"A","title":"宏丰料号",       "col_name":"宏丰料号",       "col_index":"A","source_type":"VARIABLE","variable_path":"{hf_part_no}",                                            "hidden":false,"visible":true},
        {"col_key":"B","title":"材料成本",       "col_name":"材料成本",       "col_index":"B","source_type":"FORMULA", "formula":"=[B_PURE]+[B_PROC]+[B_OTHER]+[B_FIX]-[B_RECYCLE]",  "hidden":false,"visible":true,"comparison_tag":"MATERIAL_COST"},
        {"col_key":"C","title":"材料损耗成本",   "col_name":"材料损耗成本",   "col_index":"C","source_type":"FORMULA", "formula":"=[C_LOSS]",                                             "hidden":false,"visible":true,"comparison_tag":"MATERIAL_LOSS"},
        {"col_key":"D","title":"包装材料费",     "col_name":"包装材料费",     "col_index":"D","source_type":"FORMULA", "formula":"=[D_PKG]",                                              "hidden":false,"visible":true,"comparison_tag":"PACKAGING_FEE"},
        {"col_key":"E","title":"加工费",         "col_name":"加工费",         "col_index":"E","source_type":"FORMULA", "formula":"=[E_PROC]",                                             "hidden":false,"visible":true,"comparison_tag":"PROCESS_FEE"},
        {"col_key":"L","title":"电镀成本",       "col_name":"电镀成本",       "col_index":"L","source_type":"FORMULA", "formula":"=([L_EPROC]+[L_EMAT])*(1+[L_EDEF])",                   "hidden":false,"visible":true,"comparison_tag":"PLATING_COST"},
        {"col_key":"M","title":"其他外加工成本", "col_name":"其他外加工成本", "col_index":"M","source_type":"FORMULA", "formula":"=[M_OUT]",                                              "hidden":false,"visible":true,"comparison_tag":"OUTSOURCE_COST"},
        {"col_key":"BASE","title":"加价基数",    "col_name":"加价基数",       "source_type":"FORMULA","formula":"=[B]+[C]+[D]+[E]+[L]+[M]",                                             "hidden":true,"visible":false},
        {"col_key":"F","title":"管理费",         "col_name":"管理费",         "col_index":"F","source_type":"FORMULA", "formula":"=[BASE]*[F_MGMT]",                                     "hidden":false,"visible":true,"comparison_tag":"MGMT_FEE"},
        {"col_key":"G","title":"财务费",         "col_name":"财务费",         "col_index":"G","source_type":"FORMULA", "formula":"=[BASE]*[G_FIN]",                                      "hidden":false,"visible":true,"comparison_tag":"FINANCE_FEE"},
        {"col_key":"H","title":"利润",           "col_name":"利润",           "col_index":"H","source_type":"FORMULA", "formula":"=[BASE]*[H_PROFIT]",                                   "hidden":false,"visible":true,"comparison_tag":"PROFIT"},
        {"col_key":"I","title":"税费",           "col_name":"税费",           "col_index":"I","source_type":"FORMULA", "formula":"=[BASE]*[I_TAX]",                                      "hidden":false,"visible":true,"comparison_tag":"TAX"},
        {"col_key":"J","title":"运费",           "col_name":"运费",           "col_index":"J","source_type":"VARIABLE","variable_path":"$summary_part.freight_fee",                      "hidden":false,"visible":true,"comparison_tag":"FREIGHT_FEE"},
        {"col_key":"K","title":"清关费",         "col_name":"清关费",         "col_index":"K","source_type":"VARIABLE","variable_path":"$summary_part.customs_fee",                      "hidden":false,"visible":true,"comparison_tag":"CUSTOMS_FEE"},
        {"col_key":"N","title":"总成本(CNY/KG)", "col_name":"总成本(CNY/KG)","col_index":"N","source_type":"FORMULA", "formula":"=[B]+[C]+[D]+[E]+[F]+[G]+[H]+[I]+[J]+[K]+[L]+[M]",   "hidden":false,"visible":true,"comparison_tag":"TOTAL_CNY_KG"},
        {"col_key":"O","title":"币种",           "col_name":"币种",           "col_index":"O","source_type":"VARIABLE","variable_path":"$summary_part.currency_label",                  "hidden":false,"visible":true},
        {"col_key":"P","title":"计量单位",       "col_name":"计量单位",       "col_index":"P","source_type":"VARIABLE","variable_path":"$summary_part.weight_unit_label",               "hidden":false,"visible":true},
        {"col_key":"Q","title":"总成本(USD/PCS)","col_name":"总成本(USD/PCS)","col_index":"Q","source_type":"FORMULA","formula":"=[N]/1000*[Q_WT]*[Q_RATE]",                             "hidden":false,"visible":true,"comparison_tag":"TOTAL_USD_PCS"}
    ]$JSON$::jsonb,
    referenced_variables = $REFS$[
        "{hf_part_no}",
        "$summary_material.pure_material_cost",
        "$summary_material.incoming_process_fee",
        "$summary_material.incoming_other_fee",
        "$summary_part.incoming_fixed_fee",
        "$summary_material.recycle_cost",
        "$summary_material.material_loss_cost",
        "$summary_part.packaging_fee",
        "$summary_material.process_fee_total",
        "$summary_plating_cost.plating_process_fee",
        "$summary_plating_cost.plating_material_fee",
        "$summary_plating_cost.plating_defect_rate",
        "$summary_part.outsource_fee",
        "$summary_mgmt_fee_ratio.mgmt_fee_ratio",
        "$summary_finance_fee_ratio.finance_fee_ratio",
        "$summary_profit_tax_ratio.profit_ratio",
        "$summary_profit_tax_ratio.tax_ratio",
        "$summary_part.freight_fee",
        "$summary_part.customs_fee",
        "$summary_part.currency_label",
        "$summary_part.weight_unit_label",
        "$summary_unit_weight_rate.unit_weight_g",
        "$summary_unit_weight_rate.exchange_rate_to_usd"
    ]$REFS$::jsonb,
    updated_at = NOW()
WHERE template_series_id = 'ca11f33d-6424-437d-af3f-25ea01674fcd'
  AND version = 'v1.2'
  AND status  = 'DRAFT';

DO $$
DECLARE v_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt
    FROM template
    WHERE template_series_id = 'ca11f33d-6424-437d-af3f-25ea01674fcd'
      AND version = 'v1.2'
      AND status  = 'DRAFT'
      AND jsonb_array_length(COALESCE(excel_view_config, '[]'::jsonb)) >= 36;
    IF v_cnt = 1 THEN
        RAISE NOTICE 'V258 CHECK OK: v1.2 excel_view_config 已写入 36 列';
    ELSE
        RAISE WARNING 'V258 CHECK FAIL: v1.2 excel_view_config 写入异常，count=%', v_cnt;
    END IF;
END $$;
