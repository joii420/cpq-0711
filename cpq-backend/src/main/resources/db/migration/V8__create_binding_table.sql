CREATE TABLE product_template_binding (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES product(id),
    process_ids JSONB NOT NULL DEFAULT '[]',
    process_ids_hash VARCHAR(64) NOT NULL,
    template_id UUID NOT NULL REFERENCES template(id),
    is_default BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_binding UNIQUE(product_id, process_ids_hash, template_id)
);

CREATE UNIQUE INDEX idx_binding_default ON product_template_binding(product_id, process_ids_hash) WHERE is_default = true;
CREATE INDEX idx_binding_product ON product_template_binding(product_id);
CREATE INDEX idx_binding_hash ON product_template_binding(product_id, process_ids_hash);
