-- ============================================================
-- V41: 14 张物理业务表落地（路线 X Phase 1）
-- Ref: docs/superpowers/specs/2026-04-25-cpq-design-v5.md §5
--      docs/superpowers/specs/2026-04-26-cpq-design-v5.1.md §6.1 §2.2
-- ============================================================

-- ============================================================
-- 全局表 (4 张) — 不含 customer_id，跨客户共享
-- ============================================================

-- ============== mat_part — 生产料号（含单重）§5.1.1 ==============
CREATE TABLE IF NOT EXISTS mat_part (
    part_no              VARCHAR(64)  PRIMARY KEY,
    part_name            VARCHAR(128),
    specification        VARCHAR(128),
    size_info            VARCHAR(128),
    category_id          UUID         REFERENCES product_category(id),
    unit_weight          DECIMAL(18,4),
    weight_unit          VARCHAR(16),
    status_code          VARCHAR(4)   NOT NULL DEFAULT 'Y',
    is_pending_category  BOOLEAN      NOT NULL DEFAULT false,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by           UUID,
    updated_by           UUID,
    CONSTRAINT chk_mat_part_status_code CHECK (status_code IN ('Y','N'))
);

CREATE INDEX idx_mat_part_category ON mat_part(category_id, status_code);
CREATE INDEX idx_mat_part_pending  ON mat_part(is_pending_category);

COMMENT ON TABLE mat_part IS 'v5.0 §5.1.1 生产料号主档（含单重）';

-- ============== mat_bom — 统一 BOM 表（合并来料+元素）§5.1.3 ==============
CREATE TABLE IF NOT EXISTS mat_bom (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    bom_type              VARCHAR(16)  NOT NULL,
    hf_part_no            VARCHAR(64)  NOT NULL REFERENCES mat_part(part_no),
    seq_no                INT          NOT NULL,
    input_material_no     VARCHAR(64),
    input_material_name   VARCHAR(128),
    loss_rate             DECIMAL(10,4),
    gross_qty             DECIMAL(18,4),
    net_qty               DECIMAL(18,4),
    gross_unit            VARCHAR(16),
    net_unit              VARCHAR(16),
    -- INCOMING 专用
    output_material_type  VARCHAR(64),
    defect_rate           DECIMAL(10,4),
    -- ELEMENT 专用
    element_name          VARCHAR(64),
    composition_pct       DECIMAL(10,4),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID,
    CONSTRAINT chk_mat_bom_type CHECK (bom_type IN ('INCOMING','ELEMENT'))
);

CREATE INDEX idx_mat_bom_type_part ON mat_bom(bom_type, hf_part_no, seq_no);
CREATE UNIQUE INDEX uq_mat_bom_row ON mat_bom(
    bom_type, hf_part_no, seq_no,
    COALESCE(input_material_no, ''),
    COALESCE(element_name, '')
);

COMMENT ON TABLE mat_bom IS 'v5.0 §5.1.3 统一 BOM 表（合并来料 + 元素）';

-- ============== mat_process — 工艺基础（含 customer_id，BIZ-2）§5.2.1 + v5.1 §2.2 ==============
CREATE TABLE IF NOT EXISTS mat_process (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id           UUID         NOT NULL REFERENCES customer(id),
    hf_part_no            VARCHAR(64)  NOT NULL REFERENCES mat_part(part_no),
    version               INT          NOT NULL DEFAULT 1,
    is_current            BOOLEAN      NOT NULL DEFAULT true,
    seq_no                INT          NOT NULL,
    process_code          VARCHAR(32),
    assembly_process      VARCHAR(64),
    sub_seq_no            INT,
    component_part_no     VARCHAR(64),
    component_name        VARCHAR(128),
    supplier_code         VARCHAR(32),
    supplier_name         VARCHAR(128),
    quantity              DECIMAL(18,4),
    quantity_unit         VARCHAR(16),
    unit_price            DECIMAL(18,4),
    freight               DECIMAL(18,4),
    currency              VARCHAR(8),
    price_unit            VARCHAR(16),
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    imported_by           UUID,
    import_record_id      UUID,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID,
    CONSTRAINT chk_mat_process_status CHECK (status IN ('ACTIVE','DELETED'))
);

