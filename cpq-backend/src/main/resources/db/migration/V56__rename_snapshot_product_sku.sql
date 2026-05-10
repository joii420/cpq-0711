-- V56: align quotation_line_item_snapshot.product_sku with the codebase rename
-- (Product.sku → partNo, V23 missed this table)
ALTER TABLE quotation_line_item_snapshot
    RENAME COLUMN product_sku TO product_part_no;

COMMENT ON COLUMN quotation_line_item_snapshot.product_part_no IS
    'Aligned with Product.partNo (was product_sku, V23 missed this table; renamed in V56)';
