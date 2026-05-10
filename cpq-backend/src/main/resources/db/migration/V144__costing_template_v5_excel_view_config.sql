-- ============================================================
-- V144: 核价标准模板 v5.0 Excel 视图配置
--       为 V142 模板 (id=77decd71-c6cd-498a-9d8d-f47adfb024da) 配置 17 列布局 + 22 条公式
-- ============================================================
--
-- 已知正确答案 (自检参考，partNo=3100080003):
--   材料成本         = 4892.484
--   加工费           = 4.3369
--   管理费           = 30.43178959  (加价基数 × mgmt_fee_ratio)
--   总成本(CNY/KG)   = 6043.410233
--   总成本(USD/PCS)  = 1.667981224
--
-- 列布局 (17 可见列 + 18 隐藏中间列 = 35 列合计):
--
-- 17 可见列:
--   A  宏丰料号            VARIABLE  {hf_part_no}
--   B  材料成本            FORMULA   =[B_PURE]+[B_PROC]+[B_OTHER]+[B_FIX]-[B_RECYCLE]
--   C  材料损耗成本        FORMULA   =[C_LOSS]
--   D  包装材料费          FORMULA   =[D_PKG]
--   E  加工费              FORMULA   =[E_PROC]
--   F  管理费              FORMULA   =[BASE]*[F_MGMT]
--   G  财务费              FORMULA   =[BASE]*[G_FIN]
--   H  利润                FORMULA   =[BASE]*[H_PROFIT]
--   I  税费                FORMULA   =[BASE]*[I_TAX]
--   J  运费                VARIABLE  v_c_summary_agg.freight_fee
--   K  清关费              VARIABLE  v_c_summary_agg.customs_fee
--   L  电镀成本            FORMULA   =([L_EPROC]+[L_EMAT])*(1+[L_EDEF])
--   M  其他外加工成本      FORMULA   =[M_OUT]
--   N  总成本(CNY/KG)      FORMULA   =[B]+[C]+[D]+[E]+[F]+[G]+[H]+[I]+[J]+[K]+[L]+[M]
--   O  币种                VARIABLE  v_c_summary_agg.currency_label  (固定'CNY')
--   P  计量单位            VARIABLE  v_c_summary_agg.weight_unit_label (固定'KG')
--   Q  总成本(USD/PCS)     FORMULA   =[N]/1000*[Q_WT]*[Q_RATE]
--
-- 18 隐藏中间列 (VARIABLE 从视图取聚合值, 供 FORMULA 引用):
--   B_PURE    v_costing_summary_full.pure_material_cost
--   B_PROC    v_costing_summary_full.incoming_process_fee
--   B_OTHER   v_costing_summary_full.incoming_other_fee
--   B_FIX     v_c_summary_agg.incoming_fixed_fee
--   B_RECYCLE v_costing_summary_full.recycle_cost
--   C_LOSS    v_costing_summary_full.material_loss_cost
--   D_PKG     v_c_summary_agg.packaging_fee
--   E_PROC    v_costing_summary_full.process_fee_total
--   BASE      FORMULA =[B]+[C]+[D]+[E]+[L]+[M]  (加价基数, 顺序放在 E 列之后 L 之后)
--   F_MGMT    v_costing_summary_full.mgmt_fee_ratio
--   G_FIN     v_costing_summary_full.finance_fee_ratio
--   H_PROFIT  v_costing_summary_full.profit_ratio
--   I_TAX     v_costing_summary_full.tax_ratio
--   L_EPROC   v_costing_summary_full.plating_process_fee
--   L_EMAT    v_costing_summary_full.plating_material_fee
--   L_EDEF    v_costing_summary_full.plating_defect_rate
--   M_OUT     v_c_summary_agg.outsource_fee
--   Q_WT      v_costing_summary_full.unit_weight_g
--   Q_RATE    v_costing_summary_full.exchange_rate_to_usd
--
-- 技术约束说明 (docs/Excel模板配置指南.md 第七章):
--   LinkedExcelView 两遍扫描:
--     第一遍: 并发求值所有 VARIABLE 列 → pathCache
--     第二遍: 按数组顺序逐列计算 FORMULA 列 → 每列结果写入 rowCellValues 后供后续列引用
--   因此: FORMULA 列可以引用前面的 FORMULA 列，前提是列顺序正确。
--   BASE 列 (FORMULA) 放在 E 之后、F 之前; N 列放在 F/G/H/I 之后; Q 最后。
-- ============================================================

