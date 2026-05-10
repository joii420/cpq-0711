-- V80: 核价汇总视图 + 注册 basic_data_config sheet + 配置默认核价 Excel 模板的 23 列
--
-- 目的:
--   1) 让"报价单 → 核价单 → Excel 视图"按导入的「核价Excel模板·汇总页」结构展示
--   2) BNF 自动按 lineItem 的 productPartNo 注入 hf_part_no -> 视图筛选当前料号
--   3) 同步插入一条演示 summary（hf_part_no='3100080003'），让 demo 立即可见
--
-- 设计要点:
--   * compute() 当前产出 7 个 metric (MATERIAL_COST/PROCESS_FEE/TOOLING_FEE/DESIGN_COST/...)
--     与 Excel "汇总" 9 列 (材料/损耗/加工/管理/财务/利润/税费/电镀/其他) 只有材料&加工对齐;
--     未实现的商务加价列以 NULL 占位,等 compute() 升级后自动有值
--   * 视图带 hf_part_no 列 -> ImplicitJoinRewriter 自动按料号注入
--   * 同一 linked_template_id 内 is_default 唯一,沿用现有"核价模板2" (id 0a8441c0) 改名+换 columns

-- ========================================================
-- 1) v_costing_summary_full 视图
--    每料号 × summary 一行;9 个成本 metric PIVOT 横向; 3 个版本号; 是否生效
-- ========================================================
CREATE OR REPLACE VIEW v_costing_summary_full AS
WITH agg AS (
    SELECT
        s.id,
        s.summary_no,
        s.hf_part_no,
        s.status,
        s.quote_currency,
        s.notes,
        s.element_version_id,
        s.material_version_id,
        s.exchange_version_id,
        MAX(CASE WHEN r.metric_code = 'MATERIAL_COST' THEN r.value END) AS material_cost,
        MAX(CASE WHEN r.metric_code = 'PROCESS_FEE'   THEN r.value END) AS processing_cost
    FROM costing_summary s
    LEFT JOIN costing_summary_result r ON r.summary_id = s.id
    GROUP BY s.id, s.summary_no, s.hf_part_no, s.status, s.quote_currency, s.notes,
             s.element_version_id, s.material_version_id, s.exchange_version_id
)
SELECT
    a.id                                                                       AS summary_id,
    a.summary_no,
    a.hf_part_no,
    (ROW_NUMBER() OVER (PARTITION BY a.hf_part_no ORDER BY a.summary_no))::int AS line_seq,
    a.status,
    CASE a.status WHEN 'PUBLISHED' THEN '是' ELSE '否' END                       AS is_published_label,
    a.quote_currency,
    'KG'::varchar(10)                                                          AS weight_unit,
    ev.version_number                                                          AS element_version_number,
    mv.version_number                                                          AS material_version_number,
    xv.version_number                                                          AS exchange_version_number,
    a.material_cost,
    CAST(NULL AS numeric)                                                      AS material_loss_cost,
    a.processing_cost,
    CAST(NULL AS numeric)                                                      AS management_cost,
    CAST(NULL AS numeric)                                                      AS finance_cost,
    CAST(NULL AS numeric)                                                      AS profit,
    CAST(NULL AS numeric)                                                      AS tax,
    CAST(NULL AS numeric)                                                      AS plating_cost,
    CAST(NULL AS numeric)                                                      AS other_outsource_cost
FROM agg a
LEFT JOIN costing_price_version ev ON ev.id = a.element_version_id
LEFT JOIN costing_price_version mv ON mv.id = a.material_version_id
LEFT JOIN costing_price_version xv ON xv.id = a.exchange_version_id;

COMMENT ON VIEW v_costing_summary_full IS
    '核价汇总视图: 每料号x summary 一行,9 个成本指标 PIVOT 横向 + 3 个版本号 + 是否生效。BNF 路径自动按 lineItem 的 productPartNo 注入 hf_part_no。Excel 视图「汇总」模板从此视图取数;未实现 metric 暂以 NULL 占位。';

-- ========================================================
-- 2) basic_data_config: 注册新 sheet「核价汇总」
--    template_kind='COSTING' -> PathPickerDrawer 选"核价模板"时可见
-- ========================================================
INSERT INTO basic_data_config (
    id, sheet_name, sheet_index, header_row_index, data_start_row_index,
    description, parent_config_id, join_columns, sort_order, status,
    target_table, template_kind, created_at, updated_at
)
SELECT
    gen_random_uuid(), '核价汇总', 200, 1, 2,
    '核价汇总: 每料号 x summary 一行,9 个成本指标 PIVOT 横向。视图: v_costing_summary_full',
    NULL, '[]'::jsonb, 200, 'ACTIVE',
    'v_costing_summary_full', 'COSTING', now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM basic_data_config WHERE sheet_name = '核价汇总' AND status = 'ACTIVE'
);

