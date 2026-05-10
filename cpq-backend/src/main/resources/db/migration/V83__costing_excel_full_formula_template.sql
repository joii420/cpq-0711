-- V83: 核价 Excel 视图模板「完整公式版」
--
-- 来源: data/template/核价系统计算公式和取值（示例）.xlsx
--   该 Excel 描述了核价单的全部计算链路, 含「汇总」页签(行 73~74)和 21 条公式定义(行 78~100)。
--
-- 与 V80 的关系:
--   V80 创建的「核价-汇总演示模板」(0a8441c0) 把 9 个商务加价/总成本列全部声明为 VARIABLE,
--   读 v_costing_summary_full 视图; 但视图里这 6 个加价列都是 NULL 占位(等 compute() 升级)。
--   本迁移按用户提供的 Excel 公式, 用 FORMULA 列在前端 Excel 视图层把加价链算出来,
--   即使后端 compute() 暂未填上, 前端依然能看到完整成本拆解。
--
-- 列结构 (共 19 列):
--   A  宏丰料号       VARIABLE  {hf_part_no}                                由 lineItem.productPartNo 注入
--   B  材料成本       VARIABLE  v_costing_summary_full.material_cost
--   C  材料损耗成本   VARIABLE  v_costing_summary_full.material_loss_cost   compute() 落地后自动有值
--   D  加工费         VARIABLE  v_costing_summary_full.processing_cost
--   E  电镀成本       VARIABLE  v_costing_summary_full.plating_cost         compute() 落地后自动有值
--   F  其他外加工成本 VARIABLE  v_costing_summary_full.other_outsource_cost compute() 落地后自动有值
--   G  加价基数       FORMULA   =[B]+[C]+[D]+[E]+[F]                        中间列, Excel 公式中的 "(材料成本+加工费+材料损耗成本+电镀成本+其他外加工成本)"
--   H  管理费         FORMULA   =[G]*0.008                                  Excel 行 50: 比例=0.8%
--   I  财务费         FORMULA   =[G]*0.012                                  Excel 行 51: 比例=1.2%
--   J  利润           FORMULA   =[G]*0.05                                   Excel 行 52: 比例=5%
--   K  税费           FORMULA   =[G]*0.13                                   Excel 行 53: 比例=13%
--   L  总成本(CNY/KG) FORMULA   =[G]+[H]+[I]+[J]+[K]                        Excel 行 97
--   M  单重(g/pcs)    VARIABLE  mat_part.unit_weight                        Excel 行 68/69 → mat_part 基础数据
--   N  总成本(CNY/PCS)FORMULA   =[L]/1000/[M]                               Excel 行 98
--   O  核价汇率       FORMULA   =0.138                                      Excel 行 3 (CNY→USD); TODO: 改为 BNF 取 EXCHANGE 版本
--   P  总成本(USD/KG) FORMULA   =[L]*[O]                                    Excel 行 99
--   Q  总成本(USD/PCS)FORMULA   =[P]/1000/[M]                               Excel 行 100
--   R  报价币种       VARIABLE  v_costing_summary_full.quote_currency
--   S  计量单位       VARIABLE  v_costing_summary_full.weight_unit          固定 'KG'
--
-- 已知约束:
--   * 4 个加价比例 (0.8/1.2/5/13) 当前作为字面量写死在 FORMULA 中; 用户的 Excel 是按
--     「来料其他费用 / 成品其他费用」分级配置, 后续如果接入数据源 (mat_fee[fee_type='MGMT'].fee_value 等),
--     编辑此模板把对应 FORMULA 改为 [{path}] 形式即可。
--   * O 列核价汇率同上, 当前用字面量 0.138; 后续改为 BNF (例如 v_costing_summary_full.exchange_rate, 视图需要扩列)。
--   * 「材料损耗成本/电镀成本/其他外加工成本」依赖后端 compute() 写 costing_summary_result; 在那之前
--     这 3 列在视图里是 NULL → G 加价基数 = B+D (其它3项按 NULL 参与) , 前端建议把 NULL 当 0 处理。
--
-- 模板状态:
--   是 DRAFT (不是 PUBLISHED), 待用户在「核价模板配置」页面里审核 + 关联模板 + 发布。
--   不设 is_default = true, 避免与现有默认模板冲突。

DO $$
DECLARE
    v_template_id UUID := gen_random_uuid();
    v_series_id   UUID := gen_random_uuid();