-- ════════════════════════════════════════════════════════════════════════════
-- Step 1: 新建聚合视图 v_c_summary_agg
--         每 hf_part_no 返回一行聚合值，供 excel_view_config VARIABLE 列 BNF 取数
-- ════════════════════════════════════════════════════════════════════════════
DROP VIEW IF EXISTS v_c_summary_agg CASCADE;
CREATE VIEW v_c_summary_agg AS
WITH
base_parts AS (
    -- 以 costing_part_weight 作为料号锚 (最稳定的单件表)
    SELECT DISTINCT hf_part_no FROM costing_part_weight WHERE COALESCE(is_active, true) = true
    UNION
    SELECT DISTINCT hf_part_no FROM costing_part_process_cost WHERE COALESCE(is_active, true) = true
    UNION
    SELECT DISTINCT hf_part_no FROM mat_fee WHERE COALESCE(is_current, true) = true AND COALESCE(status, 'ACTIVE') = 'ACTIVE'
),
pkg_agg AS (
    SELECT hf_part_no, SUM(unit_price) AS packaging_fee
    FROM costing_part_process_cost
    WHERE cost_type = 'CONSUMABLE'
      AND COALESCE(process_name, '') LIKE '%包装%'
      AND COALESCE(is_active, true) = true
    GROUP BY hf_part_no
),
ifix_agg AS (
    SELECT hf_part_no, SUM(fee_value) AS incoming_fixed_fee
    FROM mat_fee
    WHERE fee_type = 'INCOMING_FIXED'
      AND COALESCE(is_current, true) = true
      AND COALESCE(status, 'ACTIVE') = 'ACTIVE'
    GROUP BY hf_part_no
),
outsource_agg AS (
    SELECT hf_part_no, SUM(unit_price) AS outsource_fee
    FROM costing_part_process_cost
    WHERE cost_type = 'POST_PROC'
      AND COALESCE(is_active, true) = true
    GROUP BY hf_part_no
),
ffix_agg AS (
    SELECT
        hf_part_no,
        SUM(CASE WHEN dim_element_name LIKE '%运费%' THEN fee_value ELSE 0 END) AS freight_fee,
        SUM(CASE WHEN dim_element_name LIKE '%清关%' THEN fee_value ELSE 0 END) AS customs_fee
    FROM mat_fee
    WHERE fee_type = 'FINISHED_FIXED'
      AND COALESCE(is_current, true) = true
      AND COALESCE(status, 'ACTIVE') = 'ACTIVE'
    GROUP BY hf_part_no
)
SELECT
    p.hf_part_no,
    COALESCE(pkg.packaging_fee,    0) AS packaging_fee,
    COALESCE(ifix.incoming_fixed_fee, 0) AS incoming_fixed_fee,
    COALESCE(out_.outsource_fee,   0) AS outsource_fee,
    COALESCE(ff.freight_fee,       0) AS freight_fee,
    COALESCE(ff.customs_fee,       0) AS customs_fee,
    'CNY'::varchar(10)               AS currency_label,
    'KG'::varchar(10)                AS weight_unit_label
FROM base_parts p
LEFT JOIN pkg_agg     pkg  ON pkg.hf_part_no  = p.hf_part_no
LEFT JOIN ifix_agg    ifix ON ifix.hf_part_no = p.hf_part_no
LEFT JOIN outsource_agg out_ ON out_.hf_part_no = p.hf_part_no
LEFT JOIN ffix_agg    ff   ON ff.hf_part_no   = p.hf_part_no;

COMMENT ON VIEW v_c_summary_agg IS
    'V144: 每 hf_part_no 聚合一行，提供 packaging_fee / incoming_fixed_fee / outsource_fee / '
    'freight_fee / customs_fee / currency_label(CNY) / weight_unit_label(KG)';

-- ════════════════════════════════════════════════════════════════════════════
-- Step 2: 在 costing_template 表新建 V5.0 Excel 视图模板记录
--         用 DO 块方式，提供 series_id（必填 NOT NULL 字段）
--         linked_template_id = '77decd71-c6cd-498a-9d8d-f47adfb024da'
--         状态 DRAFT，用户审核后手工 publish 并设 is_default=true
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_template_id UUID := 'a1b2c3d4-e5f6-7890-abcd-144000000001'::uuid;
    v_series_id   UUID := gen_random_uuid();
