ALTER TABLE quotation_line_item
    ADD COLUMN IF NOT EXISTS profit_snapshot numeric(26,12) NULL;
