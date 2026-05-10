-- V31: QuotationWithdrawRequest — APPROVED → DRAFT 撤回流程

CREATE TABLE IF NOT EXISTS quotation_withdraw_request (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id    UUID NOT NULL REFERENCES quotation(id),
    requested_by    UUID NOT NULL,
    reason          TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / APPROVED / REJECTED
    decided_by      UUID,
    decided_at      TIMESTAMP WITH TIME ZONE,
    decision_note   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_qwr_quotation ON quotation_withdraw_request(quotation_id);
CREATE INDEX IF NOT EXISTS idx_qwr_status ON quotation_withdraw_request(status);

-- 同一报价单同时只能有一个 PENDING 撤回请求
CREATE UNIQUE INDEX IF NOT EXISTS uq_qwr_pending
    ON quotation_withdraw_request(quotation_id) WHERE status = 'PENDING';