-- 22 个 attribute (U 列总成本是 FORMULA,不写入 attribute)
INSERT INTO basic_data_attribute (
    id, config_id, column_letter, column_title, variable_code, variable_label,
    data_type, status, sort_order, importance_level, affects_calculation, is_required,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), c.id, x.col_letter, x.col_title, x.var_code, x.var_label,
    x.data_type, 'ACTIVE', x.sort_order, 'NORMAL', false, false,
    now(), now()
FROM basic_data_config c
CROSS JOIN (VALUES
    ('A', '宏丰料号',       'hf_part_no',                '宏丰料号',                  'IDENTIFIER', 1),
    ('B', '品名',           'product_name',              '品名',                      'VALUE',       2),
    ('C', '规格',           'specification',             '规格',                      'VALUE',       3),
    ('D', '尺寸',           'size_info',                 '尺寸',                      'VALUE',       4),
    ('E', '项次',           'line_seq',                  '项次',                      'VALUE',       5),
    ('F', '核价版本编号',   'summary_no',                '核价版本编号',              'IDENTIFIER',  6),
    -- G 与 H 物理上指向同一视图字段 element_version_number,但 variable_code 必须唯一
    -- (uq_bda_config_var on (config_id, variable_code)),所以 G 用别名 element_version_label
    ('G', '核价版本名称',   'element_version_label',     '核价版本名称',              'IDENTIFIER',  7),
    ('H', '元素价格版本',   'element_version_number',    '元素价格版本',              'IDENTIFIER',  8),
    ('I', '材料价格版本',   'material_version_number',   '材料价格版本',              'IDENTIFIER',  9),
    ('J', '汇率价格版本',   'exchange_version_number',   '汇率价格版本',              'IDENTIFIER', 10),
    ('K', '是否生效',       'is_published_label',        '是否生效',                  'VALUE',      11),
    ('L', '材料成本',       'material_cost',             '材料成本',                  'VALUE',      12),
    ('M', '材料损耗成本',   'material_loss_cost',        '材料损耗成本',              'VALUE',      13),
    ('N', '加工费',         'processing_cost',           '加工费',                    'VALUE',      14),
    ('O', '管理费',         'management_cost',           '管理费',                    'VALUE',      15),
    ('P', '财务费',         'finance_cost',              '财务费',                    'VALUE',      16),
    ('Q', '利润',           'profit',                    '利润',                      'VALUE',      17),
    ('R', '税费',           'tax',                       '税费',                      'VALUE',      18),
    ('S', '电镀成本',       'plating_cost',              '电镀成本',                  'VALUE',      19),
    ('T', '其他外加工成本', 'other_outsource_cost',      '其他外加工成本',            'VALUE',      20),
    ('V', '币种',           'quote_currency',            '币种',                      'VALUE',      21),
    ('W', '计量单位',       'weight_unit',               '计量单位',                  'VALUE',      22)
) AS x(col_letter, col_title, var_code, var_label, data_type, sort_order)
WHERE c.target_table = 'v_costing_summary_full' AND c.sheet_name = '核价汇总' AND c.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM basic_data_attribute a
      WHERE a.config_id = c.id AND a.column_letter = x.col_letter AND a.status = 'ACTIVE'
  );

