-- V77: 核价单实例 (Phase C)
--
-- 一份核价单 = 一个料号的一次核价快照 + 引用的全局基础数据 3 个版本号 + 用户对默认基础数据的差量。
--
-- 核心 3 表：
--   1. costing_summary             — 核价单主表（料号 × 版本 × 引用基础数据版本）
--   2. costing_summary_override    — 用户在核价单内对基础数据的差量（不写回基础数据，本核价单专属）
--   3. costing_summary_result      — 计算结果快照（材料成本/加工费/设计成本/包装/总成本 等）
--
-- 不再额外建 costing_version_main —— 主索引信息直接落在 costing_summary（每个料号每次核价就是一行）

CREATE TABLE costing_summary (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    summary_no               VARCHAR(50)  NOT NULL UNIQUE,    -- 业务编号 CS-yyyymm-NNNN
    hf_part_no               VARCHAR(100) NOT NULL,
    -- 引用的 3 个全局基础数据版本（FK 到 costing_price_version.id）
    element_version_id       UUID         NOT NULL REFERENCES costing_price_version(id),
    material_version_id      UUID         NOT NULL REFERENCES costing_price_version(id),
    exchange_version_id      UUID         NOT NULL REFERENCES costing_price_version(id),
    -- 状态机：DRAFT → COMPUTED → PUBLISHED → ARCHIVED
    status                   VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    quote_currency           VARCHAR(10)  NOT NULL DEFAULT 'USD',  -- 核价单输出货币
    notes                    TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by               UUID,
    computed_at              TIMESTAMPTZ,
    published_at             TIMESTAMPTZ,
    published_by             UUID,
    CONSTRAINT chk_summary_status CHECK (status IN ('DRAFT','COMPUTED','PUBLISHED','ARCHIVED'))
);
CREATE INDEX idx_summary_part   ON costing_summary (hf_part_no);
CREATE INDEX idx_summary_status ON costing_summary (status);

-- ─── 用户差量（本核价单内修改基础数据但不写回）──────────────
CREATE TABLE costing_summary_override (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    summary_id               UUID         NOT NULL REFERENCES costing_summary(id) ON DELETE CASCADE,
    -- 差量针对哪类基础数据：ELEMENT / MATERIAL / EXCHANGE
    target_kind              VARCHAR(20)  NOT NULL,
    -- 业务键（用于在加载时定位原值）：
    --   ELEMENT  → element_code
    --   MATERIAL → material_no
    --   EXCHANGE → from_currency || '/' || to_currency
    target_key               VARCHAR(200) NOT NULL,
    -- 字段名（如 costing_price / discount_rate / costing_rate）
    field_name               VARCHAR(80)  NOT NULL,
    override_value           NUMERIC(18,6) NOT NULL,
    notes                    TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_override_kind CHECK (target_kind IN ('ELEMENT','MATERIAL','EXCHANGE')),
    CONSTRAINT uq_override UNIQUE (summary_id, target_kind, target_key, field_name)
);
CREATE INDEX idx_override_summary ON costing_summary_override (summary_id);

-- ─── 计算结果快照 ────────────────────────────────────────────
CREATE TABLE costing_summary_result (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    summary_id               UUID         NOT NULL REFERENCES costing_summary(id) ON DELETE CASCADE,
    -- 结果字段名（汇总 sheet 的列）：material_cost / semi_product_material_cost / process_fee /
    --                              packaging_fee / design_cost / plating_cost / qa_cost / unit_total_cost / total
    metric_code              VARCHAR(80)  NOT NULL,
    metric_label             VARCHAR(200),
    value                    NUMERIC(18,6),
    currency                 VARCHAR(10)  NOT NULL DEFAULT 'USD',
    formula_used             TEXT,                              -- 留痕计算时使用的公式（用户可读）
    sort_order               INT          NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_result UNIQUE (summary_id, metric_code)
);
CREATE INDEX idx_result_summary ON costing_summary_result (summary_id);

-- ─── 报价单关联 ──────────────────────────────────────────────
-- quotation_line_item 加 costing_summary_id 列，引用核价单作为该行的成本基线
ALTER TABLE quotation_line_item
    ADD COLUMN IF NOT EXISTS costing_summary_id UUID REFERENCES costing_summary(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_line_item_costing_summary
    ON quotation_line_item (costing_summary_id);

COMMENT ON TABLE costing_summary           IS '核价单主表（每料号每次核价一行，引用 3 个全局基础数据版本）';
COMMENT ON TABLE costing_summary_override  IS '核价单内对基础数据的用户差量（不写回基础数据）';
COMMENT ON TABLE costing_summary_result    IS '核价计算结果快照（汇总 sheet 各列的值）';
