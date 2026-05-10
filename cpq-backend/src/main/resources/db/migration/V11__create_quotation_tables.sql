-- Quotation number sequence
CREATE SEQUENCE quotation_number_seq START 1;

-- Main quotation table
CREATE TABLE quotation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_number VARCHAR(50) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES customer(id),
    name VARCHAR(500) NOT NULL,
    contact_id UUID,
    contact_name VARCHAR(200),
    contact_phone VARCHAR(50),
    contact_email VARCHAR(200),
    project_name VARCHAR(500),
    opportunity_id VARCHAR(200),
    sales_rep_id UUID NOT NULL REFERENCES "user"(id),
    quote_type VARCHAR(20) DEFAULT 'STANDARD',
    priority VARCHAR(10) DEFAULT 'MEDIUM',
    stage VARCHAR(30) DEFAULT 'INITIAL_CONTACT',
    expected_close_date DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    total_amount DECIMAL(18,4) DEFAULT 0,
    expiry_date DATE,
    payment_terms TEXT,
    delivery_cycle INTEGER,
    original_amount DECIMAL(18,4) DEFAULT 0,
    system_discount_rate DECIMAL(5,2) DEFAULT 100,
    final_discount_rate DECIMAL(5,2) DEFAULT 100,
    discount_adjustment_reason TEXT,
    is_manually_adjusted BOOLEAN DEFAULT false,
    source_quotation_id UUID,
    assigned_approver_id UUID,
    snapshot_customer_name VARCHAR(200),
    snapshot_customer_level VARCHAR(20),
    snapshot_customer_region VARCHAR(100),
    snapshot_customer_industry VARCHAR(100),
    snapshot_customer_address TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_q_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','SENT','ACCEPTED','REJECTED','EXPIRED')),
    CONSTRAINT chk_q_type CHECK (quote_type IN ('STANDARD','DISCOUNT','BULK')),
    CONSTRAINT chk_q_priority CHECK (priority IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT chk_q_stage CHECK (stage IN ('INITIAL_CONTACT','REQUIREMENT_CONFIRMATION','QUOTING','NEGOTIATION'))
);

CREATE INDEX idx_q_customer ON quotation(customer_id);
CREATE INDEX idx_q_sales_rep ON quotation(sales_rep_id);
CREATE INDEX idx_q_status ON quotation(status);
CREATE INDEX idx_q_approver ON quotation(assigned_approver_id);

-- Line items
CREATE TABLE quotation_line_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id UUID NOT NULL REFERENCES quotation(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES product(id),
    template_id UUID NOT NULL REFERENCES template(id),
    product_attribute_values JSONB DEFAULT '{}',
    subtotal DECIMAL(18,4) DEFAULT 0,
    system_discount_rate DECIMAL(5,2) DEFAULT 100,
    final_discount_rate DECIMAL(5,2) DEFAULT 100,
    discount_adjustment_reason TEXT,
    is_manually_adjusted BOOLEAN DEFAULT false,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_qli_quotation ON quotation_line_item(quotation_id);

-- Line processes
CREATE TABLE quotation_line_process (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    line_item_id UUID NOT NULL REFERENCES quotation_line_item(id) ON DELETE CASCADE,
    process_id UUID NOT NULL REFERENCES process(id)
);

-- Line component data
CREATE TABLE quotation_line_component_data (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    line_item_id UUID NOT NULL REFERENCES quotation_line_item(id) ON DELETE CASCADE,
    component_id UUID,
    tab_name VARCHAR(200),
    row_data JSONB DEFAULT '[]',
    subtotal DECIMAL(18,4) DEFAULT 0,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_qlcd_line ON quotation_line_component_data(line_item_id);

-- Line item snapshot
CREATE TABLE quotation_line_item_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    line_item_id UUID NOT NULL REFERENCES quotation_line_item(id) ON DELETE CASCADE,
    product_sku VARCHAR(100),
    product_category VARCHAR(30),
    product_specification VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Approval records
CREATE TABLE quotation_approval (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id UUID NOT NULL REFERENCES quotation(id),
    approver_id UUID NOT NULL REFERENCES "user"(id),
    action VARCHAR(20) NOT NULL,
    comment TEXT,
    acted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_qa_action CHECK (action IN ('APPROVED','REJECTED'))
);

CREATE INDEX idx_qa_quotation ON quotation_approval(quotation_id);
