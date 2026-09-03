-- ============================================================================
-- task-260902 · 报价与核价建表与导入方案新规范 · 三套数据集 _history 归档表 39 张
--
-- 依据：dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md（唯一建表依据）
--       + 需求文档.md §③ R-1（建表规则）/ R-2（免版本表主键）/ R-5（_history 结构）
-- 服务的 AC：AC-1, AC-5
--
-- 🚫 本迁移只新增表，不删改任何既有表（CLAUDE.md §3.2）。
-- ⚠️ 列的取舍完全由 字段矩阵.md 决定：白底（主数据 JOIN 展示列）一律不建字段。
-- ============================================================================

-- 结构 = 主表全部列（主表 id → origin_id，本表自带新 id bigserial pk）+ archived_at / archived_by / archive_reason。
-- 🚫 无外键：主表行会被删除，外键会挡住归档（backtask B-2）。


-- ══════ 报价数据（ds_quote_*）══════

-- ── ds_quote_material_bom_history ──
CREATE TABLE ds_quote_material_bom_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_material_bom_history_axis_ver ON ds_quote_material_bom_history (material_no, version_no);
COMMENT ON TABLE ds_quote_material_bom_history IS '物料BOM · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_material_bom_history.origin_id IS '归档前主表 ds_quote_material_bom.id';
COMMENT ON COLUMN ds_quote_material_bom_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_element_bom_history ──
CREATE TABLE ds_quote_element_bom_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_element_bom_history_axis_ver ON ds_quote_element_bom_history (material_no, version_no);
COMMENT ON TABLE ds_quote_element_bom_history IS '物料与元素BOM · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_element_bom_history.origin_id IS '归档前主表 ds_quote_element_bom.id';
COMMENT ON COLUMN ds_quote_element_bom_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_incoming_fixed_fee_history ──
CREATE TABLE ds_quote_incoming_fixed_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_incoming_fixed_fee_history_axis_ver ON ds_quote_incoming_fixed_fee_history (material_no, version_no);
COMMENT ON TABLE ds_quote_incoming_fixed_fee_history IS '来料固定加工费 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee_history.origin_id IS '归档前主表 ds_quote_incoming_fixed_fee.id';
COMMENT ON COLUMN ds_quote_incoming_fixed_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_incoming_other_fee_history ──
CREATE TABLE ds_quote_incoming_other_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_incoming_other_fee_history_axis_ver ON ds_quote_incoming_other_fee_history (material_no, version_no);
COMMENT ON TABLE ds_quote_incoming_other_fee_history IS '来料其他费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_incoming_other_fee_history.origin_id IS '归档前主表 ds_quote_incoming_other_fee.id';
COMMENT ON COLUMN ds_quote_incoming_other_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_incoming_recovery_history ──
CREATE TABLE ds_quote_incoming_recovery_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_incoming_recovery_history_axis_ver ON ds_quote_incoming_recovery_history (material_no, version_no);
COMMENT ON TABLE ds_quote_incoming_recovery_history IS '来料回收折扣 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_incoming_recovery_history.origin_id IS '归档前主表 ds_quote_incoming_recovery.id';
COMMENT ON COLUMN ds_quote_incoming_recovery_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_self_process_fee_history ──
CREATE TABLE ds_quote_self_process_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_self_process_fee_history_axis_ver ON ds_quote_self_process_fee_history (material_no, version_no);
COMMENT ON TABLE ds_quote_self_process_fee_history IS '自制加工费 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_self_process_fee_history.origin_id IS '归档前主表 ds_quote_self_process_fee.id';
COMMENT ON COLUMN ds_quote_self_process_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_finished_other_fee_history ──
CREATE TABLE ds_quote_finished_other_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_finished_other_fee_history_axis_ver ON ds_quote_finished_other_fee_history (material_no, version_no);
COMMENT ON TABLE ds_quote_finished_other_fee_history IS '成品其他费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_finished_other_fee_history.origin_id IS '归档前主表 ds_quote_finished_other_fee.id';
COMMENT ON COLUMN ds_quote_finished_other_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_sub_component_fee_history ──
CREATE TABLE ds_quote_sub_component_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_sub_component_fee_history_axis_ver ON ds_quote_sub_component_fee_history (material_no, version_no);
COMMENT ON TABLE ds_quote_sub_component_fee_history IS '组成件其他费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_sub_component_fee_history.origin_id IS '归档前主表 ds_quote_sub_component_fee.id';
COMMENT ON COLUMN ds_quote_sub_component_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_assembly_fee_history ──
CREATE TABLE ds_quote_assembly_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_assembly_fee_history_axis_ver ON ds_quote_assembly_fee_history (material_no, version_no);
COMMENT ON TABLE ds_quote_assembly_fee_history IS '组装加工费 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_assembly_fee_history.origin_id IS '归档前主表 ds_quote_assembly_fee.id';
COMMENT ON COLUMN ds_quote_assembly_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_assembly_fee_annual_history ──
CREATE TABLE ds_quote_assembly_fee_annual_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_assembly_fee_annual_history_axis_ver ON ds_quote_assembly_fee_annual_history (material_no, version_no);
COMMENT ON TABLE ds_quote_assembly_fee_annual_history IS '组装加工费年降 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_assembly_fee_annual_history.origin_id IS '归档前主表 ds_quote_assembly_fee_annual.id';
COMMENT ON COLUMN ds_quote_assembly_fee_annual_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_plating_fee_history ──
CREATE TABLE ds_quote_plating_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_plating_fee_history_axis_ver ON ds_quote_plating_fee_history (material_no, version_no);
COMMENT ON TABLE ds_quote_plating_fee_history IS '电镀费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_plating_fee_history.origin_id IS '归档前主表 ds_quote_plating_fee.id';
COMMENT ON COLUMN ds_quote_plating_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_incoming_annual_history ──
CREATE TABLE ds_quote_incoming_annual_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_incoming_annual_history_axis_ver ON ds_quote_incoming_annual_history (material_no, version_no);
COMMENT ON TABLE ds_quote_incoming_annual_history IS '来料年降 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_incoming_annual_history.origin_id IS '归档前主表 ds_quote_incoming_annual.id';
COMMENT ON COLUMN ds_quote_incoming_annual_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_quote_annual_discount_history ──
CREATE TABLE ds_quote_annual_discount_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_quote_annual_discount_history_axis_ver ON ds_quote_annual_discount_history (material_no, version_no);
COMMENT ON TABLE ds_quote_annual_discount_history IS '年降系数 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_quote_annual_discount_history.origin_id IS '归档前主表 ds_quote_annual_discount.id';
COMMENT ON COLUMN ds_quote_annual_discount_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';


