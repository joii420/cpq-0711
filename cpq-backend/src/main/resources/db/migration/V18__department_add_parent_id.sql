-- Add parent_id to department for hierarchical tree structure
ALTER TABLE department ADD COLUMN parent_id UUID REFERENCES department(id);
CREATE INDEX idx_department_parent ON department(parent_id);
