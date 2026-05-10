CREATE TABLE component_directory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id UUID REFERENCES component_directory(id),
    name VARCHAR(200) NOT NULL,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE component (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    directory_id UUID REFERENCES component_directory(id),
    name VARCHAR(200) NOT NULL,
    code VARCHAR(100) NOT NULL UNIQUE,
    column_count INTEGER NOT NULL DEFAULT 0,
    fields JSONB NOT NULL DEFAULT '[]',
    formulas JSONB NOT NULL DEFAULT '[]',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_component_status CHECK (status IN ('ACTIVE','DISABLED'))
);

CREATE INDEX idx_component_directory ON component(directory_id);
CREATE INDEX idx_component_code ON component(code);