CREATE INDEX idx_mat_process_cust_part ON mat_process(customer_id, hf_part_no, version);
CREATE INDEX idx_mat_process_current   ON mat_process(is_current);
CREATE UNIQUE INDEX uq_mat_process_row ON mat_process(customer_id, hf_part_no, version, seq_no, sub_seq_no);
CREATE UNIQUE INDEX uq_mat_process_current ON mat_process(customer_id, hf_part_no, seq_no, sub_seq_no)
    WHERE is_current = true;

COMMENT ON TABLE mat_process IS 'v5.0 §5.2.1 工艺基础（含 customer_id，BIZ-2 跨客户差异化）';

-- ============== plating_plan — 电镀方案 §5.1.4 ==============
CREATE TABLE IF NOT EXISTS plating_plan (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code            VARCHAR(32)  NOT NULL,
    version              VARCHAR(16)  NOT NULL,
    seq_no               INT          NOT NULL,
    plating_element      VARCHAR(64),
    plating_area         DECIMAL(18,4),
    coating_thickness    DECIMAL(10,4),
    plating_requirement  VARCHAR(256),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by           UUID,
    updated_by           UUID
);

CREATE UNIQUE INDEX uq_plating_plan_row ON plating_plan(plan_code, version, seq_no);

COMMENT ON TABLE plating_plan IS 'v5.0 §5.1.4 电镀方案';

-- ============================================================
-- 客户级表 (10 张) — 含 customer_id
-- ============================================================

-- ============== mat_fee — 统一费用表 §5.2.2 ==============
CREATE TABLE IF NOT EXISTS mat_fee (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id              UUID         NOT NULL REFERENCES customer(id),
    hf_part_no               VARCHAR(64)  NOT NULL REFERENCES mat_part(part_no),
    version                  INT          NOT NULL DEFAULT 1,
    is_current               BOOLEAN      NOT NULL DEFAULT true,
    fee_type                 VARCHAR(32)  NOT NULL,
    seq_no                   INT          NOT NULL,
    fee_value                DECIMAL(18,4),
    fee_ratio                DECIMAL(10,4),
    currency                 VARCHAR(8),
    price_unit               VARCHAR(16),
    -- 维度字段
    dim_input_material_no    VARCHAR(64),
    dim_input_material_name  VARCHAR(128),
    dim_element_name         VARCHAR(128),
    dim_assembly_process     VARCHAR(64),
    dim_sub_seq_no           INT,
    -- INCOMING_FIXED 专用
    price_floating           BOOLEAN,
    settlement_rise_ratio    DECIMAL(10,4),
    fixed_rise_value         DECIMAL(18,4),
    rise_currency            VARCHAR(8),
    rise_unit                VARCHAR(16),
    -- ASSEMBLY_PROCESS 专用
    reject_rate              DECIMAL(10,4),
    status                   VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    imported_by              UUID,
    import_record_id         UUID,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by               UUID,
    updated_by               UUID,
    CONSTRAINT chk_mat_fee_type CHECK (fee_type IN (
        'INCOMING_FIXED','INCOMING_OTHER','FINISHED_FIXED','FINISHED_OTHER','ASSEMBLY_PROCESS'
    )),
    CONSTRAINT chk_mat_fee_status CHECK (status IN ('ACTIVE','DELETED'))
);

CREATE INDEX idx_mat_fee_cust_type ON mat_fee(customer_id, fee_type, hf_part_no, version);
CREATE INDEX idx_mat_fee_current   ON mat_fee(is_current);

COMMENT ON TABLE mat_fee IS 'v5.0 §5.2.2 统一费用表（含 customer_id + version）';

-- ============== plating_fee — 电镀费用 §5.2.3 ==============
CREATE TABLE IF NOT EXISTS plating_fee (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id           UUID         NOT NULL REFERENCES customer(id),
    hf_part_no            VARCHAR(64)  NOT NULL REFERENCES mat_part(part_no),
    version               INT          NOT NULL DEFAULT 1,
    is_current            BOOLEAN      NOT NULL DEFAULT true,
    plating_plan_code     VARCHAR(32),
    plan_version          VARCHAR(16),
    plating_process_fee   DECIMAL(18,4),
    plating_material_fee  DECIMAL(18,4),
    currency              VARCHAR(8),
    price_unit            VARCHAR(16),
    defect_rate           DECIMAL(10,4),
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    imported_by           UUID,
    import_record_id      UUID,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID,
    CONSTRAINT chk_plating_fee_status CHECK (status IN ('ACTIVE','DELETED'))
);

