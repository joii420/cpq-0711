CREATE TABLE datasource (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    type VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    description TEXT,
    sql_query TEXT,
    sql_result_column VARCHAR(100),
    api_url VARCHAR(1000),
    api_method VARCHAR(10),
    api_headers JSONB DEFAULT '[]',
    api_body_template TEXT,
    api_result_path VARCHAR(500),
    api_timeout_seconds INTEGER DEFAULT 5,
    created_by UUID REFERENCES "user"(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_ds_type CHECK (type IN ('SQL','API')),
    CONSTRAINT chk_ds_status CHECK (status IN ('ACTIVE','DISABLED'))
);

CREATE TABLE datasource_param (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    datasource_id UUID NOT NULL REFERENCES datasource(id) ON DELETE CASCADE,
    param_order INTEGER NOT NULL,
    param_code VARCHAR(100) NOT NULL,
    param_name VARCHAR(200) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    system_param_code VARCHAR(50),
    is_required BOOLEAN NOT NULL DEFAULT true,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_param_source CHECK (source_type IN ('USER_FIELD','SYSTEM_PARAM')),
    CONSTRAINT uq_ds_param_code UNIQUE(datasource_id, param_code)
);

CREATE INDEX idx_dsp_datasource ON datasource_param(datasource_id);
