-- Add sort_order and is_required to product_process table
-- sort_order: controls the display order of processes for a product
-- is_required: marks if this process is mandatory for this specific product (per-product override)
ALTER TABLE product_process ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE product_process ADD COLUMN is_required BOOLEAN NOT NULL DEFAULT false;