CREATE INDEX idx_plating_fee_cust  ON plating_fee(customer_id, hf_part_no, version);
CREATE INDEX idx_plating_fee_curr  ON plating_fee(is_current);

COMMENT ON TABLE plating_fee IS 'v5.0 §5.2.3 电镀费用（含 customer_id + version）';

-- ============== mat_customer_part_mapping — 跨客户料号映射 §5.1.2 ==============
CREATE TABLE IF NOT EXISTS mat_customer_part_mapping (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id           UUID         NOT NULL REFERENCES customer(id),
    customer_part_name    VARCHAR(128),
    customer_product_no   VARCHAR(64),
    customer_drawing_no   VARCHAR(64),
    hf_part_no            VARCHAR(64)  NOT NULL REFERENCES mat_part(part_no),
    payment_method        VARCHAR(64),
    base_currency         VARCHAR(8),
    quote_currency        VARCHAR(8),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID
);

CREATE UNIQUE INDEX uq_mat_cust_part ON mat_customer_part_mapping(customer_id, customer_product_no);

COMMENT ON TABLE mat_customer_part_mapping IS 'v5.0 §5.1.2 跨客户料号对照';

-- ============== element_price_source — 元素价格来源 §5.3.1 ==============
CREATE TABLE IF NOT EXISTS element_price_source (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    source_name     VARCHAR(128) NOT NULL,
    source_url      VARCHAR(256),
    source_type     VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    description     TEXT,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    CONSTRAINT chk_eps_source_type CHECK (source_type IN ('HTML_SCRAPE','API','MANUAL')),
    CONSTRAINT chk_eps_status      CHECK (status IN ('ACTIVE','DISABLED'))
);

CREATE UNIQUE INDEX uq_eps_name_url ON element_price_source(source_name, COALESCE(source_url, ''));

COMMENT ON TABLE element_price_source IS 'v5.0 §5.3.1 元素价格来源（v1 仅 schema）';

-- ============== element_price_fetch_rule — 抓取规则 §5.3.2 ==============
CREATE TABLE IF NOT EXISTS element_price_fetch_rule (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_name        VARCHAR(128) NOT NULL,
    rule_code        VARCHAR(64)  NOT NULL UNIQUE,
    rule_definition  JSONB,
    description      TEXT,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       UUID,
    updated_by       UUID,
    CONSTRAINT chk_epfr_status CHECK (status IN ('ACTIVE','DISABLED'))
);

COMMENT ON TABLE element_price_fetch_rule IS 'v5.0 §5.3.2 元素价格抓取规则（v1 仅 schema）';

-- ============== element_price — 客户元素价格配置 §5.2.4 ==============
-- 注：source_id / fetch_rule_id 在 V42 改为 nullable（v5.1 §6.3），此处先建为 nullable
CREATE TABLE IF NOT EXISTS element_price (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id       UUID         NOT NULL REFERENCES customer(id),
    element_name      VARCHAR(64)  NOT NULL,
    version           INT          NOT NULL DEFAULT 1,
    is_current        BOOLEAN      NOT NULL DEFAULT true,
    source_id         UUID         REFERENCES element_price_source(id),
    fetch_rule_id     UUID         REFERENCES element_price_fetch_rule(id),
    premium_price     DECIMAL(18,4),
    currency          VARCHAR(8),
    price_unit        VARCHAR(16),
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    imported_by       UUID,
    import_record_id  UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        UUID,
    updated_by        UUID,
    CONSTRAINT chk_element_price_status CHECK (status IN ('ACTIVE','DELETED'))
);

CREATE INDEX idx_element_price_cust ON element_price(customer_id, element_name, version);
CREATE INDEX idx_element_price_curr ON element_price(is_current);
CREATE UNIQUE INDEX uq_element_price_ver ON element_price(customer_id, element_name, version);
CREATE UNIQUE INDEX uq_element_price_curr ON element_price(customer_id, element_name)
    WHERE is_current = true;

COMMENT ON TABLE element_price IS 'v5.0 §5.2.4 客户元素价格配置（v1 仅 schema，source_id/fetch_rule_id nullable）';

