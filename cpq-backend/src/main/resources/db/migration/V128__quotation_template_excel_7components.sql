-- ============================================================
-- V128: 报价模板 Excel 基础结构 — 7 个合并组件 + QUOTATION 通用模板
-- ============================================================
-- 背景:
--   根据 data/template/报价系统功能基础数据功能结构所需字段（1.0版）.xlsx
--   把 Excel 的多个 sheet 收敛为 7 个组件，每个组件多源 sheet 用 UNION ALL
--   视图纵向叠加 + 列并集（NULL 填充无关列）。视图名前缀 v_q_*_merged。
--
-- 7 个组件清单:
--   1. 料件        COMP-QX-PART-INFO   v_q_part_info_merged     单行(每料号)
--   2. 来料        COMP-QX-INCOMING    v_q_incoming_merged      4 来源 UNION ALL
--   3. 元素        COMP-QX-ELEMENT     v_q_element_merged       2 来源 UNION ALL
--   4. 成品        COMP-QX-FINISHED    v_q_finished_merged      2 来源 UNION ALL
--   5. 组成件      COMP-QX-COMPONENT   v_q_component_merged     单源 mat_process
--   6. 组装加工    COMP-QX-ASSEMBLY    v_q_assembly_merged      单源 mat_fee[ASSEMBLY_PROCESS]
--   7. 电镀        COMP-QX-PLATING     v_q_plating_merged       2 来源 UNION ALL
--
-- 约束扩展:
--   mat_fee.fee_type 新增 'ELEMENT_RECYCLE'
--
-- 自检要点:
--   - 每个视图 SELECT COUNT(*) 不报错（允许 0 行，无数据场景正常）
--   - 组件 code 冲突走 ON CONFLICT DO UPDATE 幂等
--   - 模板名冲突检查跳过
--   - DO $$ 末尾 RAISE NOTICE 报告行数/组件数/模板数
-- ============================================================

-- ════════════════════════════════════════════════════════════════════════════
-- 1. mat_fee.fee_type CHECK 约束扩展: 新增 ELEMENT_RECYCLE
-- ════════════════════════════════════════════════════════════════════════════
ALTER TABLE mat_fee DROP CONSTRAINT IF EXISTS chk_mat_fee_type;
ALTER TABLE mat_fee ADD CONSTRAINT chk_mat_fee_type CHECK (fee_type IN (
    'INCOMING_FIXED',
    'INCOMING_OTHER',
    'FINISHED_FIXED',
    'FINISHED_OTHER',
    'ASSEMBLY_PROCESS',
    'INCOMING_ANNUAL_DOWN',
    'ASSEMBLY_ANNUAL_DOWN',
    'ANNUAL_REDUCTION_FACTOR',
    'MATERIAL_RECYCLE',     -- V118
    'ELEMENT_RECYCLE'       -- V128
));

-- ════════════════════════════════════════════════════════════════════════════
-- 2. 新建目录「报价模板组件V3-Excel结构」
-- ════════════════════════════════════════════════════════════════════════════
INSERT INTO component_directory (id, name, parent_id, sort_order, created_at)
SELECT 'c1d2e3f4-0003-4003-8003-000000000003'::uuid, '报价模板组件V3-Excel结构', NULL, 82, now()
WHERE NOT EXISTS (
    SELECT 1 FROM component_directory
    WHERE id = 'c1d2e3f4-0003-4003-8003-000000000003'::uuid
       OR name = '报价模板组件V3-Excel结构'
);

-- ════════════════════════════════════════════════════════════════════════════
-- 3. 创建 7 个合并视图
-- ════════════════════════════════════════════════════════════════════════════

-- ── 3.1 v_q_part_info_merged — 料件（单行，客户料号关系 + 单重 + 汇率）
CREATE OR REPLACE VIEW v_q_part_info_merged AS
SELECT
    'PART'::VARCHAR                    AS source_type,
    m.hf_part_no,
    m.customer_part_name,
    m.customer_product_no,
    m.customer_drawing_no,
    m.payment_method,
    m.base_currency,
    m.quote_currency,
    er.rate                            AS exchange_rate,
    p.unit_weight,
    p.weight_unit
