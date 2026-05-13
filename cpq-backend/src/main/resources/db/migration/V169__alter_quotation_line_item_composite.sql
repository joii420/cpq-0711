-- V169__alter_quotation_line_item_composite.sql
-- 给 quotation_line_item 加 2 列以支持组合产品(父行 + 子配件行):
--   parent_line_item_id: 子配件行 → 父行的 id; SIMPLE/COMPOSITE 父行为 NULL
--   composite_type:      'SIMPLE' 独立 / 'COMPOSITE' 组合父 / 'PART' 组合子

ALTER TABLE quotation_line_item
    ADD COLUMN IF NOT EXISTS parent_line_item_id  UUID         NULL REFERENCES quotation_line_item(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS composite_type       VARCHAR(16)  NOT NULL DEFAULT 'SIMPLE';

ALTER TABLE quotation_line_item DROP CONSTRAINT IF EXISTS chk_quotation_line_item_composite_type;
ALTER TABLE quotation_line_item
    ADD CONSTRAINT chk_quotation_line_item_composite_type
        CHECK (composite_type IN ('SIMPLE','COMPOSITE','PART'));

CREATE INDEX IF NOT EXISTS idx_quotation_line_item_parent ON quotation_line_item(parent_line_item_id);

COMMENT ON COLUMN quotation_line_item.parent_line_item_id IS '组合产品场景:子配件行→父行 id';
COMMENT ON COLUMN quotation_line_item.composite_type IS 'SIMPLE 独立 / COMPOSITE 组合父 / PART 组合子';
