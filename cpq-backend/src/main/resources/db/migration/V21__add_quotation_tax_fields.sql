-- Add tax_rate and tax_amount to quotation table
-- tax_rate: percentage (e.g., 13.00 means 13%)
-- tax_amount: calculated tax amount
ALTER TABLE quotation ADD COLUMN tax_rate DECIMAL(5,2) NOT NULL DEFAULT 0;
ALTER TABLE quotation ADD COLUMN tax_amount DECIMAL(18,4) NOT NULL DEFAULT 0;
