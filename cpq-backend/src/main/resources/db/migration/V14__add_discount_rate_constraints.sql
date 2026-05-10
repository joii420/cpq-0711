-- Add CHECK constraints for discount rate fields (0-100 range)
ALTER TABLE pricing_strategy ADD CONSTRAINT chk_ps_base_discount CHECK (base_discount >= 0 AND base_discount <= 100);
ALTER TABLE pricing_rule ADD CONSTRAINT chk_pr_discount_rate CHECK (discount_rate >= 0 AND discount_rate <= 100);