-- ============== element_daily_price — 元素每日价格 §5.3.3 ==============
CREATE TABLE IF NOT EXISTS element_daily_price (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    element_name       VARCHAR(64)  NOT NULL,
    source_id          UUID         REFERENCES element_price_source(id),
    price_date         DATE         NOT NULL,
    raw_price          DECIMAL(18,4),
    raw_high           DECIMAL(18,4),
    raw_low            DECIMAL(18,4),
    raw_open           DECIMAL(18,4),
    raw_close          DECIMAL(18,4),
    currency           VARCHAR(8),
    price_unit         VARCHAR(16),
    fetch_status       VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    fetch_error        TEXT,
    fetched_at         TIMESTAMPTZ,
    manually_filled_by UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         UUID,
    updated_by         UUID,
    CONSTRAINT chk_edp_fetch_status CHECK (fetch_status IN ('SUCCESS','FAILED','MANUAL'))
);

CREATE UNIQUE INDEX uq_element_daily ON element_daily_price(element_name, COALESCE(source_id::TEXT,''), price_date);
CREATE INDEX idx_element_daily_name  ON element_daily_price(element_name, price_date DESC);

COMMENT ON TABLE element_daily_price IS 'v5.0 §5.3.3 元素每日价格（v1 仅写 fetch_status=MANUAL 行）';

-- ============== basic_data_change_log — 变更日志 §5.4 ==============
-- v1 不写入数据，但 schema 完整（v5.1 §3.5/§4.7）
CREATE TABLE IF NOT EXISTS basic_data_change_log (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    table_name        VARCHAR(64)  NOT NULL,
    record_id         UUID         NOT NULL,
    business_key      JSONB,
    change_type       VARCHAR(16)  NOT NULL,
    field_changes     JSONB,
    version_before    INT,
    version_after     INT,
    import_record_id  UUID,
    changed_by        UUID         NOT NULL,
    changed_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    remarks           TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        UUID,
    updated_by        UUID,
    CONSTRAINT chk_bdcl_change_type CHECK (change_type IN ('CREATE','UPDATE','NEW_VERSION','SOFT_DELETE'))
);

CREATE INDEX idx_bdcl_table_rec  ON basic_data_change_log(table_name, record_id, changed_at DESC);
CREATE INDEX idx_bdcl_import     ON basic_data_change_log(import_record_id);
CREATE INDEX idx_bdcl_user       ON basic_data_change_log(changed_by, changed_at DESC);

COMMENT ON TABLE basic_data_change_log IS 'v5.0 §5.4 基础资料变更日志（v1 schema 完整，不写入数据）';

-- ============== exchange_rate — 汇率 ==============
CREATE TABLE IF NOT EXISTS exchange_rate (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID         NOT NULL REFERENCES customer(id),
    from_currency   VARCHAR(8)   NOT NULL,
    to_currency     VARCHAR(8)   NOT NULL,
    rate            DECIMAL(18,6) NOT NULL,
    effective_date  DATE         NOT NULL,
    is_current      BOOLEAN      NOT NULL DEFAULT true,
    source          VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID
);

CREATE INDEX idx_exchange_rate_cust ON exchange_rate(customer_id, from_currency, to_currency, effective_date DESC);
CREATE UNIQUE INDEX uq_exchange_rate_curr ON exchange_rate(customer_id, from_currency, to_currency)
    WHERE is_current = true;

COMMENT ON TABLE exchange_rate IS 'v5.1 路线 X 客户级汇率表';

-- ============== customer_tax — 客户税率 ==============
CREATE TABLE IF NOT EXISTS customer_tax (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID         NOT NULL REFERENCES customer(id),
    tax_type        VARCHAR(32)  NOT NULL,
    tax_rate        DECIMAL(10,4) NOT NULL,
    effective_date  DATE         NOT NULL,
    expiry_date     DATE,
    is_current      BOOLEAN      NOT NULL DEFAULT true,
    description     TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID
);

CREATE INDEX idx_customer_tax_cust ON customer_tax(customer_id, tax_type, effective_date DESC);
CREATE UNIQUE INDEX uq_customer_tax_curr ON customer_tax(customer_id, tax_type)
    WHERE is_current = true;

COMMENT ON TABLE customer_tax IS 'v5.1 路线 X 客户税率表';
