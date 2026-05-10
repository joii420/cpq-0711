-- V29: ProductDataPool — 按批次存储的导入解耦层

CREATE TABLE IF NOT EXISTS product_data_pool (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_batch_id   UUID NOT NULL,
    hf_part_no        VARCHAR(200) NOT NULL,
    data_tree         JSONB NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pdp_batch ON product_data_pool(import_batch_id);
CREATE INDEX IF NOT EXISTS idx_pdp_hf ON product_data_pool(hf_part_no);