-- ========================================================
-- 3) 更新现有 Excel 模板「核价模板2」(空壳示例) -> 核价汇总(演示) + 23 列
-- ========================================================
UPDATE costing_template
SET name = '核价-汇总演示模板',
    description = '按导入的「核价系统功能基础数据」Excel "汇总" 页签 1:1 配置: 每料号一行,22 个数据列 + 1 个总成本公式列。BNF 自动按 lineItem 的 productPartNo 关联 v_costing_summary_full 视图。compute() 暂未实现的商务加价列(管理费/财务费/利润/税费/电镀/其他外加工)以空白显示。',
    columns = $JSON$[
        {"col_key":"A","title":"宏丰料号","source_type":"VARIABLE","variable_path":"{hf_part_no}"},
        {"col_key":"B","title":"品名","source_type":"VARIABLE","variable_path":"{product_name}"},
        {"col_key":"C","title":"规格","source_type":"VARIABLE","variable_path":"{specification}"},
        {"col_key":"D","title":"尺寸","source_type":"VARIABLE","variable_path":"{size_info}"},
        {"col_key":"E","title":"项次","source_type":"VARIABLE","variable_path":"v_costing_summary_full.line_seq"},
        {"col_key":"F","title":"核价版本编号","source_type":"VARIABLE","variable_path":"v_costing_summary_full.summary_no"},
        {"col_key":"G","title":"核价版本名称","source_type":"VARIABLE","variable_path":"v_costing_summary_full.element_version_number"},
        {"col_key":"H","title":"元素价格版本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.element_version_number"},
        {"col_key":"I","title":"材料价格版本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_version_number"},
        {"col_key":"J","title":"汇率价格版本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.exchange_version_number"},
        {"col_key":"K","title":"是否生效","source_type":"VARIABLE","variable_path":"v_costing_summary_full.is_published_label"},
        {"col_key":"L","title":"材料成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_cost"},
        {"col_key":"M","title":"材料损耗成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.material_loss_cost"},
        {"col_key":"N","title":"加工费","source_type":"VARIABLE","variable_path":"v_costing_summary_full.processing_cost"},
        {"col_key":"O","title":"管理费","source_type":"VARIABLE","variable_path":"v_costing_summary_full.management_cost"},
        {"col_key":"P","title":"财务费","source_type":"VARIABLE","variable_path":"v_costing_summary_full.finance_cost"},
        {"col_key":"Q","title":"利润","source_type":"VARIABLE","variable_path":"v_costing_summary_full.profit"},
        {"col_key":"R","title":"税费","source_type":"VARIABLE","variable_path":"v_costing_summary_full.tax"},
        {"col_key":"S","title":"电镀成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.plating_cost"},
        {"col_key":"T","title":"其他外加工成本","source_type":"VARIABLE","variable_path":"v_costing_summary_full.other_outsource_cost"},
        {"col_key":"U","title":"总成本","source_type":"FORMULA","formula":"=[L]+[M]+[N]+[O]+[P]+[Q]+[R]+[S]+[T]"},
        {"col_key":"V","title":"币种","source_type":"VARIABLE","variable_path":"v_costing_summary_full.quote_currency"},
        {"col_key":"W","title":"计量单位","source_type":"VARIABLE","variable_path":"v_costing_summary_full.weight_unit"}
    ]$JSON$::jsonb,
    updated_at = now()
WHERE id = '0a8441c0-1f15-4d97-ba83-66cd5c880ed7';

-- ========================================================
-- 4) 演示数据: 一条 PUBLISHED summary (hf_part_no='3100080003') + 7 个 metric
--    便于报价单选了 3100080003 这料号时立即看到非空汇总
-- ========================================================
DO $$
DECLARE
    v_summary_id  UUID := gen_random_uuid();
    v_element_id  UUID;
    v_material_id UUID;
    v_exchange_id UUID;
BEGIN
    SELECT id INTO v_element_id  FROM costing_price_version
        WHERE version_kind='ELEMENT'  AND is_default = true AND status = 'PUBLISHED' LIMIT 1;
    SELECT id INTO v_material_id FROM costing_price_version
        WHERE version_kind='MATERIAL' AND is_default = true AND status = 'PUBLISHED' LIMIT 1;
    SELECT id INTO v_exchange_id FROM costing_price_version
        WHERE version_kind='EXCHANGE' AND is_default = true AND status = 'PUBLISHED' LIMIT 1;

    IF v_element_id IS NULL OR v_material_id IS NULL OR v_exchange_id IS NULL THEN
        RAISE NOTICE 'V80 demo: 缺少默认 ELEMENT/MATERIAL/EXCHANGE version, 跳过演示数据插入';
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM costing_summary WHERE summary_no = 'CS-DEMO-0001') THEN
        RAISE NOTICE 'V80 demo: 已存在 CS-DEMO-0001, 跳过';
        RETURN;
    END IF;

    INSERT INTO costing_summary (
        id, summary_no, hf_part_no,
        element_version_id, material_version_id, exchange_version_id,
        status, quote_currency, notes,
        created_at, updated_at, computed_at, published_at
    ) VALUES (
        v_summary_id, 'CS-DEMO-0001', '3100080003',
        v_element_id, v_material_id, v_exchange_id,
        'PUBLISHED', 'CNY', '汇总演示数据 (V80)',
        now(), now(), now(), now()
    );

    INSERT INTO costing_summary_result (id, summary_id, metric_code, metric_label, value, currency, sort_order)
    VALUES
      (gen_random_uuid(), v_summary_id, 'MATERIAL_COST',    '材料成本',           96,    'CNY', 1),
      (gen_random_uuid(), v_summary_id, 'PROCESS_FEE',      '加工费',              5,    'CNY', 2),
      (gen_random_uuid(), v_summary_id, 'TOOLING_FEE',      '模具工装费',          2,    'CNY', 3),
      (gen_random_uuid(), v_summary_id, 'DESIGN_COST',      '设计成本',            1,    'CNY', 4),
      (gen_random_uuid(), v_summary_id, 'UNIT_TOTAL_COST',  '单位总成本',         104,   'CNY', 5),
      (gen_random_uuid(), v_summary_id, 'UNIT_TOTAL_QUOTE', '单位总成本(报价币种)', 104,  'CNY', 6),
      (gen_random_uuid(), v_summary_id, 'UNIT_PER_PCS',     '单件成本',            0.012,'CNY', 7);
END $$;
