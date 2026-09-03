-- ============================================================================
-- task-260902 · 报价与核价建表与导入方案新规范 · 基础核价（cost-basic）主表 10 张
--
-- 依据：dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md（唯一建表依据）
--       + 需求文档.md §③ R-1（建表规则）/ R-2（免版本表主键）/ R-5（_history 结构）
-- 服务的 AC：AC-1, AC-2, AC-3, AC-4
--
-- 🚫 本迁移只新增表，不删改任何既有表（CLAUDE.md §3.2）。
-- ⚠️ 列的取舍完全由 字段矩阵.md 决定：白底（主数据 JOIN 展示列）一律不建字段。
-- ============================================================================

-- ── 01. sheet「物料」 → ds_cost_basic_material（免版本） ──────────
CREATE TABLE ds_cost_basic_material (
    id                        bigserial      PRIMARY KEY,
    production_no             varchar(128)   NOT NULL,
    material_name             varchar(128),
    specification             varchar(128),
    dimension                 varchar(128),
    old_material_no           varchar(128),
    unit_weight               numeric(26,12),
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE UNIQUE INDEX uq_ds_cost_basic_material ON ds_cost_basic_material (production_no);
COMMENT ON TABLE ds_cost_basic_material IS '物料 · 免版本';
COMMENT ON COLUMN ds_cost_basic_material.production_no IS '生产料号（必填）';
COMMENT ON COLUMN ds_cost_basic_material.material_name IS '品名（必填）';
COMMENT ON COLUMN ds_cost_basic_material.specification IS '规格';
COMMENT ON COLUMN ds_cost_basic_material.dimension IS '尺寸';
COMMENT ON COLUMN ds_cost_basic_material.old_material_no IS '旧料号';
COMMENT ON COLUMN ds_cost_basic_material.unit_weight IS '单重';

-- ── 02. sheet「物料BOM」 → ds_cost_basic_material_bom（带版本） ──────────
CREATE TABLE ds_cost_basic_material_bom (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_basic_material_bom_axis_ver ON ds_cost_basic_material_bom (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_material_bom IS '物料BOM · 带版本';
COMMENT ON COLUMN ds_cost_basic_material_bom.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_basic_material_bom.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_basic_material_bom.component_type IS '组成类型（对比项）';
COMMENT ON COLUMN ds_cost_basic_material_bom.component_no IS '组成料号（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_material_bom.operation_no IS '工序编号（对比项）';
COMMENT ON COLUMN ds_cost_basic_material_bom.usage_characteristic IS '使用特性（对比项）';
COMMENT ON COLUMN ds_cost_basic_material_bom.component_qty IS '组成用量（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_material_bom.component_qty_unit IS '组成用量单位（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_material_bom.base_qty IS '底数（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_material_bom.base_qty_unit IS '底数单位（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_material_bom.material_loss_rate IS '材料损耗率（%）（对比项）';
COMMENT ON COLUMN ds_cost_basic_material_bom.material_fixed_loss IS '材料固定损耗量（对比项）';
COMMENT ON COLUMN ds_cost_basic_material_bom.defect_rate IS '不良率（%）（对比项）';
COMMENT ON COLUMN ds_cost_basic_material_bom.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_basic_material_bom.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 03. sheet「物料与元素BOM」 → ds_cost_basic_element_bom（带版本） ──────────
CREATE TABLE ds_cost_basic_element_bom (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_basic_element_bom_axis_ver ON ds_cost_basic_element_bom (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_element_bom IS '物料与元素BOM · 带版本';
COMMENT ON COLUMN ds_cost_basic_element_bom.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_basic_element_bom.material_part_no IS '材质料号（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_element_bom.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_basic_element_bom.element_code IS '元素代码（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_element_bom.content_pct IS '组成含量（%）（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_element_bom.loss_rate IS '损耗率（%）（对比项）';
COMMENT ON COLUMN ds_cost_basic_element_bom.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_basic_element_bom.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 04. sheet「来料加工费」 → ds_cost_basic_incoming_process_fee（带版本） ──────────
CREATE TABLE ds_cost_basic_incoming_process_fee (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_basic_incoming_process_fee_axis_ver ON ds_cost_basic_incoming_process_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_incoming_process_fee IS '来料加工费 · 带版本';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee.incoming_material_no IS '来料料号（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee.process_fee IS '加工费（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee.loss_rate IS '损耗（%）（对比项）';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_basic_incoming_process_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 05. sheet「来料其他费用」 → ds_cost_basic_incoming_other_fee（带版本） ──────────
CREATE TABLE ds_cost_basic_incoming_other_fee (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_basic_incoming_other_fee_axis_ver ON ds_cost_basic_incoming_other_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_incoming_other_fee IS '来料其他费用 · 带版本';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee.item_seq IS '项次';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee.incoming_material_no IS '来料料号（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee.element_item_seq IS '要素项次（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee.ratio_pct IS '比例（%）（对比项）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee.fee IS '费用（对比项）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 06. sheet「来料其他固定费用」 → ds_cost_basic_incoming_other_fixed_fee（带版本） ──────────
CREATE TABLE ds_cost_basic_incoming_other_fixed_fee (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_basic_incoming_other_fixed_fee_axis_ver ON ds_cost_basic_incoming_other_fixed_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_incoming_other_fixed_fee IS '来料其他固定费用 · 带版本';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee.incoming_material_no IS '来料料号（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee.element_item_seq IS '要素项次（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee.fee IS '费用（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_basic_incoming_other_fixed_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 07. sheet「加工费&组装费」 → ds_cost_basic_process_assembly_fee（带版本） ──────────
CREATE TABLE ds_cost_basic_process_assembly_fee (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_basic_process_assembly_fee_axis_ver ON ds_cost_basic_process_assembly_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_process_assembly_fee IS '加工费&组装费 · 带版本';
COMMENT ON COLUMN ds_cost_basic_process_assembly_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_basic_process_assembly_fee.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_process_assembly_fee.process_fee IS '加工费（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_process_assembly_fee.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_basic_process_assembly_fee.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_basic_process_assembly_fee.defect_rate IS '不良率/拒收率（%）（对比项）';
COMMENT ON COLUMN ds_cost_basic_process_assembly_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_basic_process_assembly_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 08. sheet「其他外加工成本」 → ds_cost_basic_outsourced_process（带版本） ──────────
CREATE TABLE ds_cost_basic_outsourced_process (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_basic_outsourced_process_axis_ver ON ds_cost_basic_outsourced_process (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_outsourced_process IS '其他外加工成本 · 带版本';
COMMENT ON COLUMN ds_cost_basic_outsourced_process.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_basic_outsourced_process.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_outsourced_process.outsourced_fee IS '外加工费用（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_outsourced_process.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_basic_outsourced_process.unit IS '单位（对比项）';
COMMENT ON COLUMN ds_cost_basic_outsourced_process.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_basic_outsourced_process.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 09. sheet「成品其他比例费用」 → ds_cost_basic_finished_ratio_fee（带版本） ──────────
CREATE TABLE ds_cost_basic_finished_ratio_fee (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_basic_finished_ratio_fee_axis_ver ON ds_cost_basic_finished_ratio_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_finished_ratio_fee IS '成品其他比例费用 · 带版本';
COMMENT ON COLUMN ds_cost_basic_finished_ratio_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_basic_finished_ratio_fee.item_seq IS '项次（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_finished_ratio_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_finished_ratio_fee.ratio_pct IS '比例（%）（对比项）';
COMMENT ON COLUMN ds_cost_basic_finished_ratio_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_basic_finished_ratio_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 10. sheet「成品其他固定费用」 → ds_cost_basic_finished_fixed_fee（带版本） ──────────
CREATE TABLE ds_cost_basic_finished_fixed_fee (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_basic_finished_fixed_fee_axis_ver ON ds_cost_basic_finished_fixed_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_basic_finished_fixed_fee IS '成品其他固定费用 · 带版本';
COMMENT ON COLUMN ds_cost_basic_finished_fixed_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_basic_finished_fixed_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_basic_finished_fixed_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_finished_fixed_fee.fee IS '费用（对比项/必填）';
COMMENT ON COLUMN ds_cost_basic_finished_fixed_fee.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_basic_finished_fixed_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_cost_basic_finished_fixed_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_basic_finished_fixed_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

