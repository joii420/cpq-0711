-- V167__alter_mat_part_add_configure_cols.sql
-- 给 mat_part 加 3 列以支持"添加产品 — 选配"功能:
--   material_recipe_id: 材质配方 FK,旧料号(导入产生)留 NULL
--   product_type:       'SIMPLE' 独立 / 'COMPOSITE' 组合(父料号)
--   config_fingerprint: 配置指纹(F2),仅选配料号写入;sha256 hex 64 字符

ALTER TABLE mat_part
    ADD COLUMN IF NOT EXISTS material_recipe_id   UUID         NULL REFERENCES material_recipe(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS product_type         VARCHAR(16)  NOT NULL DEFAULT 'SIMPLE',
    ADD COLUMN IF NOT EXISTS config_fingerprint   VARCHAR(64)  NULL;

ALTER TABLE mat_part DROP CONSTRAINT IF EXISTS chk_mat_part_product_type;
ALTER TABLE mat_part
    ADD CONSTRAINT chk_mat_part_product_type
        CHECK (product_type IN ('SIMPLE','COMPOSITE'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_mat_part_fingerprint
    ON mat_part(config_fingerprint)
    WHERE config_fingerprint IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mat_part_recipe ON mat_part(material_recipe_id);
CREATE INDEX IF NOT EXISTS idx_mat_part_product_type ON mat_part(product_type);

COMMENT ON COLUMN mat_part.material_recipe_id IS '材质配方 FK(选配生成的料号会填;旧料号为 NULL)';
COMMENT ON COLUMN mat_part.product_type IS 'SIMPLE 独立 / COMPOSITE 组合(父料号)';
COMMENT ON COLUMN mat_part.config_fingerprint IS '配置指纹(F2):仅选配料号写;sha256 hex 64 字符';
