-- V197: 选配产品标准报价模板-单一产品 配置 Excel 视图(对齐 data/template/报价逻辑模型.xlsx)
--
-- 背景:
--   PRD 设计稿 data/template/报价逻辑模型.xlsx 给出"选配产品-单一产品"的 Excel 报价视图
--   样式: 81 列,28 个工序步骤,核心累加公式
--     本工序小计 = 上道工序小计 ÷ 本工序成材率 + 本工序费用
--   本迁移把该列结构 1:1 落到 template.excel_view_config (V150 起 Excel 视图配置直接挂模板自身).
--
-- 关键决策(2026-05-18 与用户确认):
--   1. 列结构: 完整 1:1 映射 81 列(76 个有效列, A~BW). 同名工序(正火/酸洗/冷拔/退火)
--      通过 process_code 后缀(_1/_2/_3...)区分,mat_process 表需相应 seed 这些工序码.
--   2. 成材率: DB 保持百分数(0-100)不动, Excel 公式带 /100 校正:
--      小计 = [上道] / ([成材率] / 100) + [费用]    -- 等价 [上道] * 100 / [成材率] + [费用]
--   3. 模板定位: 按 name='选配产品标准报价模板-单一产品' 取最新版(version DESC LIMIT 1),
--      兼容 V183 v1.0 (b1d2e3f4...164) 与 UI 后续派生的 v1.2 (af4a834f...).
--
-- 列字典(对照 Excel 列):
--   A: 客户名称        B: 成品规格            C: 工序           D: 原材料
--   E_RAW(隐藏): 含税价   E: 不含税价(=E_RAW/1.13)
--   F~BQ: 28 个工序(费用/成材率/小计)
--   BR: 营运成本(12%)=BM*0.12     BS: 运费(input)
--   BT: 废钢收入=(1-G%*N%*U%*AB%*BJ%)*2900     BU: 单位成本总计=BM+BR+BS-BT
--   BV: 底线利润(input)            BW: 底线报价=(BU+BV)*1.13
--
-- 自检:
--   - PRE_COUNT: 找到的"选配产品标准报价模板-单一产品" 行数(>=1)
--   - POST_COUNT: excel_view_config 列数 = 76
--   - 关键公式探针: H, O, BT, BW

DO $main$
DECLARE
    v_template_id UUID;
    v_template_name TEXT := '选配产品标准报价模板-单一产品';
    v_pre_count INT;
    v_columns JSONB;
    v_col_count INT;
    v_formula_H TEXT;
    v_formula_O TEXT;
    v_formula_BT TEXT;
    v_formula_BW TEXT;
