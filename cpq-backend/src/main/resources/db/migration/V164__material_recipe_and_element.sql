-- V164__material_recipe_and_element.sql

CREATE TABLE IF NOT EXISTS material_recipe (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64)  NOT NULL UNIQUE,
    symbol          VARCHAR(32)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    spec_label      VARCHAR(64),
    recipe_type     VARCHAR(16)  NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    CONSTRAINT chk_material_recipe_type CHECK (recipe_type IN ('locked','editable','partial')),
    CONSTRAINT chk_material_recipe_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX idx_material_recipe_status ON material_recipe(status, sort_order);

CREATE TABLE IF NOT EXISTS material_recipe_element (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id       UUID         NOT NULL REFERENCES material_recipe(id) ON DELETE CASCADE,
    element_code    VARCHAR(32)  NOT NULL,
    element_name    VARCHAR(64)  NOT NULL,
    default_pct     DECIMAL(8,4) NOT NULL,
    min_pct         DECIMAL(8,4),
    max_pct         DECIMAL(8,4),
    is_locked       BOOLEAN      NOT NULL DEFAULT false,
    sort_order      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_recipe_element UNIQUE (recipe_id, element_code),
    CONSTRAINT chk_recipe_element_range CHECK (
        (is_locked = true AND min_pct IS NULL AND max_pct IS NULL)
        OR (is_locked = false AND min_pct IS NOT NULL AND max_pct IS NOT NULL AND min_pct <= max_pct)
    )
);
CREATE INDEX idx_recipe_element_recipe ON material_recipe_element(recipe_id, sort_order);

COMMENT ON TABLE material_recipe IS '材质配方字典(选配抽屉 P2 材质库)';
COMMENT ON TABLE material_recipe_element IS '材质元素含量(每材质 2-3 元素)';
