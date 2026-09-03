-- ============================================================================
-- task-260902 · 报价与核价建表与导入方案新规范 · 详细核价（cost-detail）主表 19 张
--
-- 依据：dev-docs/task-260902-报价与核价建表与导入方案新规范/字段矩阵.md（唯一建表依据）
--       + 需求文档.md §③ R-1（建表规则）/ R-2（免版本表主键）/ R-5（_history 结构）
-- 服务的 AC：AC-1, AC-2, AC-3, AC-4
--
-- 🚫 本迁移只新增表，不删改任何既有表（CLAUDE.md §3.2）。
-- ⚠️ 列的取舍完全由 字段矩阵.md 决定：白底（主数据 JOIN 展示列）一律不建字段。
-- ============================================================================

-- ── 01. sheet「物料」 → ds_cost_detail_material（免版本） ──────────
CREATE TABLE ds_cost_detail_material (
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
CREATE UNIQUE INDEX uq_ds_cost_detail_material ON ds_cost_detail_material (production_no);
COMMENT ON TABLE ds_cost_detail_material IS '物料 · 免版本';
COMMENT ON COLUMN ds_cost_detail_material.production_no IS '生产料号（必填）';
COMMENT ON COLUMN ds_cost_detail_material.material_name IS '品名（必填）';
COMMENT ON COLUMN ds_cost_detail_material.specification IS '规格';
COMMENT ON COLUMN ds_cost_detail_material.dimension IS '尺寸';
COMMENT ON COLUMN ds_cost_detail_material.old_material_no IS '旧料号';
COMMENT ON COLUMN ds_cost_detail_material.unit_weight IS '单重';

-- ── 02. sheet「物料BOM」 → ds_cost_detail_material_bom（带版本） ──────────
CREATE TABLE ds_cost_detail_material_bom (
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
CREATE INDEX idx_ds_cost_detail_material_bom_axis_ver ON ds_cost_detail_material_bom (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_material_bom IS '物料BOM · 带版本';
COMMENT ON COLUMN ds_cost_detail_material_bom.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_material_bom.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_detail_material_bom.component_type IS '组成类型（对比项）';
COMMENT ON COLUMN ds_cost_detail_material_bom.component_no IS '组成料号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_material_bom.operation_no IS '工序编号（对比项）';
COMMENT ON COLUMN ds_cost_detail_material_bom.usage_characteristic IS '使用特性（对比项）';
COMMENT ON COLUMN ds_cost_detail_material_bom.component_qty IS '组成用量（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_material_bom.component_qty_unit IS '组成用量单位（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_material_bom.base_qty IS '底数（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_material_bom.base_qty_unit IS '底数单位（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_material_bom.material_loss_rate IS '材料损耗率（%）（对比项）';
COMMENT ON COLUMN ds_cost_detail_material_bom.material_fixed_loss IS '材料固定损耗量（对比项）';
COMMENT ON COLUMN ds_cost_detail_material_bom.defect_rate IS '不良率（%）（对比项）';
COMMENT ON COLUMN ds_cost_detail_material_bom.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_material_bom.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 03. sheet「物料与元素BOM」 → ds_cost_detail_element_bom（带版本） ──────────
CREATE TABLE ds_cost_detail_element_bom (
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
CREATE INDEX idx_ds_cost_detail_element_bom_axis_ver ON ds_cost_detail_element_bom (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_element_bom IS '物料与元素BOM · 带版本';
COMMENT ON COLUMN ds_cost_detail_element_bom.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_element_bom.material_part_no IS '材质料号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_element_bom.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_detail_element_bom.element_code IS '元素代码（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_element_bom.content_pct IS '组成含量（%）（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_element_bom.loss_rate IS '损耗率（%）（对比项）';
COMMENT ON COLUMN ds_cost_detail_element_bom.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_element_bom.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 04. sheet「产能」 → ds_cost_detail_capacity（带版本） ──────────
CREATE TABLE ds_cost_detail_capacity (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_detail_capacity_axis_ver ON ds_cost_detail_capacity (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_capacity IS '产能 · 带版本';
COMMENT ON COLUMN ds_cost_detail_capacity.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_capacity.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_capacity.labor_std_price IS '人工标准单价（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_capacity.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_capacity.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_capacity.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_capacity.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 05. sheet「设备折旧成本」 → ds_cost_detail_depreciation（带版本） ──────────
CREATE TABLE ds_cost_detail_depreciation (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_detail_depreciation_axis_ver ON ds_cost_detail_depreciation (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_depreciation IS '设备折旧成本 · 带版本';
COMMENT ON COLUMN ds_cost_detail_depreciation.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_depreciation.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_depreciation.depreciation_price IS '折旧单价（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_depreciation.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_depreciation.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_depreciation.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_depreciation.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 06. sheet「生产设备能耗」 → ds_cost_detail_production_energy（带版本） ──────────
CREATE TABLE ds_cost_detail_production_energy (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_detail_production_energy_axis_ver ON ds_cost_detail_production_energy (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_production_energy IS '生产设备能耗 · 带版本';
COMMENT ON COLUMN ds_cost_detail_production_energy.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_production_energy.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_production_energy.production_energy_price IS '生产能耗单价（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_production_energy.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_production_energy.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_production_energy.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_production_energy.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 07. sheet「辅助设备能耗」 → ds_cost_detail_auxiliary_energy（带版本） ──────────
CREATE TABLE ds_cost_detail_auxiliary_energy (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_detail_auxiliary_energy_axis_ver ON ds_cost_detail_auxiliary_energy (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_auxiliary_energy IS '辅助设备能耗 · 带版本';
COMMENT ON COLUMN ds_cost_detail_auxiliary_energy.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_auxiliary_energy.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_auxiliary_energy.auxiliary_energy_price IS '非生产能耗单价（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_auxiliary_energy.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_auxiliary_energy.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_auxiliary_energy.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_auxiliary_energy.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 08. sheet「模具工装成本」 → ds_cost_detail_tooling（带版本） ──────────
CREATE TABLE ds_cost_detail_tooling (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_detail_tooling_axis_ver ON ds_cost_detail_tooling (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_tooling IS '模具工装成本 · 带版本';
COMMENT ON COLUMN ds_cost_detail_tooling.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_tooling.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_tooling.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_detail_tooling.tooling_no IS '模具台账/工装编号（对比项）';
COMMENT ON COLUMN ds_cost_detail_tooling.tooling_cost IS '单个模具/工装成本（对比项）';
COMMENT ON COLUMN ds_cost_detail_tooling.tooling_life IS '寿命（次）（对比项）';
COMMENT ON COLUMN ds_cost_detail_tooling.cycle_output IS '单循环产量（对比项）';
COMMENT ON COLUMN ds_cost_detail_tooling.tooling_unit_price IS '模具工装成本单价（对比项）';
COMMENT ON COLUMN ds_cost_detail_tooling.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_tooling.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_tooling.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_tooling.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 09. sheet「生产耗材BOM」 → ds_cost_detail_consumable（带版本） ──────────
CREATE TABLE ds_cost_detail_consumable (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_detail_consumable_axis_ver ON ds_cost_detail_consumable (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_consumable IS '生产耗材BOM · 带版本';
COMMENT ON COLUMN ds_cost_detail_consumable.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_consumable.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_consumable.consumable_price IS '耗材成本单价（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_consumable.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_consumable.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_consumable.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_consumable.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 10. sheet「包装材料BOM」 → ds_cost_detail_packaging（带版本） ──────────
CREATE TABLE ds_cost_detail_packaging (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_detail_packaging_axis_ver ON ds_cost_detail_packaging (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_packaging IS '包装材料BOM · 带版本';
COMMENT ON COLUMN ds_cost_detail_packaging.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_packaging.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_packaging.packaging_price IS '包装成本单价（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_packaging.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_packaging.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_packaging.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_packaging.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 11. sheet「来料加工费」 → ds_cost_detail_incoming_process_fee（带版本） ──────────
CREATE TABLE ds_cost_detail_incoming_process_fee (
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
CREATE INDEX idx_ds_cost_detail_incoming_process_fee_axis_ver ON ds_cost_detail_incoming_process_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_incoming_process_fee IS '来料加工费 · 带版本';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee.incoming_material_no IS '来料料号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee.process_fee IS '加工费（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee.loss_rate IS '损耗（%）（对比项）';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_incoming_process_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 12. sheet「来料其他费用」 → ds_cost_detail_incoming_other_fee（带版本） ──────────
CREATE TABLE ds_cost_detail_incoming_other_fee (
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
CREATE INDEX idx_ds_cost_detail_incoming_other_fee_axis_ver ON ds_cost_detail_incoming_other_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_incoming_other_fee IS '来料其他费用 · 带版本';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee.item_seq IS '项次';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee.incoming_material_no IS '来料料号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee.element_item_seq IS '要素项次（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee.ratio_pct IS '比例（%）（对比项）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee.fee IS '费用（对比项）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 13. sheet「来料其他固定费用」 → ds_cost_detail_incoming_other_fixed_fee（带版本） ──────────
CREATE TABLE ds_cost_detail_incoming_other_fixed_fee (
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
CREATE INDEX idx_ds_cost_detail_incoming_other_fixed_fee_axis_ver ON ds_cost_detail_incoming_other_fixed_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_incoming_other_fixed_fee IS '来料其他固定费用 · 带版本';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee.incoming_material_no IS '来料料号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee.element_item_seq IS '要素项次（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee.fee IS '费用（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_incoming_other_fixed_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 14. sheet「加工费&组装费」 → ds_cost_detail_process_assembly_fee（带版本） ──────────
CREATE TABLE ds_cost_detail_process_assembly_fee (
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
CREATE INDEX idx_ds_cost_detail_process_assembly_fee_axis_ver ON ds_cost_detail_process_assembly_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_process_assembly_fee IS '加工费&组装费 · 带版本';
COMMENT ON COLUMN ds_cost_detail_process_assembly_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_process_assembly_fee.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_process_assembly_fee.process_fee IS '加工费（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_process_assembly_fee.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_process_assembly_fee.unit IS '计量单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_process_assembly_fee.defect_rate IS '不良率/拒收率（%）（对比项）';
COMMENT ON COLUMN ds_cost_detail_process_assembly_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_process_assembly_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 15. sheet「其他外加工成本」 → ds_cost_detail_outsourced_process（带版本） ──────────
CREATE TABLE ds_cost_detail_outsourced_process (
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
CREATE INDEX idx_ds_cost_detail_outsourced_process_axis_ver ON ds_cost_detail_outsourced_process (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_outsourced_process IS '其他外加工成本 · 带版本';
COMMENT ON COLUMN ds_cost_detail_outsourced_process.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_outsourced_process.operation_no IS '工序编号（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_outsourced_process.outsourced_fee IS '外加工费用（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_outsourced_process.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_outsourced_process.unit IS '单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_outsourced_process.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_outsourced_process.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 16. sheet「电镀成本」 → ds_cost_detail_plating_cost（带版本） ──────────
CREATE TABLE ds_cost_detail_plating_cost (
    id                        bigserial      PRIMARY KEY,
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
    updated_by                varchar(64)
);
CREATE INDEX idx_ds_cost_detail_plating_cost_axis_ver ON ds_cost_detail_plating_cost (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_plating_cost IS '电镀成本 · 带版本';
COMMENT ON COLUMN ds_cost_detail_plating_cost.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_plating_cost.plating_scheme_no IS '电镀方案编号（对比项）';
COMMENT ON COLUMN ds_cost_detail_plating_cost.plating_version IS '版本编号（对比项）';
COMMENT ON COLUMN ds_cost_detail_plating_cost.plating_process_fee IS '电镀加工费（对比项）';
COMMENT ON COLUMN ds_cost_detail_plating_cost.plating_material_fee IS '电镀材料费（对比项）';
COMMENT ON COLUMN ds_cost_detail_plating_cost.currency IS '货币（对比项）';
COMMENT ON COLUMN ds_cost_detail_plating_cost.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_plating_cost.defect_rate IS '不良率（%）（对比项）';
COMMENT ON COLUMN ds_cost_detail_plating_cost.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_plating_cost.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 17. sheet「成品其他比例费用」 → ds_cost_detail_finished_ratio_fee（带版本） ──────────
CREATE TABLE ds_cost_detail_finished_ratio_fee (
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
CREATE INDEX idx_ds_cost_detail_finished_ratio_fee_axis_ver ON ds_cost_detail_finished_ratio_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_finished_ratio_fee IS '成品其他比例费用 · 带版本';
COMMENT ON COLUMN ds_cost_detail_finished_ratio_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_finished_ratio_fee.item_seq IS '项次（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_finished_ratio_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_finished_ratio_fee.ratio_pct IS '比例（%）（对比项）';
COMMENT ON COLUMN ds_cost_detail_finished_ratio_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_finished_ratio_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 18. sheet「成品其他固定费用」 → ds_cost_detail_finished_fixed_fee（带版本） ──────────
CREATE TABLE ds_cost_detail_finished_fixed_fee (
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
CREATE INDEX idx_ds_cost_detail_finished_fixed_fee_axis_ver ON ds_cost_detail_finished_fixed_fee (production_no, version_no);
COMMENT ON TABLE ds_cost_detail_finished_fixed_fee IS '成品其他固定费用 · 带版本';
COMMENT ON COLUMN ds_cost_detail_finished_fixed_fee.production_no IS '生产料号（轴/必填）';
COMMENT ON COLUMN ds_cost_detail_finished_fixed_fee.item_seq IS '项次（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_finished_fixed_fee.element_name IS '要素名称（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_finished_fixed_fee.fee IS '费用（对比项/必填）';
COMMENT ON COLUMN ds_cost_detail_finished_fixed_fee.currency IS '币种（对比项）';
COMMENT ON COLUMN ds_cost_detail_finished_fixed_fee.pricing_unit IS '计价单位（对比项）';
COMMENT ON COLUMN ds_cost_detail_finished_fixed_fee.version_no IS '该轴值的当前版本号，从 1 起（R-1）';
COMMENT ON COLUMN ds_cost_detail_finished_fixed_fee.row_fingerprint IS '对比项列的 SHA-256 行指纹（R-3）';

-- ── 19. sheet「电镀方案」 → ds_cost_detail_plating_scheme（免版本） ──────────
CREATE TABLE ds_cost_detail_plating_scheme (
    id                        bigserial      PRIMARY KEY,
    scheme_no                 varchar(128)   NOT NULL,
    scheme_version            varchar(128)   NOT NULL,
    item_seq                  integer        NOT NULL,
    plating_element           varchar(256),
    plating_area              numeric(26,12),
    coating_thickness         numeric(26,12),
    plating_requirement       varchar(256),
    density                   numeric(26,12),
    source                    varchar(16)    NOT NULL DEFAULT 'IMPORT',
    created_at                timestamptz    NOT NULL DEFAULT now(),
    created_by                varchar(64),
    updated_at                timestamptz,
    updated_by                varchar(64)
);
CREATE UNIQUE INDEX uq_ds_cost_detail_plating_scheme ON ds_cost_detail_plating_scheme (scheme_no, scheme_version, item_seq);
COMMENT ON TABLE ds_cost_detail_plating_scheme IS '电镀方案 · 免版本';
COMMENT ON COLUMN ds_cost_detail_plating_scheme.scheme_no IS '方案编号（必填）';
COMMENT ON COLUMN ds_cost_detail_plating_scheme.scheme_version IS '版本（必填）';
COMMENT ON COLUMN ds_cost_detail_plating_scheme.item_seq IS '项次（必填）';
COMMENT ON COLUMN ds_cost_detail_plating_scheme.plating_element IS '电镀元素名称';
COMMENT ON COLUMN ds_cost_detail_plating_scheme.plating_area IS '电镀面积（cm2）';
COMMENT ON COLUMN ds_cost_detail_plating_scheme.coating_thickness IS '镀层厚度（μm）';
COMMENT ON COLUMN ds_cost_detail_plating_scheme.plating_requirement IS '电镀要求';
COMMENT ON COLUMN ds_cost_detail_plating_scheme.density IS '密度（g/cm3)';