FROM mat_customer_part_mapping m
LEFT JOIN mat_part p ON p.part_no = m.hf_part_no
LEFT JOIN exchange_rate er
    ON  er.customer_id   = m.customer_id
    AND er.from_currency = m.base_currency
    AND er.to_currency   = m.quote_currency
    AND er.is_current    = true;

COMMENT ON VIEW v_q_part_info_merged IS
    'V128: 料件合并视图 — mat_customer_part_mapping LEFT JOIN mat_part + exchange_rate(当前汇率)';

-- ── 3.2 v_q_incoming_merged — 来料（4 来源 UNION ALL）
CREATE OR REPLACE VIEW v_q_incoming_merged AS
-- 来源 1: 来料 BOM
SELECT
    'BOM'::VARCHAR               AS source_type,
    hf_part_no,
    seq_no,
    input_material_no,
    input_material_name,
    output_material_type,
    gross_qty,
    net_qty,
    gross_unit                   AS weight_unit,
    loss_rate,
    defect_rate,
    NULL::INT                    AS sub_seq_no,
    NULL::VARCHAR(128)           AS element_name,
    NULL::DECIMAL(18,4)          AS fee_value,
    NULL::DECIMAL(10,4)          AS fee_ratio,
    NULL::VARCHAR(8)             AS currency,
    NULL::VARCHAR(16)            AS price_unit,
    NULL::BOOLEAN                AS price_floating,
    NULL::DECIMAL(10,4)          AS settlement_rise_ratio,
    NULL::DECIMAL(18,4)          AS fixed_rise_value,
    NULL::VARCHAR(8)             AS rise_currency,
    NULL::VARCHAR(16)            AS rise_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct
FROM mat_bom
WHERE bom_type = 'INCOMING'

UNION ALL

-- 来源 2: 来料固定加工费
SELECT
    'INCOMING_FIXED'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    NULL::VARCHAR(64)            AS output_material_type,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS weight_unit,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(10,4)          AS defect_rate,
    NULL::INT                    AS sub_seq_no,
    NULL::VARCHAR(128)           AS element_name,
    fee_value,
    fee_ratio,
    currency,
    price_unit,
    price_floating,
    settlement_rise_ratio,
    fixed_rise_value,
    rise_currency,
    rise_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct
FROM mat_fee
WHERE fee_type = 'INCOMING_FIXED'
  AND is_current = true

UNION ALL

-- 来源 3: 来料其他费用
SELECT
    'INCOMING_OTHER'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    NULL::VARCHAR(64)            AS output_material_type,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS weight_unit,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(10,4)          AS defect_rate,
    dim_sub_seq_no               AS sub_seq_no,
    dim_element_name             AS element_name,
    fee_value,
    fee_ratio,
    currency,
    price_unit,
    NULL::BOOLEAN                AS price_floating,
    NULL::DECIMAL(10,4)          AS settlement_rise_ratio,
    NULL::DECIMAL(18,4)          AS fixed_rise_value,
    NULL::VARCHAR(8)             AS rise_currency,
    NULL::VARCHAR(16)            AS rise_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct
FROM mat_fee
WHERE fee_type = 'INCOMING_OTHER'
  AND is_current = true

UNION ALL

-- 来源 4: 来料回收折扣（fee_ratio → recycle_pct）
SELECT
    'MATERIAL_RECYCLE'::VARCHAR  AS source_type,
    hf_part_no,
    seq_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    NULL::VARCHAR(64)            AS output_material_type,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS weight_unit,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(10,4)          AS defect_rate,
    NULL::INT                    AS sub_seq_no,
    NULL::VARCHAR(128)           AS element_name,
    NULL::DECIMAL(18,4)          AS fee_value,
    NULL::DECIMAL(10,4)          AS fee_ratio,
    NULL::VARCHAR(8)             AS currency,
    NULL::VARCHAR(16)            AS price_unit,
    NULL::BOOLEAN                AS price_floating,
    NULL::DECIMAL(10,4)          AS settlement_rise_ratio,
    NULL::DECIMAL(18,4)          AS fixed_rise_value,
    NULL::VARCHAR(8)             AS rise_currency,
    NULL::VARCHAR(16)            AS rise_unit,
    fee_ratio                    AS recycle_pct
FROM mat_fee
WHERE fee_type = 'MATERIAL_RECYCLE'
  AND is_current = true;

