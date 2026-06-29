-- 核价单(精简): 报价单提交审批时自动建; 锚定核价流程元数据 + 第二期扩展点
CREATE TABLE costing_order (
    id                 UUID PRIMARY KEY,
    quotation_id       UUID NOT NULL REFERENCES quotation(id),
    submitted_by       UUID,
    entered_costing_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_costing_order_quotation UNIQUE (quotation_id)
);
CREATE INDEX idx_costing_order_quotation ON costing_order(quotation_id);

-- 状态 CHECK: 加 COSTING_REJECTED(本期新增) + 顺补既存遗漏的 CANCELLED
ALTER TABLE quotation DROP CONSTRAINT chk_q_status;
ALTER TABLE quotation ADD CONSTRAINT chk_q_status CHECK (
    status IN ('DRAFT','SUBMITTED','APPROVED','SENT','ACCEPTED','REJECTED','EXPIRED','CANCELLED','COSTING_REJECTED')
);

-- 审批动作 CHECK: 加核价两枚举(现为 APPROVED/REJECTED/WITHDRAWN)
ALTER TABLE quotation_approval DROP CONSTRAINT chk_qa_action;
ALTER TABLE quotation_approval ADD CONSTRAINT chk_qa_action CHECK (
    action IN ('APPROVED','REJECTED','WITHDRAWN','COSTING_APPROVED','COSTING_REJECTED')
);
