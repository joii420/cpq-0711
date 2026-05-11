-- ============================================================
-- V149: 视图列标签字典 (variable_label)
--
-- 与 V104 区别:
--   - V104 global_variable_definition 粒度=整表 (LOOKUP_TABLE/SCALAR),
--     用于公式 @CODE[k=v] 查表函数 (例 @ELEM_PRICE[element_code='Cu']).
--   - 本表 variable_label 粒度=单列, 用于 Excel 视图列编辑器和公式
--     [col_key] 引用时的中文友好显示 (例 v_c_summary_agg.packaging_fee
--     → "包装材料费源").
--
-- 策略: 渐进式注册. 不一次性回填全部 397 视图列, 只先种子已被 Excel 模板
--       引用过的 22 条 (V144 之前已配好的中文映射). 未注册字段在前端选择器
--       中回退到 raw path, 并提示用户「为此字段命名」.
-- ============================================================

CREATE TABLE IF NOT EXISTS variable_label (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    variable_path   VARCHAR(200) NOT NULL UNIQUE,
    display_name    VARCHAR(100) NOT NULL,
    category        VARCHAR(50)  NOT NULL,
    data_type       VARCHAR(20),
    unit            VARCHAR(20),
    description     TEXT,
    example_value   VARCHAR(100),
    source_type     VARCHAR(20) NOT NULL DEFAULT 'VIEW_COLUMN',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID
);

CREATE INDEX IF NOT EXISTS idx_variable_label_category
    ON variable_label(category, status);
CREATE INDEX IF NOT EXISTS idx_variable_label_status
    ON variable_label(status);

COMMENT ON TABLE variable_label IS
    'V149: 视图列中文标签字典. Excel 列编辑器/公式 [col_key] 引用的 SSOT. '
    '渐进式注册: 未命名字段前端回退到 raw path 并引导用户起名.';

COMMENT ON COLUMN variable_label.variable_path IS
    '视图列路径, 形如 v_c_summary_agg.packaging_fee. 全局唯一.';
COMMENT ON COLUMN variable_label.category IS
    '业务分类, 当前 5 类: 成本汇总 / 费用比率 / 物料属性 / 单位标签 / 汇率';
COMMENT ON COLUMN variable_label.data_type IS
    'DECIMAL / INTEGER / PERCENT / STRING / DATE, 引导前端格式化展示';
COMMENT ON COLUMN variable_label.source_type IS
    'VIEW_COLUMN (来自 SQL 视图列, 当前唯一支持) / CONSTANT / DERIVED (预留)';

-- ============================================================
-- 种子: 从 V144 excel_view_config 自动抽取的 22 条已命名映射
-- 命名来自核价 5.0 Excel 模板, 已经过业务方使用验证
-- ============================================================
INSERT INTO variable_label (variable_path, display_name, category, data_type, unit) VALUES
-- v_c_summary_agg (单零件号聚合视图)
('v_c_summary_agg.packaging_fee',           '包装材料费源',       '成本汇总', 'DECIMAL', '¥'),
('v_c_summary_agg.incoming_fixed_fee',      '来料其他固定费用',   '成本汇总', 'DECIMAL', '¥'),
('v_c_summary_agg.outsource_fee',           '外加工成本源',       '成本汇总', 'DECIMAL', '¥'),
('v_c_summary_agg.freight_fee',             '运费',               '成本汇总', 'DECIMAL', '¥'),
('v_c_summary_agg.customs_fee',             '清关费',             '成本汇总', 'DECIMAL', '¥'),
('v_c_summary_agg.currency_label',          '币种',               '单位标签', 'STRING',  NULL),
('v_c_summary_agg.weight_unit_label',       '计量单位',           '单位标签', 'STRING',  NULL),
-- v_costing_summary_full (跨零件号总览视图)
('v_costing_summary_full.pure_material_cost',     '纯材料成本',         '成本汇总', 'DECIMAL', '¥'),
('v_costing_summary_full.recycle_cost',           '回收成本',           '成本汇总', 'DECIMAL', '¥'),
('v_costing_summary_full.material_loss_cost',     '材料损耗成本源',     '成本汇总', 'DECIMAL', '¥'),
('v_costing_summary_full.incoming_process_fee',   '来料加工费',         '成本汇总', 'DECIMAL', '¥'),
('v_costing_summary_full.incoming_other_fee',     '来料其他比例费用',   '成本汇总', 'DECIMAL', '¥'),
('v_costing_summary_full.process_fee_total',      '加工费源',           '成本汇总', 'DECIMAL', '¥'),
('v_costing_summary_full.plating_process_fee',    '电镀加工费',         '成本汇总', 'DECIMAL', '¥'),
('v_costing_summary_full.plating_material_fee',   '电镀材料费',         '成本汇总', 'DECIMAL', '¥'),
('v_costing_summary_full.plating_defect_rate',    '电镀不良率',         '费用比率', 'PERCENT', '%'),
('v_costing_summary_full.mgmt_fee_ratio',         '管理费比例',         '费用比率', 'PERCENT', '%'),
('v_costing_summary_full.finance_fee_ratio',      '财务费比例',         '费用比率', 'PERCENT', '%'),
('v_costing_summary_full.profit_ratio',           '利润比例',           '费用比率', 'PERCENT', '%'),
('v_costing_summary_full.tax_ratio',              '税费比例',           '费用比率', 'PERCENT', '%'),
('v_costing_summary_full.exchange_rate_to_usd',   '核价汇率(CNY到USD)', '汇率',     'DECIMAL', NULL),
('v_costing_summary_full.unit_weight_g',          '单重(g/pcs)',        '物料属性', 'DECIMAL', 'g')
ON CONFLICT (variable_path) DO NOTHING;

DO $$
DECLARE v_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt FROM variable_label WHERE status = 'ACTIVE';
    RAISE NOTICE 'V149 OK: variable_label 创建完毕, 当前 ACTIVE 条目数=%', v_cnt;
END $$;
