-- ============================================================
-- V218: V6 基础数据 - 主数据/版本/工序 (5 张)
-- 来源: docs/table/数据库表结构设计文档.md (V6, 2026-05-26)
-- 策略: 与 V44 mat_* 表并存运行；导入逻辑重写后再决定老表归档
--
-- 类型映射约定 (全 V218~V220 统一):
--   MySQL DATETIME      -> PostgreSQL TIMESTAMPTZ
--   MySQL TINYINT(1)    -> PostgreSQL BOOLEAN  (语义为 is_xxx / 标志位的字段)
--   MySQL INT           -> PostgreSQL INTEGER
-- 主键约定: 全部增加代理键 id UUID DEFAULT gen_random_uuid()
--          业务唯一性用 UNIQUE INDEX (允许 NULL 字段用 COALESCE 兜底)
-- 审计列约定: 全部追加 created_at/updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
--             + created_by/updated_by UUID
--
-- 命名调整:
--   设计文档 #10 exchange_rate 因与 V44 已有 exchange_rate 表 (customer_id 维度)
--   重名，落库为 exchange_rate_v6；后续导入服务绑定到 v6 表。
-- ============================================================

-- ============== 1. 料号表 material_master =====================
CREATE TABLE IF NOT EXISTS material_master (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    material_no     VARCHAR(20)     NOT NULL,
    material_name   VARCHAR(100),
    specification   VARCHAR(100),
    dimension       VARCHAR(100),
    old_material_no VARCHAR(50),
    material_type   VARCHAR(50),
    usage_property  VARCHAR(50),
    unit_weight     DECIMAL(18,6),
    standard_unit   VARCHAR(20),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID
);

CREATE UNIQUE INDEX uq_material_master_no ON material_master(material_no);
CREATE INDEX idx_material_master_type     ON material_master(material_type);

COMMENT ON TABLE material_master IS 'V6 §1 料号主表（业务键 material_no UNIQUE）';

-- ============== 2. 料号关系表 material_customer_map ==========
CREATE TABLE IF NOT EXISTS material_customer_map (
    id                       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    material_no              VARCHAR(20)     NOT NULL,
    customer_no              VARCHAR(20)     NOT NULL,
    customer_name            VARCHAR(100),
    customer_material_name   VARCHAR(100),
    customer_product_no      VARCHAR(50)     NOT NULL,
    customer_drawing_no      VARCHAR(50),
    seq_no                   INTEGER,
    payment_method           VARCHAR(50),
    base_currency            VARCHAR(10),
    quote_currency           VARCHAR(10),
    exchange_rate            DECIMAL(18,8),
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by               UUID,
    updated_by               UUID
);

CREATE UNIQUE INDEX uq_material_customer_map
    ON material_customer_map(material_no, customer_no, customer_product_no);
CREATE INDEX idx_material_customer_map_customer ON material_customer_map(customer_no);
CREATE INDEX idx_material_customer_map_prod     ON material_customer_map(customer_product_no);

COMMENT ON TABLE material_customer_map IS 'V6 §2 料号-客户映射（业务键 material_no+customer_no+customer_product_no）';

-- ============== 13. 料号生效版本管理表 material_version_mgmt ==
CREATE TABLE IF NOT EXISTS material_version_mgmt (
    id                       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    material_no              VARCHAR(20)     NOT NULL,
    customer_no              VARCHAR(20),
    material_name            VARCHAR(100),
    specification            VARCHAR(100),
    dimension                VARCHAR(100),
    seq_no                   INTEGER         NOT NULL,
    pricing_version_no       VARCHAR(20)     NOT NULL,
    pricing_version_name     VARCHAR(50),
    element_price_version    VARCHAR(20),
    material_price_version   VARCHAR(20),
    exchange_rate_version    VARCHAR(20),
    is_effective             BOOLEAN         NOT NULL DEFAULT TRUE,
    effective_date           DATE,
    expire_date              DATE,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by               UUID,
    updated_by               UUID
);

CREATE UNIQUE INDEX uq_material_version_mgmt
    ON material_version_mgmt(material_no, COALESCE(customer_no, ''), seq_no, pricing_version_no);
CREATE INDEX idx_material_version_mgmt_lookup
    ON material_version_mgmt(material_no, customer_no, is_effective);

COMMENT ON TABLE material_version_mgmt IS 'V6 §13 料号生效版本管理（核价版本绑定元素/材料/汇率版本）';

-- ============== 10. 汇率表 exchange_rate_v6 ===================
-- 注：因 V44 exchange_rate 表 (customer_id 维度) 仍被引用，新表加 _v6 后缀避让
CREATE TABLE IF NOT EXISTS exchange_rate_v6 (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    version_no      VARCHAR(20)     NOT NULL,
    base_currency   VARCHAR(10)     NOT NULL,
    target_currency VARCHAR(10)     NOT NULL,
    rate            DECIMAL(18,8)   NOT NULL,
    ref_rate        DECIMAL(18,8),
    ref_fetch_rule  VARCHAR(200),
    ref_source_url  VARCHAR(500),
    effective_date  DATE,
    expire_date     DATE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID
);

CREATE UNIQUE INDEX uq_exchange_rate_v6
    ON exchange_rate_v6(version_no, base_currency, target_currency);
CREATE INDEX idx_exchange_rate_v6_lookup
    ON exchange_rate_v6(base_currency, target_currency, effective_date DESC);

COMMENT ON TABLE exchange_rate_v6 IS 'V6 §10 汇率表（设计文档 exchange_rate；避让 V44 exchange_rate 加 _v6 后缀）';

-- ============== 8. 工序表 process_master ======================
CREATE TABLE IF NOT EXISTS process_master (
    id                   UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    process_no           VARCHAR(20)     NOT NULL,
    process_name         VARCHAR(50)     NOT NULL,
    process_category     VARCHAR(30),
    is_outsource         BOOLEAN,
    standard_currency    VARCHAR(10),
    standard_unit        VARCHAR(20),
    default_defect_rate  DECIMAL(10,4),
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by           UUID,
    updated_by           UUID
);

CREATE UNIQUE INDEX uq_process_master_no ON process_master(process_no);
CREATE INDEX idx_process_master_category ON process_master(process_category);

COMMENT ON TABLE process_master IS 'V6 §8 工序主数据（business key process_no UNIQUE）';