-- ══════ 基础核价（ds_cost_basic_*）══════

-- ── ds_cost_basic_material_bom_history ──
CREATE TABLE ds_cost_basic_material_bom_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    component_type            varchar(128),
    component_no              varchar(128),
    operation_no              varchar(128),
    usage_characteristic      varchar(128),
    component_qty             numeric(26,12),
    component_qty_unit        varchar(128),
    base_qty                  numeric(26,12),
    base_qty_unit             varchar(128),
    material_loss_rate        numeric(26,12),
    material_fixed_loss       numeric(26,12),
    defect_rate               numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_basic_material_bom_history_axis_ver ON ds_cost_basic_material_bom_history (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_material_bom_history IS '物料BOM · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_basic_material_bom_history.origin_id IS '归档前主表 ds_cost_basic_material_bom.id';
COMMENT ON COLUMN ds_cost_basic_material_bom_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_basic_element_bom_history ──
CREATE TABLE ds_cost_basic_element_bom_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    material_part_no          varchar(128),
    item_seq                  integer,
    element_code              varchar(128),
    content_pct               numeric(26,12),
    loss_rate                 numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_basic_element_bom_history_axis_ver ON ds_cost_basic_element_bom_history (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_element_bom_history IS '物料与元素BOM · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_basic_element_bom_history.origin_id IS '归档前主表 ds_cost_basic_element_bom.id';
COMMENT ON COLUMN ds_cost_basic_element_bom_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_basic_incoming_process_fee_history ──
CREATE TABLE ds_cost_basic_incoming_process_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    incoming_material_no      varchar(128),
    process_fee               numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    loss_rate                 numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_basic_incoming_process_fee_history_axis_ver ON ds_cost_basic_incoming_process_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_incoming_process_fee_history IS '来料加工费 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee_history.origin_id IS '归档前主表 ds_cost_basic_incoming_process_fee.id';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_basic_incoming_other_fee_history ──
CREATE TABLE ds_cost_basic_incoming_other_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    incoming_material_no      varchar(128),
    element_item_seq          integer,
    element_name              varchar(256),
    ratio_pct                 numeric(26,12),
    fee                       numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_basic_incoming_other_fee_history_axis_ver ON ds_cost_basic_incoming_other_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_incoming_other_fee_history IS '来料其他费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee_history.origin_id IS '归档前主表 ds_cost_basic_incoming_other_fee.id';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_basic_incoming_other_fixed_fee_history ──
CREATE TABLE ds_cost_basic_incoming_other_fixed_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    incoming_material_no      varchar(128),
    element_item_seq          integer,
    element_name              varchar(256),
    fee                       numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_basic_incoming_other_fixed_fee_history_axis_ver ON ds_cost_basic_incoming_other_fixed_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_incoming_other_fixed_fee_history IS '来料其他固定费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee_history.origin_id IS '归档前主表 ds_cost_basic_incoming_other_fixed_fee.id';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_basic_process_assembly_fee_history ──
CREATE TABLE ds_cost_basic_process_assembly_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    process_fee               numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    defect_rate               numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_basic_process_assembly_fee_history_axis_ver ON ds_cost_basic_process_assembly_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_process_assembly_fee_history IS '加工费&组装费 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_basic_process_assembly_fee_history.origin_id IS '归档前主表 ds_cost_basic_process_assembly_fee.id';
COMMENT ON COLUMN ds_cost_basic_process_assembly_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_basic_outsourced_process_history ──
CREATE TABLE ds_cost_basic_outsourced_process_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    outsourced_fee            numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_basic_outsourced_process_history_axis_ver ON ds_cost_basic_outsourced_process_history (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_outsourced_process_history IS '其他外加工成本 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_basic_outsourced_process_history.origin_id IS '归档前主表 ds_cost_basic_outsourced_process.id';
COMMENT ON COLUMN ds_cost_basic_outsourced_process_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_basic_finished_ratio_fee_history ──
CREATE TABLE ds_cost_basic_finished_ratio_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    element_name              varchar(256),
    ratio_pct                 numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_basic_finished_ratio_fee_history_axis_ver ON ds_cost_basic_finished_ratio_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_finished_ratio_fee_history IS '成品其他比例费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_basic_finished_ratio_fee_history.origin_id IS '归档前主表 ds_cost_basic_finished_ratio_fee.id';
COMMENT ON COLUMN ds_cost_basic_finished_ratio_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_basic_finished_fixed_fee_history ──
CREATE TABLE ds_cost_basic_finished_fixed_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    element_name              varchar(256),
    fee                       numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_basic_finished_fixed_fee_history_axis_ver ON ds_cost_basic_finished_fixed_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_finished_fixed_fee_history IS '成品其他固定费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_basic_finished_fixed_fee_history.origin_id IS '归档前主表 ds_cost_basic_finished_fixed_fee.id';
COMMENT ON COLUMN ds_cost_basic_finished_fixed_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';


-- ══════ 详细核价（ds_cost_detail_*）══════

-- ── ds_cost_detail_material_bom_history ──
CREATE TABLE ds_cost_detail_material_bom_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    component_type            varchar(128),
    component_no              varchar(128),
    operation_no              varchar(128),
    usage_characteristic      varchar(128),
    component_qty             numeric(26,12),
    component_qty_unit        varchar(128),
    base_qty                  numeric(26,12),
    base_qty_unit             varchar(128),
    material_loss_rate        numeric(26,12),
    material_fixed_loss       numeric(26,12),
    defect_rate               numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_material_bom_history_axis_ver ON ds_cost_detail_material_bom_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_material_bom_history IS '物料BOM · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_material_bom_history.origin_id IS '归档前主表 ds_cost_detail_material_bom.id';
COMMENT ON COLUMN ds_cost_detail_material_bom_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_element_bom_history ──
CREATE TABLE ds_cost_detail_element_bom_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    material_part_no          varchar(128),
    item_seq                  integer,
    element_code              varchar(128),
    content_pct               numeric(26,12),
    loss_rate                 numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_element_bom_history_axis_ver ON ds_cost_detail_element_bom_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_element_bom_history IS '物料与元素BOM · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_element_bom_history.origin_id IS '归档前主表 ds_cost_detail_element_bom.id';
COMMENT ON COLUMN ds_cost_detail_element_bom_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_capacity_history ──
CREATE TABLE ds_cost_detail_capacity_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    labor_std_price           numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_capacity_history_axis_ver ON ds_cost_detail_capacity_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_capacity_history IS '产能 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_capacity_history.origin_id IS '归档前主表 ds_cost_detail_capacity.id';
COMMENT ON COLUMN ds_cost_detail_capacity_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_depreciation_history ──
CREATE TABLE ds_cost_detail_depreciation_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    depreciation_price        numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_depreciation_history_axis_ver ON ds_cost_detail_depreciation_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_depreciation_history IS '设备折旧成本 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_depreciation_history.origin_id IS '归档前主表 ds_cost_detail_depreciation.id';
COMMENT ON COLUMN ds_cost_detail_depreciation_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_production_energy_history ──
CREATE TABLE ds_cost_detail_production_energy_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    production_energy_price   numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_production_energy_history_axis_ver ON ds_cost_detail_production_energy_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_production_energy_history IS '生产设备能耗 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_production_energy_history.origin_id IS '归档前主表 ds_cost_detail_production_energy.id';
COMMENT ON COLUMN ds_cost_detail_production_energy_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_auxiliary_energy_history ──
CREATE TABLE ds_cost_detail_auxiliary_energy_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    auxiliary_energy_price    numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_auxiliary_energy_history_axis_ver ON ds_cost_detail_auxiliary_energy_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_auxiliary_energy_history IS '辅助设备能耗 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_auxiliary_energy_history.origin_id IS '归档前主表 ds_cost_detail_auxiliary_energy.id';
COMMENT ON COLUMN ds_cost_detail_auxiliary_energy_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_tooling_history ──
CREATE TABLE ds_cost_detail_tooling_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    item_seq                  integer,
    tooling_no                varchar(128),
    tooling_cost              numeric(26,12),
    tooling_life              integer,
    cycle_output              integer,
    tooling_unit_price        numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_tooling_history_axis_ver ON ds_cost_detail_tooling_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_tooling_history IS '模具工装成本 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_tooling_history.origin_id IS '归档前主表 ds_cost_detail_tooling.id';
COMMENT ON COLUMN ds_cost_detail_tooling_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_consumable_history ──
CREATE TABLE ds_cost_detail_consumable_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    consumable_price          numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_consumable_history_axis_ver ON ds_cost_detail_consumable_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_consumable_history IS '生产耗材BOM · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_consumable_history.origin_id IS '归档前主表 ds_cost_detail_consumable.id';
COMMENT ON COLUMN ds_cost_detail_consumable_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_packaging_history ──
CREATE TABLE ds_cost_detail_packaging_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    packaging_price           numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_packaging_history_axis_ver ON ds_cost_detail_packaging_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_packaging_history IS '包装材料BOM · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_packaging_history.origin_id IS '归档前主表 ds_cost_detail_packaging.id';
COMMENT ON COLUMN ds_cost_detail_packaging_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_incoming_process_fee_history ──
CREATE TABLE ds_cost_detail_incoming_process_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    incoming_material_no      varchar(128),
    process_fee               numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    loss_rate                 numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_incoming_process_fee_history_axis_ver ON ds_cost_detail_incoming_process_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_incoming_process_fee_history IS '来料加工费 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee_history.origin_id IS '归档前主表 ds_cost_detail_incoming_process_fee.id';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_incoming_other_fee_history ──
CREATE TABLE ds_cost_detail_incoming_other_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    incoming_material_no      varchar(128),
    element_item_seq          integer,
    element_name              varchar(256),
    ratio_pct                 numeric(26,12),
    fee                       numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_incoming_other_fee_history_axis_ver ON ds_cost_detail_incoming_other_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_incoming_other_fee_history IS '来料其他费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee_history.origin_id IS '归档前主表 ds_cost_detail_incoming_other_fee.id';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_incoming_other_fixed_fee_history ──
CREATE TABLE ds_cost_detail_incoming_other_fixed_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    incoming_material_no      varchar(128),
    element_item_seq          integer,
    element_name              varchar(256),
    fee                       numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_incoming_other_fixed_fee_history_axis_ver ON ds_cost_detail_incoming_other_fixed_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_incoming_other_fixed_fee_history IS '来料其他固定费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee_history.origin_id IS '归档前主表 ds_cost_detail_incoming_other_fixed_fee.id';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_process_assembly_fee_history ──
CREATE TABLE ds_cost_detail_process_assembly_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    process_fee               numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    defect_rate               numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_process_assembly_fee_history_axis_ver ON ds_cost_detail_process_assembly_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_process_assembly_fee_history IS '加工费&组装费 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_process_assembly_fee_history.origin_id IS '归档前主表 ds_cost_detail_process_assembly_fee.id';
COMMENT ON COLUMN ds_cost_detail_process_assembly_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_outsourced_process_history ──
CREATE TABLE ds_cost_detail_outsourced_process_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    operation_no              varchar(128),
    outsourced_fee            numeric(26,12),
    currency                  varchar(128),
    unit                      varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_outsourced_process_history_axis_ver ON ds_cost_detail_outsourced_process_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_outsourced_process_history IS '其他外加工成本 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_outsourced_process_history.origin_id IS '归档前主表 ds_cost_detail_outsourced_process.id';
COMMENT ON COLUMN ds_cost_detail_outsourced_process_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_plating_cost_history ──
CREATE TABLE ds_cost_detail_plating_cost_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
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
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_plating_cost_history_axis_ver ON ds_cost_detail_plating_cost_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_plating_cost_history IS '电镀成本 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_plating_cost_history.origin_id IS '归档前主表 ds_cost_detail_plating_cost.id';
COMMENT ON COLUMN ds_cost_detail_plating_cost_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_finished_ratio_fee_history ──
CREATE TABLE ds_cost_detail_finished_ratio_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    element_name              varchar(256),
    ratio_pct                 numeric(26,12),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_finished_ratio_fee_history_axis_ver ON ds_cost_detail_finished_ratio_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_finished_ratio_fee_history IS '成品其他比例费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_finished_ratio_fee_history.origin_id IS '归档前主表 ds_cost_detail_finished_ratio_fee.id';
COMMENT ON COLUMN ds_cost_detail_finished_ratio_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

-- ── ds_cost_detail_finished_fixed_fee_history ──
CREATE TABLE ds_cost_detail_finished_fixed_fee_history (
    id                        bigserial      PRIMARY KEY,
    origin_id                 bigint,
    production_no             varchar(128)   NOT NULL,
    item_seq                  integer,
    element_name              varchar(256),
    fee                       numeric(26,12),
    currency                  varchar(128),
    pricing_unit              varchar(128),
    version_no                integer        NOT NULL,
    row_fingerprint           char(64)       NOT NULL,
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64),
    archived_at               timestamptz    NOT NULL DEFAULT now(),
    archived_by               varchar(64),
    archive_reason            varchar(32)
);
CREATE INDEX idx_ds_cost_detail_finished_fixed_fee_history_axis_ver ON ds_cost_detail_finished_fixed_fee_history (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_finished_fixed_fee_history IS '成品其他固定费用 · 历史版本归档（R-5，只增不改不删）';
COMMENT ON COLUMN ds_cost_detail_finished_fixed_fee_history.origin_id IS '归档前主表 ds_cost_detail_finished_fixed_fee.id';
COMMENT ON COLUMN ds_cost_detail_finished_fixed_fee_history.archive_reason IS 'IMPORT_UPGRADE | MANUAL_UPGRADE';

