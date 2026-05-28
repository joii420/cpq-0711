-- ============================================================
-- V219: V6 基础数据 - BOM 主子表 (4 张)
-- 来源: docs/table/数据库表结构设计文档.md §3~§6
-- 依赖: V218 (material_master 已建)
--
-- 与 V44 mat_bom 关系：mat_bom 是合并的单表（INCOMING/ELEMENT），
-- 新设计拆为 4 张表 (material_bom + material_bom_item + element_bom + element_bom_item)，
-- 按 system_type (QUOTE/PRICING/BOTH) + bom_type (MATERIAL/ASSEMBLY) 维度拓展。
-- 两套表暂并存运行。
-- ============================================================

-- ============== 3. 物料BOM主表 material_bom ===================
CREATE TABLE IF NOT EXISTS material_bom (
    id               UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    system_type      VARCHAR(10)     NOT NULL,
    customer_no      VARCHAR(20)     NOT NULL,
    bom_type         VARCHAR(20)     NOT NULL,
    bom_version      VARCHAR(20)     NOT NULL,
    bom_status       VARCHAR(20),
    plant            VARCHAR(20),
    valid_from       DATE,
    valid_to         DATE,
    material_no      VARCHAR(20)     NOT NULL,
    characteristic   VARCHAR(100),
    batch_qty        VARCHAR(100),
    production_unit  VARCHAR(100),
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by       UUID,
    updated_by       UUID,
    CONSTRAINT chk_material_bom_system_type CHECK (system_type IN ('QUOTE','PRICING','BOTH')),
    CONSTRAINT chk_material_bom_type        CHECK (bom_type    IN ('MATERIAL','ASSEMBLY')),
    CONSTRAINT chk_material_bom_status      CHECK (bom_status IS NULL OR bom_status IN ('DRAFT','RELEASED','OBSOLETE'))
);

-- 注：uq_material_bom / uq_element_bom 索引名已被 V137 costing_part_* 表占用 (PG 索引全 schema 唯一)
--     故 V6 系列索引一律加 _v6 后缀避让
CREATE UNIQUE INDEX uq_material_bom_v6
    ON material_bom(system_type, customer_no, material_no, bom_version, COALESCE(characteristic, ''));
CREATE INDEX idx_material_bom_lookup ON material_bom(customer_no, material_no, bom_version);
CREATE INDEX idx_material_bom_valid  ON material_bom(material_no, valid_from, valid_to);

COMMENT ON TABLE material_bom IS 'V6 §3 物料BOM主表（system_type+customer_no+material_no+bom_version+characteristic 唯一）';

-- ============== 4. 物料BOM子表 material_bom_item ==============
CREATE TABLE IF NOT EXISTS material_bom_item (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    system_type           VARCHAR(10)    NOT NULL,
    customer_no           VARCHAR(20)    NOT NULL,
    material_no           VARCHAR(20)    NOT NULL,
    characteristic        VARCHAR(100),
    seq_no                INTEGER,
    component_no          VARCHAR(20),
    part_no               VARCHAR(20),
    effective_datetime    TIMESTAMPTZ,
    expire_datetime       TIMESTAMPTZ,
    operation_no          VARCHAR(20),
    operation_seq         VARCHAR(20),
    item_seq              INTEGER,
    issue_unit            VARCHAR(20),
    composition_qty       DECIMAL(18,6),
    base_qty              DECIMAL(18,6),
    component_usage_type  VARCHAR(20),
    feature_mgmt          VARCHAR(20),
    upper_limit_pct       DECIMAL(10,4),
    lower_limit_pct       DECIMAL(10,4),
    scrap_batch           DECIMAL(18,6),
    scrap_rate            DECIMAL(10,4),
    fixed_scrap           DECIMAL(18,6),
    issue_location        VARCHAR(50),
    issue_storage         VARCHAR(50),
    fas_group             VARCHAR(20),
    plug_position         VARCHAR(50),
    ref_rd_center         VARCHAR(50),
    is_optional           BOOLEAN,
    wo_expand_option      VARCHAR(20),
    is_purchase_replace   BOOLEAN,
    component_lead_time   DECIMAL(18,6),
    main_substitute       VARCHAR(20),
    attached_part         VARCHAR(20),
    ecn_no                VARCHAR(30),
    use_qty_formula       BOOLEAN,
    qty_formula           VARCHAR(500),
    scrap_rate_type       VARCHAR(20),
    is_backflush          BOOLEAN,
    is_customer_supply    BOOLEAN,
    defect_rate           DECIMAL(10,4),
    calc_type             VARCHAR(20),
    recovery_discount     DECIMAL(10,4),
    recovery_currency     VARCHAR(10),
    recovery_unit         VARCHAR(20),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID,
    CONSTRAINT chk_material_bom_item_system_type CHECK (system_type IN ('QUOTE','PRICING','BOTH'))
);

