-- V24: Excel Import v2 — Excel view config on Template + view snapshot on LineItem

-- 1. Template: add excel_view_config
ALTER TABLE template ADD COLUMN IF NOT EXISTS excel_view_config JSONB;

-- 2. QuotationLineItem: add excel_view_snapshot
ALTER TABLE quotation_line_item ADD COLUMN IF NOT EXISTS excel_view_snapshot JSONB;