COMMENT ON VIEW v_q_incoming_merged IS
    'V128: 来料合并视图 — mat_bom[INCOMING] + mat_fee[INCOMING_FIXED/INCOMING_OTHER/MATERIAL_RECYCLE] UNION ALL';

-- ── 3.3 v_q_element_merged — 元素（2 来源 UNION ALL）
CREATE OR REPLACE VIEW v_q_element_merged AS
-- 来源 1: 元素 BOM
SELECT
    'BOM'::VARCHAR               AS source_type,
    hf_part_no,
    input_material_no,
    input_material_name,
    seq_no,
    element_name,
    composition_pct,
    loss_rate,
    gross_qty,
    gross_unit,
    net_qty,
    net_unit,
    NULL::DECIMAL(10,4)          AS recycle_pct
FROM mat_bom
WHERE bom_type = 'ELEMENT'

UNION ALL

-- 来源 2: 元素回收折扣（mat_fee[ELEMENT_RECYCLE]）
SELECT
    'ELEMENT_RECYCLE'::VARCHAR   AS source_type,
    hf_part_no,
    dim_input_material_no        AS input_material_no,
    dim_input_material_name      AS input_material_name,
    seq_no,
    dim_element_name             AS element_name,
    NULL::DECIMAL(10,4)          AS composition_pct,
    NULL::DECIMAL(10,4)          AS loss_rate,
    NULL::DECIMAL(18,4)          AS gross_qty,
    NULL::VARCHAR(16)            AS gross_unit,
    NULL::DECIMAL(18,4)          AS net_qty,
    NULL::VARCHAR(16)            AS net_unit,
    fee_ratio                    AS recycle_pct
FROM mat_fee
WHERE fee_type = 'ELEMENT_RECYCLE'
  AND is_current = true;

COMMENT ON VIEW v_q_element_merged IS
    'V128: 元素合并视图 — mat_bom[ELEMENT] + mat_fee[ELEMENT_RECYCLE, is_current=true] UNION ALL';

-- ── 3.4 v_q_finished_merged — 成品（2 来源 UNION ALL）
CREATE OR REPLACE VIEW v_q_finished_merged AS
-- 来源 1: 成品固定加工费
SELECT
    'FINISHED_FIXED'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    NULL::VARCHAR(128)           AS element_name,
    fee_value,
    fee_ratio,
    currency,
    price_unit
FROM mat_fee
WHERE fee_type = 'FINISHED_FIXED'
  AND is_current = true

UNION ALL

-- 来源 2: 成品其他费用（dim_element_name → element_name）
SELECT
    'FINISHED_OTHER'::VARCHAR    AS source_type,
    hf_part_no,
    seq_no,
    dim_element_name             AS element_name,
    fee_value,
    fee_ratio,
    currency,
    price_unit
FROM mat_fee
WHERE fee_type = 'FINISHED_OTHER'
  AND is_current = true;

COMMENT ON VIEW v_q_finished_merged IS
    'V128: 成品合并视图 — mat_fee[FINISHED_FIXED/FINISHED_OTHER, is_current=true] UNION ALL';

-- ── 3.5 v_q_component_merged — 组成件（单源 mat_process[is_current=true]）
CREATE OR REPLACE VIEW v_q_component_merged AS
SELECT
    'COMPONENT_BOM'::VARCHAR     AS source_type,
    hf_part_no,
    seq_no,
    process_code,
    assembly_process,
    sub_seq_no,
    component_part_no,
    component_name,
    supplier_code,
    supplier_name,
    quantity,
    quantity_unit,
    unit_price,
    freight,
    currency,
    price_unit
FROM mat_process
WHERE is_current = true;

COMMENT ON VIEW v_q_component_merged IS
    'V128: 组成件合并视图 — mat_process[is_current=true]';

-- ── 3.6 v_q_assembly_merged — 组装加工（单源 mat_fee[ASSEMBLY_PROCESS]）
CREATE OR REPLACE VIEW v_q_assembly_merged AS
SELECT
    'ASSEMBLY_PROCESS'::VARCHAR  AS source_type,
    hf_part_no,
    seq_no,
    dim_assembly_process         AS assembly_process,
    fee_value,
    currency,
    price_unit,
    reject_rate
FROM mat_fee
WHERE fee_type = 'ASSEMBLY_PROCESS'
  AND is_current = true;

