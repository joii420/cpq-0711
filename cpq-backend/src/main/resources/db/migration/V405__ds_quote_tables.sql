-- ============================================================================
-- task-260902 · 报价与核价建表与导入方案新规范 · 报价数据（quote）主表 16 张
--
-- 依据：dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md（唯一建表依据）
--       + 需求文档.md §③ R-1（建表规则）/ R-2（免版本表主键）/ R-5（_history 结构）
-- 服务的 AC：AC-1, AC-2, AC-3, AC-4
--
-- 🚫 本迁移只新增表，不删改任何既有表（CLAUDE.md §3.2）。
-- ⚠️ 列的取舍完全由 字段矩阵.md 决定：白底（主数据 JOIN 展示列）一律不建字段。
-- ============================================================================

-- ── 01. sheet「物料」 → ds_quote_material（免版本） ──────────
CREATE TABLE ds_quote_material (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    material_name             varchar(128),
    specification             varchar(128),
    dimension                 varchar(128),
    old_material_no           varchar(128),
    unit_weight               numeric(26,12),
    production_no             varchar(128),
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE UNIQUE INDEX uq_ds_quote_material ON ds_quote_material (material_no);
COMMENT ON TABLE ds_quote_material IS '物料 · 免版本';
COMMENT ON COLUMN ds_quote_material.material_no IS '销售料号（必填）';
COMMENT ON COLUMN ds_quote_material.material_name IS '品名（必填）';
COMMENT ON COLUMN ds_quote_material.specification IS '规格';
COMMENT ON COLUMN ds_quote_material.dimension IS '尺寸';
COMMENT ON COLUMN ds_quote_material.old_material_no IS '旧料号';
COMMENT ON COLUMN ds_quote_material.unit_weight IS '单重';
COMMENT ON COLUMN ds_quote_material.production_no IS '生产料号';

-- ── 02. sheet「客户料号」 → ds_quote_customer_part（免版本） ──────────
CREATE TABLE ds_quote_customer_part (
    id                        bigserial      PRIMARY KEY,
    customer_no               varchar(20)    NOT NULL,
    customer_part_name        varchar(256),
    customer_product_no       varchar(128)   NOT NULL,
    customer_drawing_no       varchar(128),
    material_no               varchar(128),
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE UNIQUE INDEX uq_ds_quote_customer_part ON ds_quote_customer_part (customer_no, customer_product_no);
COMMENT ON TABLE ds_quote_customer_part IS '客户料号 · 免版本';
COMMENT ON COLUMN ds_quote_customer_part.customer_no IS '客户编号（必填）';
COMMENT ON COLUMN ds_quote_customer_part.customer_part_name IS '客户料号名称';
COMMENT ON COLUMN ds_quote_customer_part.customer_product_no IS '客户产品编号（必填）';
COMMENT ON COLUMN ds_quote_customer_part.customer_drawing_no IS '客户图号';
COMMENT ON COLUMN ds_quote_customer_part.material_no IS '销售料号（必填）';

-- ── 03. sheet「物料BOM」 → ds_quote_material_bom（带版本） ──────────
CREATE TABLE ds_quote_material_bom (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_type                varchar(128),
    input_material_no         varchar(128),
    unit_weight               numeric(26,12),
    output_material_type      varchar(128),
    component_qty             numeric(26,12),
    gross_weight              numeric(26,12),
    net_weight                numeric(26,12),
    weight_unit               varchar(128),
    material_ratio            numeric(26,12),
    loss_rate                 numeric(26,12),
    defect_rate               numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_material_bom_axis_ver ON ds_quote_material_bom (material_no, version_no);
COMMENT ON TABLE ds_quote_material_bom IS '物料BOM · 带版本';
COMMENT ON COLUMN ds_quote_material_bom.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_material_bom.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_quote_material_bom.input_type IS '投入类型（对比项）';
COMMENT ON COLUMN ds_quote_material_bom.input_material_no IS '投入料号（对比项/必填）';
COMMENT ON COLUMN ds_quote_material_bom.unit_weight IS '单重（对比项）';
COMMENT ON COLUMN ds_quote_material_bom.output_material_type IS '产出料号类型（对比项）';
COMMENT ON COLUMN ds_quote_material_bom.component_qty IS '组成数量（对比项/必填）';
COMMENT ON COLUMN ds_quote_material_bom.gross_weight IS '材料毛重（对比项）';
COMMENT ON COLUMN ds_quote_material_bom.net_weight IS '材料净重（对比项）';
COMMENT ON COLUMN ds_quote_material_bom.weight_unit IS '重量单位（对比项）';
COMMENT ON COLUMN ds_quote_material_bom.material_ratio IS '材料占比（%）（对比项）';
COMMENT ON COLUMN ds_quote_material_bom.loss_rate IS '损耗率（%）（对比项）';
COMMENT ON COLUMN ds_quote_material_bom.defect_rate IS '不良率（%）（对比项）';
COMMENT ON COLUMN ds_quote_material_bom.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_material_bom.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 04. sheet「物料与元素BOM」 → ds_quote_element_bom（带版本） ──────────
CREATE TABLE ds_quote_element_bom (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    material_part_no          varchar(128),
    item_seq                  integer,
    element_code              varchar(128),
    content_pct               numeric(26,12),
    loss_rate                 numeric(26,12),
    gross_usage               numeric(26,12),
    gross_usage_unit          varchar(128),
    net_usage                 numeric(26,12),
    net_usage_unit            varchar(128),
    recovery_discount         numeric(26,12),
    recovery_qty              varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_element_bom_axis_ver ON ds_quote_element_bom (material_no, version_no);
COMMENT ON TABLE ds_quote_element_bom IS '物料与元素BOM · 带版本';
COMMENT ON COLUMN ds_quote_element_bom.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_element_bom.material_part_no IS '材质料号（对比项/必填）';
COMMENT ON COLUMN ds_quote_element_bom.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_quote_element_bom.element_code IS '元素（对比项/必填）';
COMMENT ON COLUMN ds_quote_element_bom.content_pct IS '组成含量（%）（对比项/必填）';
COMMENT ON COLUMN ds_quote_element_bom.loss_rate IS '损耗率%（对比项）';
COMMENT ON COLUMN ds_quote_element_bom.gross_usage IS '毛用量（对比项）';
COMMENT ON COLUMN ds_quote_element_bom.gross_usage_unit IS '毛用量单位（对比项）';
COMMENT ON COLUMN ds_quote_element_bom.net_usage IS '净用量（对比项）';
COMMENT ON COLUMN ds_quote_element_bom.net_usage_unit IS '净用量单位（对比项）';
COMMENT ON COLUMN ds_quote_element_bom.recovery_discount IS '回收折扣(%)（对比项）';
COMMENT ON COLUMN ds_quote_element_bom.recovery_qty IS '回收量（对比项）';
COMMENT ON COLUMN ds_quote_element_bom.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_element_bom.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 05. sheet「来料固定加工费」 → ds_quote_incoming_fixed_fee（带版本） ──────────
CREATE TABLE ds_quote_incoming_fixed_fee (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    base_value                numeric(26,12),
    ratio_pct                 numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    follow_material_price     boolean,
    material_increase_ratio   numeric(26,12),
    material_increase_value   numeric(26,12),
    increase_currency         varchar(128),
    increase_unit             varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_incoming_fixed_fee_axis_ver ON ds_quote_incoming_fixed_fee (material_no, version_no);
COMMENT ON TABLE ds_quote_incoming_fixed_fee IS '来料固定加工费 · 带版本';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.input_material_no IS '投入料号（对比项/必填）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.base_value IS '基准值（对比项）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.ratio_pct IS '比例（%）（对比项）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.follow_material_price IS '是否随材料价格波动（对比项）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.material_increase_ratio IS '材料结算涨幅比例（%）（对比项）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.material_increase_value IS '材料固定的涨幅值（对比项）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.increase_currency IS '涨幅货币（对比项）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.increase_unit IS '涨幅单位（对比项）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 06. sheet「来料其他费用」 → ds_quote_incoming_other_fee（带版本） ──────────
CREATE TABLE ds_quote_incoming_other_fee (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    element_item_seq          integer,
    element_name              varchar(256),
    value                     numeric(26,12),
    ratio_pct                 numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_incoming_other_fee_axis_ver ON ds_quote_incoming_other_fee (material_no, version_no);
COMMENT ON TABLE ds_quote_incoming_other_fee IS '来料其他费用 · 带版本';
COMMENT ON COLUMN ds_quote_incoming_other_fee.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.input_material_no IS '投入料号（对比项/必填）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.element_item_seq IS '要素项次（对比项/必填）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.value IS '值（对比项）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.ratio_pct IS '比例（%）（对比项）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_incoming_other_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 07. sheet「来料回收折扣」 → ds_quote_incoming_recovery（带版本） ──────────
CREATE TABLE ds_quote_incoming_recovery (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    recovery_discount         numeric(26,12),
    recovery_value            numeric(26,12),
    recovery_source           varchar(256),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_incoming_recovery_axis_ver ON ds_quote_incoming_recovery (material_no, version_no);
COMMENT ON TABLE ds_quote_incoming_recovery IS '来料回收折扣 · 带版本';
COMMENT ON COLUMN ds_quote_incoming_recovery.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_incoming_recovery.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_quote_incoming_recovery.input_material_no IS '投入料号（对比项/必填）';
COMMENT ON COLUMN ds_quote_incoming_recovery.recovery_discount IS '回收折扣（%）（对比项）';
COMMENT ON COLUMN ds_quote_incoming_recovery.recovery_value IS '回收值（对比项）';
COMMENT ON COLUMN ds_quote_incoming_recovery.recovery_source IS '回收来源（对比项）';
COMMENT ON COLUMN ds_quote_incoming_recovery.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_incoming_recovery.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 08. sheet「自制加工费」 → ds_quote_self_process_fee（带版本） ──────────
CREATE TABLE ds_quote_self_process_fee (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    operation_item_seq        integer,
    operation_no              varchar(128),
    value                     numeric(26,12),
    ratio_pct                 numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_self_process_fee_axis_ver ON ds_quote_self_process_fee (material_no, version_no);
COMMENT ON TABLE ds_quote_self_process_fee IS '自制加工费 · 带版本';
COMMENT ON COLUMN ds_quote_self_process_fee.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_self_process_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_quote_self_process_fee.input_material_no IS '投入料号（对比项/必填）';
COMMENT ON COLUMN ds_quote_self_process_fee.operation_item_seq IS '工序项次（对比项）';
COMMENT ON COLUMN ds_quote_self_process_fee.operation_no IS '工序编号（对比项）';
COMMENT ON COLUMN ds_quote_self_process_fee.value IS '值（对比项）';
COMMENT ON COLUMN ds_quote_self_process_fee.ratio_pct IS '比例（%）（对比项）';
COMMENT ON COLUMN ds_quote_self_process_fee.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_quote_self_process_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_quote_self_process_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_self_process_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 09. sheet「成品其他费用」 → ds_quote_finished_other_fee（带版本） ──────────
CREATE TABLE ds_quote_finished_other_fee (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    element_name              varchar(256),
    value                     numeric(26,12),
    ratio_pct                 numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_finished_other_fee_axis_ver ON ds_quote_finished_other_fee (material_no, version_no);
COMMENT ON TABLE ds_quote_finished_other_fee IS '成品其他费用 · 带版本';
COMMENT ON COLUMN ds_quote_finished_other_fee.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_finished_other_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_quote_finished_other_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_quote_finished_other_fee.value IS '值（对比项）';
COMMENT ON COLUMN ds_quote_finished_other_fee.ratio_pct IS '比例（%）（对比项）';
COMMENT ON COLUMN ds_quote_finished_other_fee.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_quote_finished_other_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_quote_finished_other_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_finished_other_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 10. sheet「组成件其他费用」 → ds_quote_sub_component_fee（带版本） ──────────
CREATE TABLE ds_quote_sub_component_fee (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    sub_component_no          varchar(128),
    supplier_no               varchar(128),
    supplier_name             varchar(256),
    element_item_seq          integer,
    element_name              varchar(256),
    value                     numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_sub_component_fee_axis_ver ON ds_quote_sub_component_fee (material_no, version_no);
COMMENT ON TABLE ds_quote_sub_component_fee IS '组成件其他费用 · 带版本';
COMMENT ON COLUMN ds_quote_sub_component_fee.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_sub_component_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_quote_sub_component_fee.sub_component_no IS '组成件料号（对比项/必填）';
COMMENT ON COLUMN ds_quote_sub_component_fee.supplier_no IS '供应商编号（对比项）';
COMMENT ON COLUMN ds_quote_sub_component_fee.supplier_name IS '供应商名称（对比项）';
COMMENT ON COLUMN ds_quote_sub_component_fee.element_item_seq IS '要素项次（对比项/必填）';
COMMENT ON COLUMN ds_quote_sub_component_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_quote_sub_component_fee.value IS '值（对比项/必填）';
COMMENT ON COLUMN ds_quote_sub_component_fee.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_quote_sub_component_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_quote_sub_component_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_sub_component_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 11. sheet「组装加工费」 → ds_quote_assembly_fee（带版本） ──────────
CREATE TABLE ds_quote_assembly_fee (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    assembly_operation        varchar(128),
    assembly_fee              numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    defect_rate               numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_assembly_fee_axis_ver ON ds_quote_assembly_fee (material_no, version_no);
COMMENT ON TABLE ds_quote_assembly_fee IS '组装加工费 · 带版本';
COMMENT ON COLUMN ds_quote_assembly_fee.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_assembly_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_quote_assembly_fee.assembly_operation IS '组装工序（对比项/必填）';
COMMENT ON COLUMN ds_quote_assembly_fee.assembly_fee IS '组装加工费（对比项/必填）';
COMMENT ON COLUMN ds_quote_assembly_fee.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_quote_assembly_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_quote_assembly_fee.defect_rate IS '拒收率/不良率（%）（对比项）';
COMMENT ON COLUMN ds_quote_assembly_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_assembly_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 12. sheet「组装加工费年降」 → ds_quote_assembly_fee_annual（带版本） ──────────
CREATE TABLE ds_quote_assembly_fee_annual (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    assembly_operation        varchar(128),
    discount_seq              integer,
    discount_rate             numeric(26,12),
    fixed_discount_value      numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    discount_times            integer,
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_assembly_fee_annual_axis_ver ON ds_quote_assembly_fee_annual (material_no, version_no);
COMMENT ON TABLE ds_quote_assembly_fee_annual IS '组装加工费年降 · 带版本';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.item_seq IS '项次';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.assembly_operation IS '组装工序（对比项）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.discount_seq IS '年降顺序（对比项）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.discount_rate IS '年降系数（%）（对比项）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.fixed_discount_value IS '单次固定年降值（对比项）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.discount_times IS '降价次数（对比项）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 13. sheet「电镀费用」 → ds_quote_plating_fee（带版本） ──────────
CREATE TABLE ds_quote_plating_fee (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    plating_scheme_no         varchar(128),
    plating_version           varchar(128),
    plating_process_fee       numeric(26,12),
    plating_material_fee      numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    defect_rate               numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_plating_fee_axis_ver ON ds_quote_plating_fee (material_no, version_no);
COMMENT ON TABLE ds_quote_plating_fee IS '电镀费用 · 带版本';
COMMENT ON COLUMN ds_quote_plating_fee.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_plating_fee.plating_scheme_no IS '电镀方案编号（对比项）';
COMMENT ON COLUMN ds_quote_plating_fee.plating_version IS '版本编号（对比项）';
COMMENT ON COLUMN ds_quote_plating_fee.plating_process_fee IS '电镀加工费（对比项）';
COMMENT ON COLUMN ds_quote_plating_fee.plating_material_fee IS '电镀材料费（对比项）';
COMMENT ON COLUMN ds_quote_plating_fee.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_quote_plating_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_quote_plating_fee.defect_rate IS '不良率（%）（对比项）';
COMMENT ON COLUMN ds_quote_plating_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_plating_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 14. sheet「电镀方案」 → ds_quote_plating_scheme（免版本） ──────────
CREATE TABLE ds_quote_plating_scheme (
    id                        bigserial      PRIMARY KEY,
    scheme_no                 varchar(128)   NOT NULL,
    scheme_version            varchar(128)   NOT NULL,
    item_seq                  integer        NOT NULL,
    plating_element           varchar(256),
    price_source_url          varchar(512),
    price_source_name         varchar(256),
    price_fetch_rule          varchar(256),
    plating_area              numeric(26,12),
    coating_thickness         numeric(26,12),
    plating_requirement       varchar(256),
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE UNIQUE INDEX uq_ds_quote_plating_scheme ON ds_quote_plating_scheme (scheme_no, scheme_version, item_seq);
COMMENT ON TABLE ds_quote_plating_scheme IS '电镀方案 · 免版本';
COMMENT ON COLUMN ds_quote_plating_scheme.scheme_no IS '方案编号（必填）';
COMMENT ON COLUMN ds_quote_plating_scheme.scheme_version IS '版本（必填）';
COMMENT ON COLUMN ds_quote_plating_scheme.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_quote_plating_scheme.plating_element IS '电镀元素名称';
COMMENT ON COLUMN ds_quote_plating_scheme.price_source_url IS '元素单价来源网站网址';
COMMENT ON COLUMN ds_quote_plating_scheme.price_source_name IS '元素单价来源网站名称';
COMMENT ON COLUMN ds_quote_plating_scheme.price_fetch_rule IS '元素单价抓取规则';
COMMENT ON COLUMN ds_quote_plating_scheme.plating_area IS '电镀面积（cm2）';
COMMENT ON COLUMN ds_quote_plating_scheme.coating_thickness IS '镀层厚度（μm）';
COMMENT ON COLUMN ds_quote_plating_scheme.plating_requirement IS '电镀要求';

-- ── 15. sheet「来料年降」 → ds_quote_incoming_annual（带版本） ──────────
CREATE TABLE ds_quote_incoming_annual (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    item_seq                  integer,
    input_material_no         varchar(128),
    discount_seq              integer,
    discount_rate             numeric(26,12),
    fixed_discount_value      numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    discount_times            integer,
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_incoming_annual_axis_ver ON ds_quote_incoming_annual (material_no, version_no);
COMMENT ON TABLE ds_quote_incoming_annual IS '来料年降 · 带版本';
COMMENT ON COLUMN ds_quote_incoming_annual.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_incoming_annual.item_seq IS '项次';
COMMENT ON COLUMN ds_quote_incoming_annual.input_material_no IS '投入料号（对比项）';
COMMENT ON COLUMN ds_quote_incoming_annual.discount_seq IS '年降顺序（对比项）';
COMMENT ON COLUMN ds_quote_incoming_annual.discount_rate IS '年降系数（%）（对比项）';
COMMENT ON COLUMN ds_quote_incoming_annual.fixed_discount_value IS '单次固定年降值（对比项）';
COMMENT ON COLUMN ds_quote_incoming_annual.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_quote_incoming_annual.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_quote_incoming_annual.discount_times IS '降价次数（对比项）';
COMMENT ON COLUMN ds_quote_incoming_annual.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_incoming_annual.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 16. sheet「年降系数」 → ds_quote_annual_discount（带版本） ──────────
CREATE TABLE ds_quote_annual_discount (
    id                        bigserial      PRIMARY KEY,
    material_no               varchar(128)   NOT NULL,
    discount_seq              integer,
    discount_rate             numeric(26,12),
    fixed_discount_value      numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    discount_times            integer,
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_quote_annual_discount_axis_ver ON ds_quote_annual_discount (material_no, version_no);
COMMENT ON TABLE ds_quote_annual_discount IS '年降系数 · 带版本';
COMMENT ON COLUMN ds_quote_annual_discount.material_no IS '销售料号（轴/必填）';
COMMENT ON COLUMN ds_quote_annual_discount.discount_seq IS '年降顺序（对比项）';
COMMENT ON COLUMN ds_quote_annual_discount.discount_rate IS '年降系数（%/年）（对比项）';
COMMENT ON COLUMN ds_quote_annual_discount.fixed_discount_value IS '单次固定年降金额（对比项）';
COMMENT ON COLUMN ds_quote_annual_discount.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_quote_annual_discount.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_quote_annual_discount.discount_times IS '降价次数（对比项）';
COMMENT ON COLUMN ds_quote_annual_discount.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_quote_annual_discount.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