BEGIN
    IF EXISTS (SELECT 1 FROM costing_template WHERE id = v_template_id) THEN
        RAISE NOTICE 'V144: costing_template id=% 已存在，跳过插入', v_template_id;
        RETURN;
    END IF;

    INSERT INTO costing_template (
        id, series_id, name, version, description, status, is_default,
        linked_template_id, columns, referenced_variables,
        created_at, updated_at
    ) VALUES (
        v_template_id,
        v_series_id,
        '核价标准模板v5.0-Excel视图',
        'v1.0',
        'V144: 17 可见列 + 18 隐藏中间列 = 35 列。'
        '材料成本/损耗/包装/加工/管理/财务/利润/税费/运费/清关/电镀/外加工 + 总成本(CNY/KG) + 总成本(USD/PCS)。'
        '中间列 VARIABLE 从 v_costing_summary_full / v_c_summary_agg 取聚合值；FORMULA 列做 scalar 算术。'
        '已知正确答案(3100080003): 材料成本=4892.484, 加工费=4.3369, 管理费=30.43178959, '
        '总成本(CNY/KG)=6043.410233, 总成本(USD/PCS)=1.667981224。',
        'DRAFT',
        false,
        '77decd71-c6cd-498a-9d8d-f47adfb024da'::uuid,
        $COLS$[
        {"col_key":"B_PURE",   "title":"纯材料成本",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.pure_material_cost",  "hidden":true},
        {"col_key":"B_PROC",   "title":"来料加工费",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.incoming_process_fee","hidden":true},
        {"col_key":"B_OTHER",  "title":"来料其他比例费用", "source_type":"VARIABLE","variable_path":"v_costing_summary_full.incoming_other_fee",   "hidden":true},
        {"col_key":"B_FIX",    "title":"来料其他固定费用", "source_type":"VARIABLE","variable_path":"v_c_summary_agg.incoming_fixed_fee",          "hidden":true},
        {"col_key":"B_RECYCLE","title":"回收成本",         "source_type":"VARIABLE","variable_path":"v_costing_summary_full.recycle_cost",         "hidden":true},
        {"col_key":"C_LOSS",   "title":"材料损耗成本源",   "source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_loss_cost",   "hidden":true},
        {"col_key":"D_PKG",    "title":"包装材料费源",     "source_type":"VARIABLE","variable_path":"v_c_summary_agg.packaging_fee",              "hidden":true},
        {"col_key":"E_PROC",   "title":"加工费源",         "source_type":"VARIABLE","variable_path":"v_costing_summary_full.process_fee_total",   "hidden":true},
        {"col_key":"L_EPROC",  "title":"电镀加工费",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_process_fee", "hidden":true},
        {"col_key":"L_EMAT",   "title":"电镀材料费",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_material_fee","hidden":true},
        {"col_key":"L_EDEF",   "title":"电镀不良率",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_defect_rate", "hidden":true},
        {"col_key":"M_OUT",    "title":"外加工成本源",     "source_type":"VARIABLE","variable_path":"v_c_summary_agg.outsource_fee",              "hidden":true},
        {"col_key":"F_MGMT",   "title":"管理费比例",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.mgmt_fee_ratio",      "hidden":true},
        {"col_key":"G_FIN",    "title":"财务费比例",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.finance_fee_ratio",   "hidden":true},
        {"col_key":"H_PROFIT", "title":"利润比例",         "source_type":"VARIABLE","variable_path":"v_costing_summary_full.profit_ratio",        "hidden":true},
        {"col_key":"I_TAX",    "title":"税费比例",         "source_type":"VARIABLE","variable_path":"v_costing_summary_full.tax_ratio",           "hidden":true},
        {"col_key":"Q_WT",     "title":"单重(g/pcs)",      "source_type":"VARIABLE","variable_path":"v_costing_summary_full.unit_weight_g",       "hidden":true},
        {"col_key":"Q_RATE",   "title":"核价汇率(CNY-USD)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.exchange_rate_to_usd","hidden":true},

        {"col_key":"A","title":"宏丰料号",      "source_type":"VARIABLE","variable_path":"{hf_part_no}","hidden":false},

        {"col_key":"B","title":"材料成本",      "source_type":"FORMULA","formula":"=[B_PURE]+[B_PROC]+[B_OTHER]+[B_FIX]-[B_RECYCLE]","hidden":false,"comparison_tag":"MATERIAL_COST"},
        {"col_key":"C","title":"材料损耗成本",  "source_type":"FORMULA","formula":"=[C_LOSS]",                                       "hidden":false,"comparison_tag":"MATERIAL_LOSS"},
        {"col_key":"D","title":"包装材料费",    "source_type":"FORMULA","formula":"=[D_PKG]",                                        "hidden":false,"comparison_tag":"PACKAGING_FEE"},
        {"col_key":"E","title":"加工费",        "source_type":"FORMULA","formula":"=[E_PROC]",                                       "hidden":false,"comparison_tag":"PROCESS_FEE"},
        {"col_key":"L","title":"电镀成本",      "source_type":"FORMULA","formula":"=([L_EPROC]+[L_EMAT])*(1+[L_EDEF])",             "hidden":false,"comparison_tag":"PLATING_COST"},
        {"col_key":"M","title":"其他外加工成本","source_type":"FORMULA","formula":"=[M_OUT]",                                        "hidden":false,"comparison_tag":"OUTSOURCE_COST"},

        {"col_key":"BASE","title":"加价基数",
         "source_type":"FORMULA","formula":"=[B]+[C]+[D]+[E]+[L]+[M]","hidden":true},

        {"col_key":"F","title":"管理费","source_type":"FORMULA","formula":"=[BASE]*[F_MGMT]","hidden":false,"comparison_tag":"MGMT_FEE"},
        {"col_key":"G","title":"财务费","source_type":"FORMULA","formula":"=[BASE]*[G_FIN]", "hidden":false,"comparison_tag":"FINANCE_FEE"},
        {"col_key":"H","title":"利润",  "source_type":"FORMULA","formula":"=[BASE]*[H_PROFIT]","hidden":false,"comparison_tag":"PROFIT"},
        {"col_key":"I","title":"税费",  "source_type":"FORMULA","formula":"=[BASE]*[I_TAX]",  "hidden":false,"comparison_tag":"TAX"},

        {"col_key":"J","title":"运费",  "source_type":"VARIABLE","variable_path":"v_c_summary_agg.freight_fee","hidden":false,"comparison_tag":"FREIGHT_FEE"},
        {"col_key":"K","title":"清关费","source_type":"VARIABLE","variable_path":"v_c_summary_agg.customs_fee","hidden":false,"comparison_tag":"CUSTOMS_FEE"},

        {"col_key":"N","title":"总成本(CNY/KG)",
         "source_type":"FORMULA","formula":"=[B]+[C]+[D]+[E]+[F]+[G]+[H]+[I]+[J]+[K]+[L]+[M]",
         "hidden":false,"comparison_tag":"TOTAL_CNY_KG"},

        {"col_key":"O","title":"币种",    "source_type":"VARIABLE","variable_path":"v_c_summary_agg.currency_label",   "hidden":false},
        {"col_key":"P","title":"计量单位","source_type":"VARIABLE","variable_path":"v_c_summary_agg.weight_unit_label","hidden":false},

        {"col_key":"Q","title":"总成本(USD/PCS)",
         "source_type":"FORMULA","formula":"=[N]/1000*[Q_WT]*[Q_RATE]",
         "hidden":false,"comparison_tag":"TOTAL_USD_PCS"}
        ]$COLS$::jsonb,
        $REFS$[
        "{hf_part_no}",
        "v_costing_summary_full.pure_material_cost",
        "v_costing_summary_full.incoming_process_fee",
        "v_costing_summary_full.incoming_other_fee",
        "v_c_summary_agg.incoming_fixed_fee",
        "v_costing_summary_full.recycle_cost",
        "v_costing_summary_full.material_loss_cost",
        "v_c_summary_agg.packaging_fee",
        "v_costing_summary_full.process_fee_total",
        "v_costing_summary_full.plating_process_fee",
        "v_costing_summary_full.plating_material_fee",
        "v_costing_summary_full.plating_defect_rate",
        "v_c_summary_agg.outsource_fee",
        "v_costing_summary_full.mgmt_fee_ratio",
        "v_costing_summary_full.finance_fee_ratio",
        "v_costing_summary_full.profit_ratio",
        "v_costing_summary_full.tax_ratio",
        "v_c_summary_agg.freight_fee",
        "v_c_summary_agg.customs_fee",
        "v_c_summary_agg.currency_label",
        "v_c_summary_agg.weight_unit_label",
        "v_costing_summary_full.unit_weight_g",
        "v_costing_summary_full.exchange_rate_to_usd"
        ]$REFS$::jsonb,
        now(),
        now()
    );

    RAISE NOTICE 'V144: costing_template 插入成功 id=% series=%', v_template_id, v_series_id;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- Step 3: 更新 template.excel_view_config
--         格式: JSON 数组 (与 costing_template.columns 保持一致)
--         包含 17 可见列 (visible=true) + 18 隐藏中间列 (hidden=true)
--         合并为单一数组，用 hidden/visible 字段区分。
--         注意: 用 $JSON$[ 开头以避免 Flyway 占位符扫描错误。
-- ════════════════════════════════════════════════════════════════════════════
UPDATE template
SET
    excel_view_config = $JSON$[
        {"col_key":"B_PURE",   "title":"纯材料成本",       "col_name":"纯材料成本",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.pure_material_cost",  "hidden":true,"visible":false},
        {"col_key":"B_PROC",   "title":"来料加工费",       "col_name":"来料加工费",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.incoming_process_fee","hidden":true,"visible":false},
        {"col_key":"B_OTHER",  "title":"来料其他比例费用", "col_name":"来料其他比例费用", "source_type":"VARIABLE","variable_path":"v_costing_summary_full.incoming_other_fee",   "hidden":true,"visible":false},
        {"col_key":"B_FIX",    "title":"来料其他固定费用", "col_name":"来料其他固定费用", "source_type":"VARIABLE","variable_path":"v_c_summary_agg.incoming_fixed_fee",          "hidden":true,"visible":false},
        {"col_key":"B_RECYCLE","title":"回收成本",         "col_name":"回收成本",         "source_type":"VARIABLE","variable_path":"v_costing_summary_full.recycle_cost",         "hidden":true,"visible":false},
        {"col_key":"C_LOSS",   "title":"材料损耗成本源",   "col_name":"材料损耗成本源",   "source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_loss_cost",   "hidden":true,"visible":false},
        {"col_key":"D_PKG",    "title":"包装材料费源",     "col_name":"包装材料费源",     "source_type":"VARIABLE","variable_path":"v_c_summary_agg.packaging_fee",              "hidden":true,"visible":false},
        {"col_key":"E_PROC",   "title":"加工费源",         "col_name":"加工费源",         "source_type":"VARIABLE","variable_path":"v_costing_summary_full.process_fee_total",   "hidden":true,"visible":false},
        {"col_key":"L_EPROC",  "title":"电镀加工费",       "col_name":"电镀加工费",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_process_fee", "hidden":true,"visible":false},
        {"col_key":"L_EMAT",   "title":"电镀材料费",       "col_name":"电镀材料费",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_material_fee","hidden":true,"visible":false},
        {"col_key":"L_EDEF",   "title":"电镀不良率",       "col_name":"电镀不良率",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_defect_rate", "hidden":true,"visible":false},
        {"col_key":"M_OUT",    "title":"外加工成本源",     "col_name":"外加工成本源",     "source_type":"VARIABLE","variable_path":"v_c_summary_agg.outsource_fee",              "hidden":true,"visible":false},
        {"col_key":"F_MGMT",   "title":"管理费比例",       "col_name":"管理费比例",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.mgmt_fee_ratio",      "hidden":true,"visible":false},
        {"col_key":"G_FIN",    "title":"财务费比例",       "col_name":"财务费比例",       "source_type":"VARIABLE","variable_path":"v_costing_summary_full.finance_fee_ratio",   "hidden":true,"visible":false},
        {"col_key":"H_PROFIT", "title":"利润比例",         "col_name":"利润比例",         "source_type":"VARIABLE","variable_path":"v_costing_summary_full.profit_ratio",        "hidden":true,"visible":false},
        {"col_key":"I_TAX",    "title":"税费比例",         "col_name":"税费比例",         "source_type":"VARIABLE","variable_path":"v_costing_summary_full.tax_ratio",           "hidden":true,"visible":false},
        {"col_key":"Q_WT",     "title":"单重(g/pcs)",      "col_name":"单重(g/pcs)",      "source_type":"VARIABLE","variable_path":"v_costing_summary_full.unit_weight_g",       "hidden":true,"visible":false},
        {"col_key":"Q_RATE",   "title":"核价汇率(CNY到USD)","col_name":"核价汇率(CNY到USD)","source_type":"VARIABLE","variable_path":"v_costing_summary_full.exchange_rate_to_usd","hidden":true,"visible":false},

        {"col_key":"A","title":"宏丰料号",       "col_name":"宏丰料号",       "col_index":"A","source_type":"VARIABLE","variable_path":"{hf_part_no}",                                       "hidden":false,"visible":true},
        {"col_key":"B","title":"材料成本",       "col_name":"材料成本",       "col_index":"B","source_type":"FORMULA", "formula":"=[B_PURE]+[B_PROC]+[B_OTHER]+[B_FIX]-[B_RECYCLE]","hidden":false,"visible":true,"comparison_tag":"MATERIAL_COST"},
        {"col_key":"C","title":"材料损耗成本",   "col_name":"材料损耗成本",   "col_index":"C","source_type":"FORMULA", "formula":"=[C_LOSS]",                                        "hidden":false,"visible":true,"comparison_tag":"MATERIAL_LOSS"},
        {"col_key":"D","title":"包装材料费",     "col_name":"包装材料费",     "col_index":"D","source_type":"FORMULA", "formula":"=[D_PKG]",                                         "hidden":false,"visible":true,"comparison_tag":"PACKAGING_FEE"},
        {"col_key":"E","title":"加工费",         "col_name":"加工费",         "col_index":"E","source_type":"FORMULA", "formula":"=[E_PROC]",                                        "hidden":false,"visible":true,"comparison_tag":"PROCESS_FEE"},
        {"col_key":"L","title":"电镀成本",       "col_name":"电镀成本",       "col_index":"L","source_type":"FORMULA", "formula":"=([L_EPROC]+[L_EMAT])*(1+[L_EDEF])",              "hidden":false,"visible":true,"comparison_tag":"PLATING_COST"},
        {"col_key":"M","title":"其他外加工成本", "col_name":"其他外加工成本", "col_index":"M","source_type":"FORMULA", "formula":"=[M_OUT]",                                         "hidden":false,"visible":true,"comparison_tag":"OUTSOURCE_COST"},
        {"col_key":"BASE","title":"加价基数",    "col_name":"加价基数",       "source_type":"FORMULA","formula":"=[B]+[C]+[D]+[E]+[L]+[M]",                                       "hidden":true,"visible":false},
        {"col_key":"F","title":"管理费",         "col_name":"管理费",         "col_index":"F","source_type":"FORMULA", "formula":"=[BASE]*[F_MGMT]",                                "hidden":false,"visible":true,"comparison_tag":"MGMT_FEE"},
        {"col_key":"G","title":"财务费",         "col_name":"财务费",         "col_index":"G","source_type":"FORMULA", "formula":"=[BASE]*[G_FIN]",                                 "hidden":false,"visible":true,"comparison_tag":"FINANCE_FEE"},
        {"col_key":"H","title":"利润",           "col_name":"利润",           "col_index":"H","source_type":"FORMULA", "formula":"=[BASE]*[H_PROFIT]",                              "hidden":false,"visible":true,"comparison_tag":"PROFIT"},
        {"col_key":"I","title":"税费",           "col_name":"税费",           "col_index":"I","source_type":"FORMULA", "formula":"=[BASE]*[I_TAX]",                                 "hidden":false,"visible":true,"comparison_tag":"TAX"},
        {"col_key":"J","title":"运费",           "col_name":"运费",           "col_index":"J","source_type":"VARIABLE","variable_path":"v_c_summary_agg.freight_fee",               "hidden":false,"visible":true,"comparison_tag":"FREIGHT_FEE"},
        {"col_key":"K","title":"清关费",         "col_name":"清关费",         "col_index":"K","source_type":"VARIABLE","variable_path":"v_c_summary_agg.customs_fee",               "hidden":false,"visible":true,"comparison_tag":"CUSTOMS_FEE"},
        {"col_key":"N","title":"总成本(CNY/KG)", "col_name":"总成本(CNY/KG)","col_index":"N","source_type":"FORMULA","formula":"=[B]+[C]+[D]+[E]+[F]+[G]+[H]+[I]+[J]+[K]+[L]+[M]","hidden":false,"visible":true,"comparison_tag":"TOTAL_CNY_KG"},
        {"col_key":"O","title":"币种",           "col_name":"币种",           "col_index":"O","source_type":"VARIABLE","variable_path":"v_c_summary_agg.currency_label",            "hidden":false,"visible":true},
        {"col_key":"P","title":"计量单位",       "col_name":"计量单位",       "col_index":"P","source_type":"VARIABLE","variable_path":"v_c_summary_agg.weight_unit_label",         "hidden":false,"visible":true},
        {"col_key":"Q","title":"总成本(USD/PCS)","col_name":"总成本(USD/PCS)","col_index":"Q","source_type":"FORMULA","formula":"=[N]/1000*[Q_WT]*[Q_RATE]",                         "hidden":false,"visible":true,"comparison_tag":"TOTAL_USD_PCS"}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE id = '77decd71-c6cd-498a-9d8d-f47adfb024da'::uuid;

-- ════════════════════════════════════════════════════════════════════════════
-- Step 4: 验证
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_template_ok      INT;
    v_ct_cols          INT;
    v_visible_cols     INT;
    v_hidden_cols      INT;
    v_agg_view_cols    INT;
    v_ct_id            uuid := 'a1b2c3d4-e5f6-7890-abcd-144000000001'::uuid;
BEGIN
    -- 1. template.excel_view_config 写入检查
    --    excel_view_config 现在是 JSON 数组 (17可见+18隐藏=35条)
    SELECT COUNT(*) INTO v_template_ok
    FROM template
    WHERE id = '77decd71-c6cd-498a-9d8d-f47adfb024da'::uuid
      AND excel_view_config IS NOT NULL
      AND jsonb_typeof(excel_view_config) = 'array'
      AND jsonb_array_length(excel_view_config) >= 17;

    IF v_template_ok = 1 THEN
        RAISE NOTICE 'V144 CHECK-1 OK: template.excel_view_config 写入成功，数组总条数>=17';
    ELSE
        RAISE WARNING 'V144 CHECK-1 FAIL: template.excel_view_config 写入异常';
    END IF;

    -- 2. costing_template 建立检查
    SELECT jsonb_array_length(columns) INTO v_ct_cols
    FROM costing_template WHERE id = v_ct_id;

    IF v_ct_cols IS NOT NULL THEN
        RAISE NOTICE 'V144 CHECK-2 OK: costing_template 建立成功，columns 总条数=%', v_ct_cols;
    ELSE
        RAISE WARNING 'V144 CHECK-2 FAIL: costing_template 记录未插入';
    END IF;

    -- 3. 可见列 / 隐藏列统计
    SELECT
        COUNT(*) FILTER (WHERE (col->>'hidden')::boolean IS NOT TRUE) AS vis,
        COUNT(*) FILTER (WHERE (col->>'hidden')::boolean = TRUE)       AS hid
    INTO v_visible_cols, v_hidden_cols
    FROM costing_template ct,
         jsonb_array_elements(ct.columns) col
    WHERE ct.id = v_ct_id;

    RAISE NOTICE 'V144 CHECK-3: costing_template 可见列=% (期望17), 隐藏列=% (期望18)', v_visible_cols, v_hidden_cols;

    -- 4. v_c_summary_agg 视图检查
    SELECT COUNT(*) INTO v_agg_view_cols
    FROM information_schema.columns
    WHERE table_name = 'v_c_summary_agg' AND table_schema = 'public';

    IF v_agg_view_cols >= 8 THEN
        RAISE NOTICE 'V144 CHECK-4 OK: v_c_summary_agg 视图存在，% 列', v_agg_view_cols;
    ELSE
        RAISE WARNING 'V144 CHECK-4 FAIL: v_c_summary_agg 视图列数=%，期望>=8', v_agg_view_cols;
    END IF;

    RAISE NOTICE 'V144 完成: 新建 v_c_summary_agg 聚合视图 + costing_template(DRAFT) + template.excel_view_config(17列)';
END $$;
