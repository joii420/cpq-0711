CREATE TABLE template (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_series_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    version VARCHAR(20),
    category VARCHAR(30),
    description TEXT,
    usage_note TEXT,
    product_attributes JSONB DEFAULT '[]',
    subtotal_formula JSONB DEFAULT '[]',
    components_snapshot JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by UUID REFERENCES "user"(id),
    published_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_template_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    CONSTRAINT chk_template_category CHECK (category IS NULL OR category IN ('STANDARD_PARTS','CUSTOM_PARTS','RAW_MATERIALS'))
);

CREATE INDEX idx_template_series ON template(template_series_id);
CREATE INDEX idx_template_status ON template(status);

CREATE TABLE template_component (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES template(id) ON DELETE CASCADE,
    component_id UUID NOT NULL REFERENCES component(id),
    tab_name VARCHAR(200),
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_tc_template ON template_component(template_id);