COMMENT ON VIEW v_q_assembly_merged IS
    'V128: 组装加工合并视图 — mat_fee[ASSEMBLY_PROCESS, is_current=true]';

-- ── 3.7 v_q_plating_merged — 电镀（2 来源 UNION ALL）
CREATE OR REPLACE VIEW v_q_plating_merged AS
-- 来源 1: 电镀方案（全局，hf_part_no 为 NULL）
SELECT
    'PLAN'::VARCHAR              AS source_type,
    NULL::VARCHAR(64)            AS hf_part_no,
    plan_code,
    version                      AS plan_version,
    seq_no,
    plating_element,
    plating_area,
    coating_thickness,
    plating_requirement,
    NULL::DECIMAL(18,4)          AS plating_process_fee,
    NULL::DECIMAL(18,4)          AS plating_material_fee,
    NULL::VARCHAR(8)             AS currency,
    NULL::VARCHAR(16)            AS price_unit,
    NULL::DECIMAL(10,4)          AS defect_rate
FROM plating_plan

UNION ALL

-- 来源 2: 电镀费用（含 hf_part_no）
SELECT
    'FEE'::VARCHAR               AS source_type,
    hf_part_no,
    plating_plan_code            AS plan_code,
    plan_version,
    NULL::INT                    AS seq_no,
    NULL::VARCHAR(64)            AS plating_element,
    NULL::DECIMAL(18,4)          AS plating_area,
    NULL::DECIMAL(10,4)          AS coating_thickness,
    NULL::VARCHAR(256)           AS plating_requirement,
    plating_process_fee,
    plating_material_fee,
    currency,
    price_unit,
    defect_rate
FROM plating_fee
WHERE is_current = true;

COMMENT ON VIEW v_q_plating_merged IS
    'V128: 电镀合并视图 — plating_plan(PLAN,全局) + plating_fee[is_current=true](FEE) UNION ALL';

-- ════════════════════════════════════════════════════════════════════════════
-- 4. 创建 7 个组件
-- ════════════════════════════════════════════════════════════════════════════

