-- ===========================================
-- V10: Approval rule table
-- Used by Approval Routing Engine (M4a)
-- ===========================================

CREATE TABLE approval_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_type VARCHAR(20) NOT NULL,
    approver_id UUID REFERENCES "user"(id),
    match_field VARCHAR(20),
    match_value_id UUID,
    priority INTEGER NOT NULL DEFAULT 100,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_ar_type CHECK (rule_type IN ('FIXED','DYNAMIC')),
    CONSTRAINT chk_ar_field CHECK (match_field IS NULL OR match_field IN ('REGION','DEPARTMENT'))
);