CREATE UNIQUE INDEX uq_material_bom_item
    ON material_bom_item(
        system_type, customer_no, material_no,
        COALESCE(characteristic, ''),
        COALESCE(seq_no, 0),
        COALESCE(component_no, ''),
        COALESCE(part_no, '')
    );
CREATE INDEX idx_material_bom_item_parent ON material_bom_item(customer_no, material_no, characteristic);
CREATE INDEX idx_material_bom_item_comp   ON material_bom_item(component_no);

COMMENT ON TABLE material_bom_item IS 'V6 §4 物料BOM子表（按 system+customer+主件+特性+项次+组件唯一）';

-- ============== 5. 元素BOM主表 element_bom ====================
CREATE TABLE IF NOT EXISTS element_bom (
    id               UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    system_type      VARCHAR(10)     NOT NULL,
    customer_no      VARCHAR(20)     NOT NULL,
    bom_type         VARCHAR(20)     NOT NULL,
    bom_status       VARCHAR(20),
    plant            VARCHAR(20),
    valid_from       DATE,
    valid_to         DATE,
    material_no      VARCHAR(20)     NOT NULL,
    characteristic   VARCHAR(100)    NOT NULL,
    batch_qty        VARCHAR(100),
    production_unit  VARCHAR(100),
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by       UUID,
    updated_by       UUID,
    CONSTRAINT chk_element_bom_system_type CHECK (system_type IN ('QUOTE','PRICING','BOTH')),
    CONSTRAINT chk_element_bom_type        CHECK (bom_type    IN ('MATERIAL','ASSEMBLY')),
    CONSTRAINT chk_element_bom_status      CHECK (bom_status IS NULL OR bom_status IN ('DRAFT','RELEASED','OBSOLETE'))
);

CREATE UNIQUE INDEX uq_element_bom_v6
    ON element_bom(system_type, customer_no, material_no, characteristic);
CREATE INDEX idx_element_bom_lookup ON element_bom(customer_no, material_no);

COMMENT ON TABLE element_bom IS 'V6 §5 元素BOM主表（characteristic 必填，用作元素维度键）';

-- ============== 6. 元素BOM子表 element_bom_item ===============
CREATE TABLE IF NOT EXISTS element_bom_item (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    system_type           VARCHAR(10)    NOT NULL,
    customer_no           VARCHAR(20)    NOT NULL,
    material_no           VARCHAR(20)    NOT NULL,
    characteristic        VARCHAR(100)   NOT NULL,
    component_no          VARCHAR(20),
    part_no               VARCHAR(20),
    effective_datetime    TIMESTAMPTZ,
    expire_datetime       TIMESTAMPTZ,
    operation_no          VARCHAR(20),
    operation_seq         VARCHAR(20),
    seq_no                INTEGER,
    issue_unit            VARCHAR(20),
    composition_qty       DECIMAL(18,6),
    base_qty              DECIMAL(18,6),
    component_usage_type  VARCHAR(20),
    feature_mgmt          VARCHAR(20),
    content               DECIMAL(18,6),
    upper_limit_pct       DECIMAL(10,4),
    lower_limit_pct       DECIMAL(10,4),
    scrap_batch           DECIMAL(18,6),
    scrap_rate            DECIMAL(10,4),
    defect_rate           DECIMAL(10,4),
    fixed_scrap           DECIMAL(18,6),
    issue_location        VARCHAR(50),
    issue_storage         VARCHAR(50),
    fas_group             VARCHAR(20),
    plug_position         VARCHAR(50),
    ref_rd_center         VARCHAR(50),
    is_optional           BOOLEAN,
    wo_expand_option      VARCHAR(20),
    is_purchase_replace   BOOLEAN,
    component_lead_time   DECIMAL(18,6),
    main_substitute       VARCHAR(20),
    attached_part         VARCHAR(20),
    ecn_no                VARCHAR(30),
    use_qty_formula       BOOLEAN,
    qty_formula           VARCHAR(500),
    scrap_rate_type       VARCHAR(20),
    is_backflush          BOOLEAN,
    is_customer_supply    BOOLEAN,
    recovery_discount     DECIMAL(10,4),
    recovery_currency     VARCHAR(10),
    recovery_unit         VARCHAR(20),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID,
    CONSTRAINT chk_element_bom_item_system_type CHECK (system_type IN ('QUOTE','PRICING','BOTH'))
);

CREATE UNIQUE INDEX uq_element_bom_item
    ON element_bom_item(
        system_type, customer_no, material_no, characteristic,
        COALESCE(seq_no, 0),
        COALESCE(component_no, ''),
        COALESCE(part_no, '')
    );
CREATE INDEX idx_element_bom_item_parent  ON element_bom_item(customer_no, material_no, characteristic);
CREATE INDEX idx_element_bom_item_comp    ON element_bom_item(component_no);

COMMENT ON TABLE element_bom_item IS 'V6 §6 元素BOM子表（在物料BOM子表基础上加 content 含量字段）';
