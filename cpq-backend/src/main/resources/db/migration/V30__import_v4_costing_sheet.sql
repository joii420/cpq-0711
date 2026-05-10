-- V30: 导入 v4 改造 — ImportRecord 加双模板快照 + Quotation 扩展 + CostingSheet

-- ImportRecord: 加 costing_template_id + customer_template_id + 快照 + import_batch_id
ALTER TABLE import_record ADD COLUMN IF NOT EXISTS costing_template_id UUID REFERENCES costing_template(id);
ALTER TABLE import_record ADD COLUMN IF NOT EXISTS customer_template_id UUID REFERENCES template(id);
ALTER TABLE import_record ADD COLUMN IF NOT EXISTS costing_template_snapshot JSONB;
ALTER TABLE import_record ADD COLUMN IF NOT EXISTS customer_template_snapshot JSONB;
ALTER TABLE import_record ADD COLUMN IF NOT EXISTS import_batch_id UUID;

CREATE INDEX IF NOT EXISTS idx_import_record_batch ON import_record(import_batch_id);

-- Quotation: 加 customer_template_id + import_batch_id
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS customer_template_id UUID REFERENCES template(id);
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS import_batch_id UUID;

CREATE INDEX IF NOT EXISTS idx_quotation_import_batch ON quotation(import_batch_id);

-- CostingSheet: 核价表
CREATE TABLE IF NOT EXISTS costing_sheet (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id          UUID NOT NULL UNIQUE REFERENCES quotation(id) ON DELETE CASCADE,
    costing_template_id   UUID REFERENCES costing_template(id),
    import_batch_id       UUID,
    rows                  JSONB NOT NULL DEFAULT '[]'::jsonb,
    total_cost            NUMERIC(20, 4),
    status                VARCHAR(20) NOT NULL DEFAULT 'LIVE',  -- LIVE / SNAPSHOT
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_costing_sheet_quotation ON costing_sheet(quotation_id);
CREATE INDEX IF NOT EXISTS idx_costing_sheet_template ON costing_sheet(costing_template_id);

-- v4 基础数据驱动: product_id 允许 null（导入产品不强制关联 Product 表）
ALTER TABLE quotation_line_item ALTER COLUMN product_id DROP NOT NULL;
ALTER TABLE quotation_line_item ALTER COLUMN template_id DROP NOT NULL;

-- 新增产品名快照列（用于无 Product 关联的导入）
ALTER TABLE quotation_line_item ADD COLUMN IF NOT EXISTS product_name_snapshot VARCHAR(500);
ALTER TABLE quotation_line_item ADD COLUMN IF NOT EXISTS product_part_no_snapshot VARCHAR(200);
