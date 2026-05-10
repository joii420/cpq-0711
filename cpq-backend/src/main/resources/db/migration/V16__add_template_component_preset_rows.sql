-- Add preset_rows to template_component for storing fixed rows configured in template
ALTER TABLE template_component ADD COLUMN preset_rows JSONB NOT NULL DEFAULT '[]';
