-- Store formula-to-field bindings configured via drag-drop in template configuration
ALTER TABLE template_component ADD COLUMN formula_assignments JSONB NOT NULL DEFAULT '{}';
