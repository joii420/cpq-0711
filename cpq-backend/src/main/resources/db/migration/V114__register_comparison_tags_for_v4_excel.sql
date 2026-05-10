-- V114: 注册「核价 vs 报价」比对视图所需的 13 个业务标签
--
-- 起因:
--   V96 创建「核价 Excel 视图模板（完整公式版）」时, 列定义里直接打了 comparison_tag code:
--     B=MATERIAL_COST, C=MATERIAL_LOSS, D=PROCESS_FEE, E=PLATING_COST, F=OUTSOURCE_COST,
--     I=MGMT_FEE, K=FINANCE_FEE, M=PROFIT, O=TAX,
--     P=TOTAL_CNY_KG, R=TOTAL_CNY_PCS, T=TOTAL_USD_KG, U=TOTAL_USD_PCS
--   但 comparison_tag 表里这些 code 全部没注册. 后果:
--     1) CostingSheetService.buildComparison 用 tagLabelMap.getOrDefault(tag, tag)
--        → UI 显示 "MATERIAL_COST" 而不是 "材料成本"
--     2) tagToGroup.getOrDefault(tag, "其他") → 全部归入 "其他" 组, 失去分组
--
-- 方案:
--   按用户图列出的比对维度 + 现有 Excel 模板列 tag 配置, 注册 13 个标签.
--   分 3 组: 成本明细(5项) / 加价(4项) / 总成本(4项).
--   旧的 Ag/Cu/UNIT_TOTAL_COST/TOTAL 等保留不动 (buildComparison 还在用 UNIT_TOTAL_COST/TOTAL 算 summary).
--
-- 渲染入口:
--   报价单 → 切到「比对视图」→ ComparisonView.tsx 调 GET /costing-sheets/comparison?quotationId=
--   后端 buildComparison 按 col.comparison_tag 从 CostingSheet.rows 里 SUM 对应单元格,
--   生成 tagGroups: [{groupName: '成本明细', tags: [...]}, ...]

INSERT INTO comparison_tag (code, label, group_name, group_sort_order, tag_sort_order, is_builtin, status, description)
VALUES
    -- 成本明细组 (5 项, 与 Excel 模板列 B/C/D/E/F 对应)
    ('MATERIAL_COST',   '材料成本',         '成本明细', 10, 10, true, 'ACTIVE',
     '料号材料成本汇总. 来源 Excel 模板列 B = 纯材料 + 来料加工费 + 来料其他费用 - 回收成本'),
    ('MATERIAL_LOSS',   '材料损耗成本',     '成本明细', 10, 20, true, 'ACTIVE',
     '料号材料损耗成本. 来源 Excel 模板列 C = ∑(BOM × 来料损耗率 × 价格) + ∑(固定损耗 × 价格)'),
    ('PROCESS_FEE',     '加工费',           '成本明细', 10, 30, true, 'ACTIVE',
     '料号加工费. 来源 Excel 模板列 D = ∑(各工序成本 × (1+不良率))'),
    ('PLATING_COST',    '电镀成本',         '成本明细', 10, 40, true, 'ACTIVE',
     '料号电镀成本. 来源 Excel 模板列 E = (电镀加工费 + 电镀材料费) × (1+不良率)'),
    ('OUTSOURCE_COST',  '其他外加工成本',   '成本明细', 10, 50, true, 'ACTIVE',
     '其他外加工成本. 来源 Excel 模板列 F (当前占位 0, 后续业务扩展)'),

    -- 加价组 (4 项, 与 Excel 模板列 I/K/M/O 对应)
    ('MGMT_FEE',        '管理费',           '加价',     20, 10, true, 'ACTIVE',
     '管理费. 来源 Excel 模板列 I = 加价基数(B+C+D+E+F) × 管理费比例'),
    ('FINANCE_FEE',     '财务费',           '加价',     20, 20, true, 'ACTIVE',
     '财务费. 来源 Excel 模板列 K = 加价基数 × 财务费比例'),
    ('PROFIT',          '利润',             '加价',     20, 30, true, 'ACTIVE',
     '利润. 来源 Excel 模板列 M = 加价基数 × 利润比例'),
    ('TAX',             '税费',             '加价',     20, 40, true, 'ACTIVE',
     '税费. 来源 Excel 模板列 O = 加价基数 × 税费比例'),

    -- 总成本组 (4 项, 与 Excel 模板列 P/R/T/U 对应)
    ('TOTAL_CNY_KG',    '总成本(CNY/KG)',   '总成本',   30, 10, true, 'ACTIVE',
     '总成本 CNY/KG. 来源 Excel 模板列 P = 加价基数 + 管理费 + 财务费 + 利润 + 税费'),
    ('TOTAL_CNY_PCS',   '总成本(CNY/PCS)',  '总成本',   30, 20, true, 'ACTIVE',
     '总成本 CNY/PCS. 来源 Excel 模板列 R = P / 1000 / 单重(g/pcs)'),
    ('TOTAL_USD_KG',    '总成本(USD/KG)',   '总成本',   30, 30, true, 'ACTIVE',
     '总成本 USD/KG. 来源 Excel 模板列 T = P × 全局变量[CNY→USD 汇率]'),
    ('TOTAL_USD_PCS',   '总成本(USD/PCS)',  '总成本',   30, 40, true, 'ACTIVE',
     '总成本 USD/PCS. 来源 Excel 模板列 U = T / 1000 / 单重')
ON CONFLICT (code) DO UPDATE SET
    label             = EXCLUDED.label,
    group_name        = EXCLUDED.group_name,
    group_sort_order  = EXCLUDED.group_sort_order,
    tag_sort_order    = EXCLUDED.tag_sort_order,
    is_builtin        = EXCLUDED.is_builtin,
    status            = EXCLUDED.status,
    description       = EXCLUDED.description,
    updated_at        = now();

-- 旧的 (V0 时期) 单元素细分 tag 不再被 Excel 模板列引用, 归档以便比对视图不出现陈旧条目;
-- buildComparison 仍引用的 UNIT_TOTAL_COST / TOTAL 保留 ACTIVE
UPDATE comparison_tag
SET status     = 'ARCHIVED',
    updated_at = now()
WHERE code IN ('MATERIAL_COST_AG', 'MATERIAL_COST_CU', 'MATERIAL_COST_TOTAL',
               'PROCESSING_COST', 'LABOR_COST', 'SETUP_COST',
               'OVERHEAD_COST', 'PACKAGING_COST', 'CUSTOM_COST');
