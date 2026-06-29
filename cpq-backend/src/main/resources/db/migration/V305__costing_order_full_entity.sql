-- V305: 核价单升级为完整实体 —— 解除一报价单一核价单、加单号/状态/原因/冻结DTO/总额/审核人

-- 1) 解除唯一约束（改为按提交累积多条）
ALTER TABLE costing_order DROP CONSTRAINT uq_costing_order_quotation;

-- 2) 新增列
ALTER TABLE costing_order
  ADD COLUMN costing_order_number VARCHAR(64),
  ADD COLUMN status               VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
  ADD COLUMN reject_reason        TEXT,
  ADD COLUMN frozen_dto           JSONB,
  ADD COLUMN total_amount         NUMERIC(18,4),
  ADD COLUMN reviewed_by          UUID,
  ADD COLUMN reviewed_at          TIMESTAMPTZ,
  ADD COLUMN updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now();

-- 3) 状态 CHECK
ALTER TABLE costing_order
  ADD CONSTRAINT chk_co_status CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN'));

-- 4) 核价单号序列 + 回填（SENT/ACCEPTED/REJECTED/EXPIRED 均隐含已通过核价 → APPROVED）
CREATE SEQUENCE IF NOT EXISTS costing_order_number_seq START 1;
UPDATE costing_order co SET
  costing_order_number = 'HJ-' || to_char(co.created_at,'YYYYMMDD') || '-' || lpad(nextval('costing_order_number_seq')::text,4,'0'),
  status = CASE q.status
             WHEN 'SUBMITTED'        THEN 'PENDING'
             WHEN 'APPROVED'         THEN 'APPROVED'
             WHEN 'SENT'             THEN 'APPROVED'
             WHEN 'ACCEPTED'         THEN 'APPROVED'
             WHEN 'REJECTED'         THEN 'APPROVED'
             WHEN 'EXPIRED'          THEN 'APPROVED'
             WHEN 'COSTING_REJECTED' THEN 'REJECTED'
             ELSE 'WITHDRAWN' END
  FROM quotation q WHERE q.id = co.quotation_id;

ALTER TABLE costing_order ALTER COLUMN costing_order_number SET NOT NULL;
ALTER TABLE costing_order ADD CONSTRAINT uq_co_number UNIQUE (costing_order_number);

-- 5) 部分唯一索引 —— DB 层强约束"至多一条 active"
CREATE UNIQUE INDEX uq_co_active ON costing_order(quotation_id) WHERE status IN ('PENDING','APPROVED');

-- 6) 索引
CREATE INDEX idx_costing_order_status ON costing_order(status);