-- 4.1 料件 (COMP-QX-PART-INFO)
INSERT INTO component (
    id, directory_id, name, code, component_type, status,
    data_driver_path, fields, formulas, column_count, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    'c1d2e3f4-0003-4003-8003-000000000003'::uuid,
    '料件',
    'COMP-QX-PART-INFO',
    'NORMAL',
    'ACTIVE',
    'v_q_part_info_merged',
    $JSON$[
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_info_merged.hf_part_no"},
        {"name":"客户料号名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_info_merged.customer_part_name"},
        {"name":"客户产品编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_info_merged.customer_product_no"},
        {"name":"客户图号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_info_merged.customer_drawing_no"},
        {"name":"付款方式","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_info_merged.payment_method"},
        {"name":"基础货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_info_merged.base_currency"},
        {"name":"报价货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_info_merged.quote_currency"},
        {"name":"汇率","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_info_merged.exchange_rate"},
        {"name":"单重(g/pcs)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_info_merged.unit_weight"},
        {"name":"重量单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_part_info_merged.weight_unit"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    10,
    now(), now()
)
ON CONFLICT (code) DO UPDATE SET
    fields           = EXCLUDED.fields,
    formulas         = EXCLUDED.formulas,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count     = EXCLUDED.column_count,
    updated_at       = now();

-- 4.2 来料 (COMP-QX-INCOMING)
INSERT INTO component (
    id, directory_id, name, code, component_type, status,
    data_driver_path, fields, formulas, column_count, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    'c1d2e3f4-0003-4003-8003-000000000003'::uuid,
    '来料',
    'COMP-QX-INCOMING',
    'NORMAL',
    'ACTIVE',
    'v_q_incoming_merged',
    $JSON$[
        {"name":"来源","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.source_type"},
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.hf_part_no"},
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.seq_no"},
        {"name":"投入料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.input_material_no"},
        {"name":"投入料号名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.input_material_name"},
        {"name":"产出料号类型","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.output_material_type"},
        {"name":"材料毛重","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.gross_qty"},
        {"name":"材料净重","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.net_qty"},
        {"name":"重量单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.weight_unit"},
        {"name":"损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.loss_rate"},
        {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.defect_rate"},
        {"name":"项次2","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.sub_seq_no"},
        {"name":"要素名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.element_name"},
        {"name":"值","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.fee_value"},
        {"name":"比例(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.fee_ratio"},
        {"name":"货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.currency"},
        {"name":"计价单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.price_unit"},
        {"name":"是否随材料价格波动","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.price_floating"},
        {"name":"材料结算涨幅比例(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.settlement_rise_ratio"},
        {"name":"材料固定涨幅值","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.fixed_rise_value"},
        {"name":"涨幅货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.rise_currency"},
        {"name":"涨幅单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.rise_unit"},
        {"name":"回收折扣(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_incoming_merged.recycle_pct"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    23,
    now(), now()
)
ON CONFLICT (code) DO UPDATE SET
    fields           = EXCLUDED.fields,
    formulas         = EXCLUDED.formulas,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count     = EXCLUDED.column_count,
    updated_at       = now();

-- 4.3 元素 (COMP-QX-ELEMENT)
INSERT INTO component (
    id, directory_id, name, code, component_type, status,
    data_driver_path, fields, formulas, column_count, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    'c1d2e3f4-0003-4003-8003-000000000003'::uuid,
    '元素',
    'COMP-QX-ELEMENT',
    'NORMAL',
    'ACTIVE',
    'v_q_element_merged',
    $JSON$[
        {"name":"来源","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.source_type"},
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.hf_part_no"},
        {"name":"投入料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.input_material_no"},
        {"name":"投入料号名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.input_material_name"},
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.seq_no"},
        {"name":"元素","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.element_name"},
        {"name":"组成含量(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.composition_pct"},
        {"name":"损耗率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.loss_rate"},
        {"name":"毛用量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.gross_qty"},
        {"name":"毛用量单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.gross_unit"},
        {"name":"净用量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.net_qty"},
        {"name":"净用量单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.net_unit"},
        {"name":"回收折扣(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_element_merged.recycle_pct"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    13,
    now(), now()
)
ON CONFLICT (code) DO UPDATE SET
    fields           = EXCLUDED.fields,
    formulas         = EXCLUDED.formulas,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count     = EXCLUDED.column_count,
    updated_at       = now();

-- 4.4 成品 (COMP-QX-FINISHED)
INSERT INTO component (
    id, directory_id, name, code, component_type, status,
    data_driver_path, fields, formulas, column_count, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    'c1d2e3f4-0003-4003-8003-000000000003'::uuid,
    '成品',
    'COMP-QX-FINISHED',
    'NORMAL',
    'ACTIVE',
    'v_q_finished_merged',
    $JSON$[
        {"name":"来源","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_finished_merged.source_type"},
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_finished_merged.hf_part_no"},
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_finished_merged.seq_no"},
        {"name":"要素名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_finished_merged.element_name"},
        {"name":"值","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_q_finished_merged.fee_value"},
        {"name":"比例(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_finished_merged.fee_ratio"},
        {"name":"货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_finished_merged.currency"},
        {"name":"计价单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_finished_merged.price_unit"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    8,
    now(), now()
)
ON CONFLICT (code) DO UPDATE SET
    fields           = EXCLUDED.fields,
    formulas         = EXCLUDED.formulas,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count     = EXCLUDED.column_count,
    updated_at       = now();

-- 4.5 组成件 (COMP-QX-COMPONENT)
INSERT INTO component (
    id, directory_id, name, code, component_type, status,
    data_driver_path, fields, formulas, column_count, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    'c1d2e3f4-0003-4003-8003-000000000003'::uuid,
    '组成件',
    'COMP-QX-COMPONENT',
    'NORMAL',
    'ACTIVE',
    'v_q_component_merged',
    $JSON$[
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.hf_part_no"},
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.seq_no"},
        {"name":"工序编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.process_code"},
        {"name":"组装工序","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.assembly_process"},
        {"name":"项次2","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.sub_seq_no"},
        {"name":"组成件料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.component_part_no"},
        {"name":"组成件名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.component_name"},
        {"name":"供应商编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.supplier_code"},
        {"name":"供应商名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.supplier_name"},
        {"name":"组成数量","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.quantity"},
        {"name":"组成单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.quantity_unit"},
        {"name":"单价","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_q_component_merged.unit_price"},
        {"name":"运费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_q_component_merged.freight"},
        {"name":"货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.currency"},
        {"name":"计价单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_component_merged.price_unit"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    15,
    now(), now()
)
ON CONFLICT (code) DO UPDATE SET
    fields           = EXCLUDED.fields,
    formulas         = EXCLUDED.formulas,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count     = EXCLUDED.column_count,
    updated_at       = now();

-- 4.6 组装加工 (COMP-QX-ASSEMBLY)
INSERT INTO component (
    id, directory_id, name, code, component_type, status,
    data_driver_path, fields, formulas, column_count, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    'c1d2e3f4-0003-4003-8003-000000000003'::uuid,
    '组装加工',
    'COMP-QX-ASSEMBLY',
    'NORMAL',
    'ACTIVE',
    'v_q_assembly_merged',
    $JSON$[
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_assembly_merged.hf_part_no"},
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_assembly_merged.seq_no"},
        {"name":"组装工序","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_assembly_merged.assembly_process"},
        {"name":"组装加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_q_assembly_merged.fee_value"},
        {"name":"货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_assembly_merged.currency"},
        {"name":"计价单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_assembly_merged.price_unit"},
        {"name":"拒收率/不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_assembly_merged.reject_rate"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    7,
    now(), now()
)
ON CONFLICT (code) DO UPDATE SET
    fields           = EXCLUDED.fields,
    formulas         = EXCLUDED.formulas,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count     = EXCLUDED.column_count,
    updated_at       = now();

-- 4.7 电镀 (COMP-QX-PLATING)
INSERT INTO component (
    id, directory_id, name, code, component_type, status,
    data_driver_path, fields, formulas, column_count, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    'c1d2e3f4-0003-4003-8003-000000000003'::uuid,
    '电镀',
    'COMP-QX-PLATING',
    'NORMAL',
    'ACTIVE',
    'v_q_plating_merged',
    $JSON$[
        {"name":"来源","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.source_type"},
        {"name":"宏丰料号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.hf_part_no"},
        {"name":"方案编号","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.plan_code"},
        {"name":"版本","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.plan_version"},
        {"name":"项次","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.seq_no"},
        {"name":"电镀元素名称","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.plating_element"},
        {"name":"电镀面积(cm2)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.plating_area"},
        {"name":"镀层厚度(um)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.coating_thickness"},
        {"name":"电镀要求","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.plating_requirement"},
        {"name":"电镀加工费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.plating_process_fee"},
        {"name":"电镀材料费","field_type":"BASIC_DATA","content":"","is_amount":true,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.plating_material_fee"},
        {"name":"货币","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.currency"},
        {"name":"计价单位","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.price_unit"},
        {"name":"不良率(%)","field_type":"BASIC_DATA","content":"","is_amount":false,"is_subtotal":false,"basic_data_path":"v_q_plating_merged.defect_rate"}
    ]$JSON$::jsonb,
    '[]'::jsonb,
    14,
    now(), now()
)
ON CONFLICT (code) DO UPDATE SET
    fields           = EXCLUDED.fields,
    formulas         = EXCLUDED.formulas,
    data_driver_path = EXCLUDED.data_driver_path,
    column_count     = EXCLUDED.column_count,
    updated_at       = now();

-- ════════════════════════════════════════════════════════════════════════════
-- 5. 创建 QUOTATION 模板「报价标准模板-Excel基础结构 v1.0」
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_tpl_id   UUID;
    v_series   UUID := gen_random_uuid();
    v_sort     INT  := 0;
    v_comp     RECORD;
    v_comp_cnt INT;
BEGIN
    -- 已存在则跳过
    IF EXISTS (
        SELECT 1 FROM template
        WHERE name = '报价标准模板-Excel基础结构 v1.0'
          AND template_kind = 'QUOTATION'
    ) THEN
        RAISE NOTICE 'V128: 模板「报价标准模板-Excel基础结构 v1.0」已存在, 跳过创建';
        RETURN;
    END IF;

    v_tpl_id := gen_random_uuid();

    INSERT INTO template (
        id, template_series_id, name, version, status, template_kind,
        customer_id, description, created_at, updated_at
    ) VALUES (
        v_tpl_id,
        v_series,
        '报价标准模板-Excel基础结构 v1.0',
        '1.0',
        'DRAFT',
        'QUOTATION',
        NULL,  -- 通用模板，不绑定特定客户
        'V128: 7 个合并组件(UNION ALL 视图)对应报价 Excel 基础结构；通用模板 customer_id=NULL; 状态 DRAFT。',
        now(), now()
    );

    -- 关联 7 个组件，按设计顺序
    FOR v_comp IN
        SELECT c.id, c.name FROM component c, (VALUES
            ('COMP-QX-PART-INFO',  0),
            ('COMP-QX-INCOMING',   1),
            ('COMP-QX-ELEMENT',    2),
            ('COMP-QX-FINISHED',   3),
            ('COMP-QX-COMPONENT',  4),
            ('COMP-QX-ASSEMBLY',   5),
            ('COMP-QX-PLATING',    6)
        ) AS ord(code, sort_idx)
        WHERE c.code = ord.code
        ORDER BY ord.sort_idx
    LOOP
        INSERT INTO template_component (
            id, template_id, component_id, tab_name, sort_order, created_at
        ) VALUES (
            gen_random_uuid(), v_tpl_id, v_comp.id, v_comp.name, v_sort, now()
        );
        v_sort := v_sort + 1;
    END LOOP;

    SELECT COUNT(*) INTO v_comp_cnt
    FROM template_component WHERE template_id = v_tpl_id;

    RAISE NOTICE 'V128: 已创建模板 id=% 关联 % 个组件', v_tpl_id, v_comp_cnt;
END $$;

-- ════════════════════════════════════════════════════════════════════════════
-- 6. 自检报告（RAISE NOTICE 视图行数 + 数量验证）
-- ════════════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    v_cnt_part     BIGINT;
    v_cnt_incoming BIGINT;
    v_cnt_element  BIGINT;
    v_cnt_finished BIGINT;
    v_cnt_comp     BIGINT;
    v_cnt_assembly BIGINT;
    v_cnt_plating  BIGINT;
    v_comp_total   BIGINT;
    v_tpl_total    BIGINT;
    v_tc_total     BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_cnt_part     FROM v_q_part_info_merged;
    SELECT COUNT(*) INTO v_cnt_incoming FROM v_q_incoming_merged;
    SELECT COUNT(*) INTO v_cnt_element  FROM v_q_element_merged;
    SELECT COUNT(*) INTO v_cnt_finished FROM v_q_finished_merged;
    SELECT COUNT(*) INTO v_cnt_comp     FROM v_q_component_merged;
    SELECT COUNT(*) INTO v_cnt_assembly FROM v_q_assembly_merged;
    SELECT COUNT(*) INTO v_cnt_plating  FROM v_q_plating_merged;

    SELECT COUNT(*) INTO v_comp_total FROM component
    WHERE directory_id = 'c1d2e3f4-0003-4003-8003-000000000003'::uuid AND status = 'ACTIVE';

    SELECT COUNT(*) INTO v_tpl_total FROM template
    WHERE name = '报价标准模板-Excel基础结构 v1.0' AND template_kind = 'QUOTATION';

    SELECT COUNT(*) INTO v_tc_total FROM template_component tc
    JOIN template t ON t.id = tc.template_id
    WHERE t.name = '报价标准模板-Excel基础结构 v1.0';

    RAISE NOTICE '━━━━ V128 自检报告 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━';
    RAISE NOTICE '  v_q_part_info_merged  行数 = %', v_cnt_part;
    RAISE NOTICE '  v_q_incoming_merged   行数 = %', v_cnt_incoming;
    RAISE NOTICE '  v_q_element_merged    行数 = %', v_cnt_element;
    RAISE NOTICE '  v_q_finished_merged   行数 = %', v_cnt_finished;
    RAISE NOTICE '  v_q_component_merged  行数 = %', v_cnt_comp;
    RAISE NOTICE '  v_q_assembly_merged   行数 = %', v_cnt_assembly;
    RAISE NOTICE '  v_q_plating_merged    行数 = %', v_cnt_plating;
    RAISE NOTICE '  目录「报价模板组件V3-Excel结构」活动组件数 = %', v_comp_total;
    RAISE NOTICE '  模板「报价标准模板-Excel基础结构 v1.0」数 = %', v_tpl_total;
    RAISE NOTICE '  模板绑定组件数 = %', v_tc_total;
    RAISE NOTICE '  mat_fee ELEMENT_RECYCLE 约束: 已添加';
    RAISE NOTICE '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━';
END $$;
