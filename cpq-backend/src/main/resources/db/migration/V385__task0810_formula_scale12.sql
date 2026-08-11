-- task-0810: formula working values retain 12 decimal places.
-- Widen only; historical values are neither recalculated nor rewritten.
ALTER TABLE quotation
    ALTER COLUMN total_amount TYPE numeric(26,12),
    ALTER COLUMN original_amount TYPE numeric(26,12),
    ALTER COLUMN tax_amount TYPE numeric(26,12);

ALTER TABLE quotation_line_item
    ALTER COLUMN subtotal TYPE numeric(26,12),
    ALTER COLUMN discount_base_amount TYPE numeric(26,12),
    ALTER COLUMN line_unit_price TYPE numeric(26,12),
    ALTER COLUMN line_final_price TYPE numeric(26,12),
    ALTER COLUMN line_discount_amount TYPE numeric(26,12),
    ALTER COLUMN line_total_amount TYPE numeric(26,12);

ALTER TABLE quotation_line_component_data
    ALTER COLUMN subtotal TYPE numeric(26,12);

ALTER TABLE costing_order
    ALTER COLUMN total_amount TYPE numeric(26,12),
    ALTER COLUMN costing_total_amount TYPE numeric(26,12);

ALTER TABLE material_price_review
    ALTER COLUMN warn_diff TYPE numeric(26,12);

ALTER TABLE material_price_review_column
    ALTER COLUMN quote_current TYPE numeric(26,12),
    ALTER COLUMN quote_adjusted TYPE numeric(26,12),
    ALTER COLUMN costing_current TYPE numeric(26,12),
    ALTER COLUMN costing_adjusted TYPE numeric(26,12),
    ALTER COLUMN diff_current TYPE numeric(26,12),
    ALTER COLUMN diff_adjusted TYPE numeric(26,12);

ALTER TABLE quotation_price_revision
    ALTER COLUMN quote_total_amount TYPE numeric(26,12);

ALTER TABLE material_price_update_job_item
    ALTER COLUMN diff_value TYPE numeric(26,12);