BEGIN
    -- 1. 取最新版本
    SELECT COUNT(*) INTO v_pre_count FROM template WHERE name = v_template_name;
    IF v_pre_count = 0 THEN
        RAISE EXCEPTION 'V197: 未找到模板 % (PRE_COUNT=0,请确认 V183 已执行)', v_template_name;
    END IF;

    SELECT id INTO v_template_id
    FROM template
    WHERE name = v_template_name
    ORDER BY
        CASE status WHEN 'PUBLISHED' THEN 1 WHEN 'DRAFT' THEN 2 ELSE 9 END,
        version DESC
    LIMIT 1;

    RAISE NOTICE 'V197: 找到模板 % 行,选中 id=% 作为最新版', v_pre_count, v_template_id;

    -- 2. 写入 Excel 视图列配置
    v_columns := $json$[{"col_key":"A","title":"客户名称","source_type":"VARIABLE","variable_path":"{customer_name}"},{"col_key":"B","title":"成品规格","source_type":"VARIABLE","variable_path":"{specification}"},{"col_key":"C","title":"工序","source_type":"VARIABLE","variable_path":"mat_process.process_code"},{"col_key":"D","title":"原材料","source_type":"VARIABLE","variable_path":"mat_bom.input_part_no"},{"col_key":"E_RAW","title":"原材料含税价(暂存)","source_type":"VARIABLE","variable_path":"mat_part.unit_price","hidden":true},{"col_key":"E","title":"原材料价格（不含税）","source_type":"FORMULA","formula":"[E_RAW] / 1.13"},{"col_key":"F","title":"分条-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='分条'].unit_cost"},{"col_key":"G","title":"分条-成材率(%)","source_type":"VARIABLE","variable_path":"mat_process[process_code='分条'].yield_rate"},{"col_key":"H","title":"分条-小计","source_type":"FORMULA","formula":"[E] / ([G] / 100) + [F]"},{"col_key":"I","title":"冷轧(委外)-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷轧'].unit_cost"},{"col_key":"J","title":"冷轧(委外)-小计","source_type":"FORMULA","formula":"[H] + [I]"},{"col_key":"K","title":"穿孔(委外)-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='穿孔'].unit_cost"},{"col_key":"L","title":"穿孔(委外)-小计","source_type":"FORMULA","formula":"[J] + [K]"},{"col_key":"M","title":"焊接-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='焊接'].unit_cost"},{"col_key":"N","title":"焊接-成材率(%)","source_type":"VARIABLE","variable_path":"mat_process[process_code='焊接'].yield_rate"},{"col_key":"O","title":"焊接-小计","source_type":"FORMULA","formula":"[H] / ([N] / 100) + [M]"},{"col_key":"P","title":"正火/退火-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='正火_1'].unit_cost"},{"col_key":"Q","title":"正火/退火-小计","source_type":"FORMULA","formula":"[O] + [P]"},{"col_key":"R","title":"酸洗-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='酸洗_1'].unit_cost"},{"col_key":"S","title":"酸洗-小计","source_type":"FORMULA","formula":"[Q] + [R]"},{"col_key":"T","title":"冷拔-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷拔_1'].unit_cost"},{"col_key":"U","title":"冷拔-成材率(%)","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷拔_1'].yield_rate"},{"col_key":"V","title":"冷拔-小计","source_type":"FORMULA","formula":"[S] / ([U] / 100) + [T]"},{"col_key":"W","title":"正火/退火-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='正火_2'].unit_cost"},{"col_key":"X","title":"正火/退火-小计","source_type":"FORMULA","formula":"[V] + [W]"},{"col_key":"Y","title":"酸洗-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='酸洗_2'].unit_cost"},{"col_key":"Z","title":"酸洗-小计","source_type":"FORMULA","formula":"[X] + [Y]"},{"col_key":"AA","title":"冷拔-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷拔_2'].unit_cost"},{"col_key":"AB","title":"冷拔-成材率(%)","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷拔_2'].yield_rate"},{"col_key":"AC","title":"冷拔-小计","source_type":"FORMULA","formula":"[Z] / ([AB] / 100) + [AA]"},{"col_key":"AD","title":"长管质量处理-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='长管质量处理'].unit_cost"},{"col_key":"AE","title":"长管质量处理-小计","source_type":"FORMULA","formula":"[AC] + [AD]"},{"col_key":"AF","title":"正火-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='正火_3'].unit_cost"},{"col_key":"AG","title":"正火-小计","source_type":"FORMULA","formula":"[AE] + [AF]"},{"col_key":"AH","title":"酸洗-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='酸洗_3'].unit_cost"},{"col_key":"AI","title":"酸洗-小计","source_type":"FORMULA","formula":"[AG] + [AH]"},{"col_key":"AJ","title":"退火-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='退火_1'].unit_cost"},{"col_key":"AK","title":"退火-小计","source_type":"FORMULA","formula":"[AI] + [AJ]"},{"col_key":"AL","title":"酸洗-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='酸洗_4'].unit_cost"},{"col_key":"AM","title":"酸洗-小计","source_type":"FORMULA","formula":"[AK] + [AL]"},{"col_key":"AN","title":"冷拔-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷拔_3'].unit_cost"},{"col_key":"AO","title":"冷拔-成材率(%)","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷拔_3'].yield_rate"},{"col_key":"AP","title":"冷拔-小计","source_type":"FORMULA","formula":"[AM] / ([AO] / 100) + [AN]"},{"col_key":"AQ","title":"退火-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='退火_2'].unit_cost"},{"col_key":"AR","title":"退火-小计","source_type":"FORMULA","formula":"[AP] + [AQ]"},{"col_key":"AS","title":"酸洗-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='酸洗_5'].unit_cost"},{"col_key":"AT","title":"酸洗-小计","source_type":"FORMULA","formula":"[AR] + [AS]"},{"col_key":"AU","title":"冷拔-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷拔_4'].unit_cost"},{"col_key":"AV","title":"冷拔-成材率(%)","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷拔_4'].yield_rate"},{"col_key":"AW","title":"冷拔-小计","source_type":"FORMULA","formula":"[AT] / ([AV] / 100) + [AU]"},{"col_key":"AX","title":"退火-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='退火_3'].unit_cost"},{"col_key":"AY","title":"退火-小计","source_type":"FORMULA","formula":"[AW] + [AX]"},{"col_key":"AZ","title":"酸洗-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='酸洗_6'].unit_cost"},{"col_key":"BA","title":"酸洗-小计","source_type":"FORMULA","formula":"[AY] + [AZ]"},{"col_key":"BB","title":"冷拔-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷拔_5'].unit_cost"},{"col_key":"BC","title":"冷拔-成材率(%)","source_type":"VARIABLE","variable_path":"mat_process[process_code='冷拔_5'].yield_rate"},{"col_key":"BD","title":"冷拔-小计","source_type":"FORMULA","formula":"[BA] / ([BC] / 100) + [BB]"},{"col_key":"BE","title":"退火-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='退火_4'].unit_cost"},{"col_key":"BF","title":"退火-小计","source_type":"FORMULA","formula":"[BD] + [BE]"},{"col_key":"BG","title":"酸洗-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='酸洗_7'].unit_cost"},{"col_key":"BH","title":"酸洗-小计","source_type":"FORMULA","formula":"[BF] + [BG]"},{"col_key":"BI","title":"切短管-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='切短管'].unit_cost"},{"col_key":"BJ","title":"切短管-成材率(%)","source_type":"VARIABLE","variable_path":"mat_process[process_code='切短管'].yield_rate"},{"col_key":"BK","title":"切短管-小计","source_type":"FORMULA","formula":"[AE] / ([BJ] / 100) + [BI]"},{"col_key":"BL","title":"短管质量处理-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='短管质量处理'].unit_cost"},{"col_key":"BM","title":"短管质量处理-小计","source_type":"FORMULA","formula":"[BK] + [BL]"},{"col_key":"BN","title":"外协工序1-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='外协工序1'].unit_cost"},{"col_key":"BO","title":"外协工序1-小计","source_type":"FORMULA","formula":"[BM] + [BN]"},{"col_key":"BP","title":"外协工序2-费用","source_type":"VARIABLE","variable_path":"mat_process[process_code='外协工序2'].unit_cost"},{"col_key":"BQ","title":"外协工序2-小计","source_type":"FORMULA","formula":"[BO] + [BP]"},{"col_key":"BR","title":"营运成本（12%）","source_type":"FORMULA","formula":"[BM] * 0.12"},{"col_key":"BS","title":"运费","source_type":"VARIABLE","variable_path":"mat_process[process_code='运费'].unit_cost"},{"col_key":"BT","title":"废钢收入","source_type":"FORMULA","formula":"(1 - ([G] / 100) * ([N] / 100) * ([U] / 100) * ([AB] / 100) * ([BJ] / 100)) * 2900"},{"col_key":"BU","title":"单位成本总计","source_type":"FORMULA","formula":"[BM] + [BR] + [BS] - [BT]"},{"col_key":"BV","title":"底线利润","source_type":"VARIABLE","variable_path":"mat_process[process_code='底线利润'].unit_cost"},{"col_key":"BW","title":"底线报价","source_type":"FORMULA","formula":"([BU] + [BV]) * 1.13"}]$json$::jsonb;

    UPDATE template
    SET excel_view_config = v_columns,
        updated_at = NOW()
    WHERE id = v_template_id;

    -- 同步更新同名所有版本(便于历史报价单复现)
    UPDATE template
    SET excel_view_config = v_columns,
        updated_at = NOW()
    WHERE name = v_template_name
      AND id <> v_template_id;

    -- 3. 自检
    SELECT jsonb_array_length(excel_view_config) INTO v_col_count
    FROM template WHERE id = v_template_id;
    IF v_col_count <> 76 THEN
        RAISE EXCEPTION 'V197 自检失败: excel_view_config 列数=% 期望=76', v_col_count;
    END IF;

    -- 关键公式探针
    SELECT elem->>'formula' INTO v_formula_H
    FROM template t, jsonb_array_elements(t.excel_view_config) elem
    WHERE t.id = v_template_id AND elem->>'col_key' = 'H';
    IF v_formula_H IS NULL OR v_formula_H NOT LIKE '%[E]%[G]%' THEN
        RAISE EXCEPTION 'V197 自检失败: H 列公式异常=%', v_formula_H;
    END IF;

    SELECT elem->>'formula' INTO v_formula_O
    FROM template t, jsonb_array_elements(t.excel_view_config) elem
    WHERE t.id = v_template_id AND elem->>'col_key' = 'O';
    IF v_formula_O IS NULL OR v_formula_O NOT LIKE '%[H]%[N]%' THEN
        RAISE EXCEPTION 'V197 自检失败: O 列公式异常=%', v_formula_O;
    END IF;

    SELECT elem->>'formula' INTO v_formula_BT
    FROM template t, jsonb_array_elements(t.excel_view_config) elem
    WHERE t.id = v_template_id AND elem->>'col_key' = 'BT';
    IF v_formula_BT IS NULL OR v_formula_BT NOT LIKE '%2900%' THEN
        RAISE EXCEPTION 'V197 自检失败: BT 列公式异常=%', v_formula_BT;
    END IF;

    SELECT elem->>'formula' INTO v_formula_BW
    FROM template t, jsonb_array_elements(t.excel_view_config) elem
    WHERE t.id = v_template_id AND elem->>'col_key' = 'BW';
    IF v_formula_BW IS NULL OR v_formula_BW NOT LIKE '%1.13%' THEN
        RAISE EXCEPTION 'V197 自检失败: BW 列公式异常=%', v_formula_BW;
    END IF;

    RAISE NOTICE 'V197 自检通过: 模板 % 已配置 76 列 Excel 视图, 累加公式 = 上道小计/(成材率/100)+费用', v_template_name;
END
$main$;
