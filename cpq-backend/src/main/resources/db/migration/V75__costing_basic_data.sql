-- V75: 核价系统全局基础数据 (Phase A)
-- 三种价格的版本化主数据：元素价格 / 材料价格 / 汇率
--
-- 设计要点：
--   - 1 个统一的版本主表（costing_price_version）+ 3 个详表，避免每个详表重复版本元信息（status/published_at/notes/created_by）
--   - 版本号按 (version_kind, version_number) 独立（Q2=A），元素 v2000 / 材料 v2000 / 汇率 v2000 互不影响
--   - is_default 走 partial unique，确保每个 kind 下 PUBLISHED 状态的默认版本最多一份
--   - 详表 FK 走 ON DELETE CASCADE：版本被删时一并清明细（DRAFT 才允许删，PUBLISHED 走归档）

CREATE TABLE costing_price_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_kind    VARCHAR(20)  NOT NULL,
    version_number  VARCHAR(50)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    notes           TEXT,
    is_default      BOOLEAN      NOT NULL DEFAULT false,
    published_at    TIMESTAMPTZ,
    published_by    UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      UUID,
    CONSTRAINT chk_costing_version_kind   CHECK (version_kind IN ('ELEMENT', 'MATERIAL', 'EXCHANGE')),
    CONSTRAINT chk_costing_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT uq_costing_version_kind_no UNIQUE (version_kind, version_number)
);

-- 每个 kind 下默认版本最多一份（仅在 PUBLISHED 时参与）
CREATE UNIQUE INDEX uq_costing_version_default
  ON costing_price_version(version_kind)
  WHERE is_default = TRUE AND status = 'PUBLISHED';

CREATE INDEX idx_costing_version_kind_status
  ON costing_price_version(version_kind, status);

-- ─── 元素价格 ──────────────────────────────────────────────────────
CREATE TABLE costing_element_price (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id        UUID NOT NULL REFERENCES costing_price_version(id) ON DELETE CASCADE,
    element_code      VARCHAR(50)  NOT NULL,        -- Ag/Cu/Ni/Au
    costing_price     NUMERIC(18,4) NOT NULL,       -- 核价单价
    market_ref_price  NUMERIC(18,4),                -- 市场参考价
    source_url        VARCHAR(500),                 -- 参考价来源网址
    source_name       VARCHAR(200),                 -- 网站名称
    source_rule       TEXT,                         -- 参考价取得规则
    currency          VARCHAR(10) NOT NULL DEFAULT 'CNY',
    unit              VARCHAR(20) NOT NULL DEFAULT 'KG',
    discount_rate     NUMERIC(5,2),                 -- 折扣率%
    sort_order        INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_costing_element_per_version UNIQUE (version_id, element_code)
);
CREATE INDEX idx_costing_element_version ON costing_element_price(version_id);

-- ─── 材料价格（黄铜等）──────────────────────────────────────────────
CREATE TABLE costing_material_price (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id        UUID NOT NULL REFERENCES costing_price_version(id) ON DELETE CASCADE,
    material_no       VARCHAR(100) NOT NULL,        -- 材料料号
    brand_name        VARCHAR(100),                 -- 品名(H62/H70 等)
    spec              VARCHAR(200),                 -- 规格
    dimension         VARCHAR(200),                 -- 尺寸
    costing_price     NUMERIC(18,4) NOT NULL,
    market_ref_price  NUMERIC(18,4),
    source_url        VARCHAR(500),
    source_name       VARCHAR(200),
    source_rule       TEXT,
    currency          VARCHAR(10) NOT NULL DEFAULT 'CNY',
    unit              VARCHAR(20) NOT NULL DEFAULT 'KG',
    discount_rate     NUMERIC(5,2),
    sort_order        INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_costing_material_per_version UNIQUE (version_id, material_no)
);
CREATE INDEX idx_costing_material_version ON costing_material_price(version_id);

-- ─── 汇率换算 ─────────────────────────────────────────────────────
CREATE TABLE costing_exchange_rate (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id        UUID NOT NULL REFERENCES costing_price_version(id) ON DELETE CASCADE,
    from_currency     VARCHAR(10) NOT NULL,         -- 货币代码 (CNY)
    to_currency       VARCHAR(10) NOT NULL,         -- 核价货币 (USD/EUR/JPY)
    costing_rate      NUMERIC(18,6) NOT NULL,       -- 核价汇率
    market_rate       NUMERIC(18,6),                -- 参考汇率
    rate_rule         TEXT,                         -- 抓取规则描述
    source_url        VARCHAR(500),                 -- 抓取网址
    sort_order        INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_costing_rate_per_version UNIQUE (version_id, from_currency, to_currency)
);
CREATE INDEX idx_costing_exchange_version ON costing_exchange_rate(version_id);

COMMENT ON TABLE  costing_price_version    IS '核价基础数据版本主表（元素/材料/汇率三种共用）';
COMMENT ON TABLE  costing_element_price    IS '元素价格明细（按版本组织）';
COMMENT ON TABLE  costing_material_price   IS '材料价格明细（按版本组织）';
COMMENT ON TABLE  costing_exchange_rate    IS '汇率明细（按版本组织）';
