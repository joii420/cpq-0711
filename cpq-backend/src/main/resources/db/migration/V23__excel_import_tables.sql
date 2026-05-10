-- ===========================================
-- V23: Excel Import feature tables
-- Product.sku → part_no rename
-- New tables: internal_material, customer_material_mapping,
-- customer_excel_template, import_mapping_template, import_record
-- QuotationLineItem: add customer_part_no
-- ===========================================

-- 1. Rename Product.sku → part_no
ALTER TABLE product RENAME COLUMN sku TO part_no;

-- Drop and recreate the index with new name
DROP INDEX IF EXISTS idx_product_sku;
CREATE INDEX idx_product_part_no ON product(part_no);

-- 2. InternalMaterial (我司生产料号)
CREATE TABLE internal_material (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    material_no VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    specification VARCHAR(500),
    size VARCHAR(200),
    status_code VARCHAR(10) NOT NULL DEFAULT 'Y',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_im_status CHECK (status_code IN ('Y', 'N'))
);

CREATE INDEX idx_im_material_no ON internal_material(material_no);
CREATE INDEX idx_im_status ON internal_material(status_code);

-- 3. CustomerMaterialMapping (客户料号关联)
CREATE TABLE customer_material_mapping (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customer(id),
    customer_part_no VARCHAR(200) NOT NULL,
    material_id UUID NOT NULL REFERENCES internal_material(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_part_no UNIQUE(customer_id, customer_part_no)
);

CREATE INDEX idx_cmm_customer ON customer_material_mapping(customer_id);
CREATE INDEX idx_cmm_part_no ON customer_material_mapping(customer_id, customer_part_no);

-- 4. CustomerExcelTemplate (客户Excel模板)
CREATE TABLE customer_excel_template (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(300) NOT NULL,
    customer_id UUID NOT NULL REFERENCES customer(id),
    description TEXT,
    header_row_index INTEGER NOT NULL DEFAULT 1,
    data_start_row_index INTEGER NOT NULL DEFAULT 2,
    sheet_index INTEGER NOT NULL DEFAULT 0,
    part_no_column VARCHAR(200) NOT NULL,
    excel_columns JSONB NOT NULL DEFAULT '[]',
    sample_file_name VARCHAR(500),
    created_by UUID REFERENCES "user"(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_cet_customer ON customer_excel_template(customer_id);

-- 5. ImportMappingTemplate (导入映射配置)
CREATE TABLE import_mapping_template (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(300) NOT NULL,
    excel_template_id UUID NOT NULL REFERENCES customer_excel_template(id),
    template_id UUID NOT NULL REFERENCES template(id),
    column_mappings JSONB NOT NULL DEFAULT '[]',
    created_by UUID REFERENCES "user"(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_excel_template_mapping UNIQUE(excel_template_id, template_id)
);

CREATE INDEX idx_imt_excel_template ON import_mapping_template(excel_template_id);

-- 6. ImportRecord (导入记录)
CREATE TABLE import_record (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id UUID REFERENCES quotation(id),
    customer_id UUID NOT NULL REFERENCES customer(id),
    excel_template_id UUID NOT NULL REFERENCES customer_excel_template(id),
    mapping_template_id UUID NOT NULL REFERENCES import_mapping_template(id),
    mapping_snapshot JSONB NOT NULL,
    original_file_name VARCHAR(500) NOT NULL,
    original_file_path VARCHAR(1000) NOT NULL,
    total_rows INTEGER,
    success_rows INTEGER,
    matched_rows INTEGER,
    unmatched_rows INTEGER,
    import_status VARCHAR(20) NOT NULL,
    error_detail JSONB,
    imported_by UUID NOT NULL REFERENCES "user"(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_ir_status CHECK (import_status IN ('SUCCESS', 'PARTIAL', 'FAILED'))
);

CREATE INDEX idx_ir_customer ON import_record(customer_id);
CREATE INDEX idx_ir_quotation ON import_record(quotation_id);
CREATE INDEX idx_ir_imported_by ON import_record(imported_by);

-- 7. QuotationLineItem: add customer_part_no
ALTER TABLE quotation_line_item ADD COLUMN customer_part_no VARCHAR(200);
