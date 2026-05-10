-- P2-8: Add remarks column to quotation table
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS remarks TEXT;