BEGIN
    -- 防重复: 同名模板已存在则跳过
    IF EXISTS (SELECT 1 FROM costing_template WHERE name = '核价Excel视图模板（完整公式版）') THEN
        RAISE NOTICE 'V83: 「核价Excel视图模板（完整公式版）」已存在, 跳过插入';
        RETURN;
    END IF;

    INSERT INTO costing_template (
        id, series_id, name, is_default, version, status, description,
        columns, referenced_variables,
        linked_template_id,
        created_at, updated_at
    ) VALUES (
        v_template_id,
        v_series_id,
        '核价Excel视图模板（完整公式版）',
        false,
        'v1.0',
        'DRAFT',
        '基于 data/template/核价系统计算公式和取值（示例）.xlsx 的全套计算公式构建。' ||
        '6 个 VARIABLE 列读 v_costing_summary_full 视图（含 NULL 占位列）+ 1 个 mat_part.unit_weight + ' ||
        '11 个 FORMULA 列在前端层算商务加价(管理/财务/利润/税)与总成本(CNY/KG, CNY/PCS, USD/KG, USD/PCS)。' ||
        '加价比例(0.8%/1.2%/5%/13%)与核价汇率(0.138)当前以字面量写死, 后续接入数据源后把对应 FORMULA ' ||
        '改成 [{BNF 路径}] 即可。详细 mapping 见 docs/templates/核价Excel模板-完整公式版-mapping.md。',
        $JSON$[
            {"col_key":"A","title":"宏丰料号","source_type":"VARIABLE","variable_path":"{hf_part_no}"},
            {"col_key":"B","title":"材料成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_cost","comparison_tag":"MATERIAL_COST"},
            {"col_key":"C","title":"材料损耗成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_loss_cost","comparison_tag":"MATERIAL_LOSS"},
            {"col_key":"D","title":"加工费","source_type":"VARIABLE","variable_path":"v_costing_summary_full.processing_cost","comparison_tag":"PROCESS_FEE"},
            {"col_key":"E","title":"电镀成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_cost","comparison_tag":"PLATING_COST"},
            {"col_key":"F","title":"其他外加工成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.other_outsource_cost","comparison_tag":"OUTSOURCE_COST"},
            {"col_key":"G","title":"加价基数","source_type":"FORMULA","formula":"=[B]+[C]+[D]+[E]+[F]"},
            {"col_key":"H","title":"管理费","source_type":"FORMULA","formula":"=[G]*0.008","comparison_tag":"MGMT_FEE"},
            {"col_key":"I","title":"财务费","source_type":"FORMULA","formula":"=[G]*0.012","comparison_tag":"FINANCE_FEE"},
            {"col_key":"J","title":"利润","source_type":"FORMULA","formula":"=[G]*0.05","comparison_tag":"PROFIT"},
            {"col_key":"K","title":"税费","source_type":"FORMULA","formula":"=[G]*0.13","comparison_tag":"TAX"},
            {"col_key":"L","title":"总成本(CNY/KG)","source_type":"FORMULA","formula":"=[G]+[H]+[I]+[J]+[K]","comparison_tag":"TOTAL_CNY_KG"},
            {"col_key":"M","title":"单重(g/pcs)","source_type":"VARIABLE","variable_path":"mat_part.unit_weight"},
            {"col_key":"N","title":"总成本(CNY/PCS)","source_type":"FORMULA","formula":"=[L]/1000/[M]","comparison_tag":"TOTAL_CNY_PCS"},
            {"col_key":"O","title":"核价汇率","source_type":"FORMULA","formula":"=0.138"},
            {"col_key":"P","title":"总成本(USD/KG)","source_type":"FORMULA","formula":"=[L]*[O]","comparison_tag":"TOTAL_USD_KG"},
            {"col_key":"Q","title":"总成本(USD/PCS)","source_type":"FORMULA","formula":"=[P]/1000/[M]","comparison_tag":"TOTAL_USD_PCS"},
            {"col_key":"R","title":"报价币种","source_type":"VARIABLE","variable_path":"v_costing_summary_full.quote_currency"},
            {"col_key":"S","title":"计量单位","source_type":"VARIABLE","variable_path":"v_costing_summary_full.weight_unit"}
        ]$JSON$::jsonb,
        $JSON$[
            "{hf_part_no}",
            "v_costing_summary_full.material_cost",
            "v_costing_summary_full.material_loss_cost",
            "v_costing_summary_full.processing_cost",
            "v_costing_summary_full.plating_cost",
            "v_costing_summary_full.other_outsource_cost",
            "mat_part.unit_weight",
            "v_costing_summary_full.quote_currency",
            "v_costing_summary_full.weight_unit"
        ]$JSON$::jsonb,
        NULL,  -- linked_template_id 暂留空, 由用户在 UI 中按需关联到具体的「核价模板」(template.id)
        now(),
        now()
    );

    RAISE NOTICE 'V83: 已创建「核价Excel视图模板（完整公式版）」 id=% series=%', v_template_id, v_series_id;
END $$;
