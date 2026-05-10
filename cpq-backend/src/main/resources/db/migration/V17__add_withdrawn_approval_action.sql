-- Add WITHDRAWN to quotation_approval action CHECK constraint for withdraw feature
ALTER TABLE quotation_approval DROP CONSTRAINT chk_qa_action;
ALTER TABLE quotation_approval ADD CONSTRAINT chk_qa_action CHECK (action IN ('APPROVED','REJECTED','WITHDRAWN'));
