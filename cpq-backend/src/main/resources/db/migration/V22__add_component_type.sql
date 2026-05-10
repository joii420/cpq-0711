-- Add component_type to distinguish regular components from subtotal components
ALTER TABLE component ADD COLUMN component_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE component ADD CONSTRAINT chk_component_type CHECK (component_type IN ('NORMAL', 'SUBTOTAL'));
