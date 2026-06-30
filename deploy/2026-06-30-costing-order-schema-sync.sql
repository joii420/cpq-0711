-- =============================================================================
-- 核价管理 表结构同步脚本（内网/生产手工同步用，非 Flyway 管理时）
-- =============================================================================
-- 来源    : master 0b9a17c（核价单表与状态机重构 第一期）
--           = 迁移 V304__costing_order_and_status_checks.sql + V305__costing_order_full_entity.sql 的最终态
-- 设计    : docs/superpowers/specs/2026-06-29-核价单表与报价单核价单状态机重构-design.md (v3)
-- 生成日期: 2026-06-30
--
-- 覆盖对象（核价管理本次唯一动到的 DB 对象）:
--   1. costing_order                    —— 新表（核价单主表）
--   2. costing_order_number_seq         —— 新序列（核价单号 HJ-yyyyMMdd-NNNN 取号）
--   3. quotation.chk_q_status           —— 改 CHECK（加 CANCELLED + COSTING_REJECTED）
--   4. quotation_approval.chk_qa_action —— 改 CHECK（加 COSTING_APPROVED + COSTING_REJECTED）
--   （quotation / quotation_approval 两表本身不加列，仅放宽 CHECK）
--
-- 前置    : quotation、quotation_approval 两表须已存在。
-- 特性    : 幂等、可重复执行。对「全新库」与「已有 V304 旧版 costing_order 的库」均安全：
--           - 全新库：CREATE TABLE 一次建全量结构；
--           - 已有 V304：CREATE TABLE no-op，下方 ADD COLUMN IF NOT EXISTS 补 V305 新列 +
--             解除旧唯一约束 + 一次性回填存量行（按 frozen_dto IS NULL 守卫只动旧行）。
-- 如果内网走 Flyway：不要跑本文件，直接把 V304/V305 两个迁移放进 db/migration 让 Flyway 跑。
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 1) 核价单号序列
-- -----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS costing_order_number_seq START 1;

-- -----------------------------------------------------------------------------
-- 2) 核价单主表（全新库一次建全量；已存在则 no-op，靠下方 ADD COLUMN 升级）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS costing_order (
    id                   uuid          NOT NULL,
    quotation_id         uuid          NOT NULL,
    submitted_by         uuid,
    entered_costing_at   timestamptz   NOT NULL DEFAULT now(),
    created_at           timestamptz   NOT NULL DEFAULT now(),
    costing_order_number varchar(64),                 -- 回填后再置 NOT NULL（见步骤 4）
    status               varchar(32)   NOT NULL DEFAULT 'PENDING',
    reject_reason        text,
    frozen_dto           jsonb,
    total_amount         numeric(18,4),
    reviewed_by          uuid,
    reviewed_at          timestamptz,
    updated_at           timestamptz   NOT NULL DEFAULT now()
);

-- 2b) 已有 V304 旧表（只含 id/quotation_id/submitted_by/entered_costing_at/created_at）时补 V305 新列
ALTER TABLE costing_order ADD COLUMN IF NOT EXISTS costing_order_number varchar(64);
ALTER TABLE costing_order ADD COLUMN IF NOT EXISTS status               varchar(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE costing_order ADD COLUMN IF NOT EXISTS reject_reason        text;
ALTER TABLE costing_order ADD COLUMN IF NOT EXISTS frozen_dto           jsonb;
ALTER TABLE costing_order ADD COLUMN IF NOT EXISTS total_amount         numeric(18,4);
ALTER TABLE costing_order ADD COLUMN IF NOT EXISTS reviewed_by          uuid;
ALTER TABLE costing_order ADD COLUMN IF NOT EXISTS reviewed_at          timestamptz;
ALTER TABLE costing_order ADD COLUMN IF NOT EXISTS updated_at           timestamptz NOT NULL DEFAULT now();

-- 2c) 解除 V304 旧唯一约束（一报价单一核价单）→ 改为按提交累积多条
ALTER TABLE costing_order DROP CONSTRAINT IF EXISTS uq_costing_order_quotation;

-- 2d) 主键 / 外键（缺则补，幂等）
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'costing_order_pkey') THEN
    ALTER TABLE costing_order ADD CONSTRAINT costing_order_pkey PRIMARY KEY (id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'costing_order_quotation_id_fkey') THEN
    ALTER TABLE costing_order ADD CONSTRAINT costing_order_quotation_id_fkey
      FOREIGN KEY (quotation_id) REFERENCES quotation(id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_co_status') THEN
    ALTER TABLE costing_order ADD CONSTRAINT chk_co_status
      CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN'));
  END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 3) 存量数据回填（仅「V304 升级」场景需要；全新库无行=no-op）
--    - 核价单号：只回填为空的行（幂等）。
--    - 状态    ：仅回填旧 V304 行（frozen_dto IS NULL 守卫），按报价单当前态映射；
--                app 新建的行有 frozen_dto，不被改 → 可安全重跑。
--    SENT/ACCEPTED/REJECTED/EXPIRED 均隐含"已通过核价"→ APPROVED；DRAFT/CANCELLED → WITHDRAWN。
-- -----------------------------------------------------------------------------
UPDATE costing_order
   SET costing_order_number = 'HJ-' || to_char(created_at,'YYYYMMDD') || '-'
                              || lpad(nextval('costing_order_number_seq')::text, 4, '0')
 WHERE costing_order_number IS NULL;

UPDATE costing_order co
   SET status = CASE q.status
                  WHEN 'SUBMITTED'        THEN 'PENDING'
                  WHEN 'APPROVED'         THEN 'APPROVED'
                  WHEN 'SENT'             THEN 'APPROVED'
                  WHEN 'ACCEPTED'         THEN 'APPROVED'
                  WHEN 'REJECTED'         THEN 'APPROVED'
                  WHEN 'EXPIRED'          THEN 'APPROVED'
                  WHEN 'COSTING_REJECTED' THEN 'REJECTED'
                  ELSE 'WITHDRAWN'
                END
  FROM quotation q
 WHERE q.id = co.quotation_id
   AND co.frozen_dto IS NULL;          -- 守卫：只动旧 V304 行，不改 app 新建行

-- -----------------------------------------------------------------------------
-- 4) 核价单号置 NOT NULL（回填完成后）
-- -----------------------------------------------------------------------------
ALTER TABLE costing_order ALTER COLUMN costing_order_number SET NOT NULL;

-- -----------------------------------------------------------------------------
-- 5) 唯一/索引（全部 IF NOT EXISTS，幂等）
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uq_co_number             ON costing_order (costing_order_number);
CREATE        INDEX IF NOT EXISTS idx_costing_order_quotation ON costing_order (quotation_id);
CREATE        INDEX IF NOT EXISTS idx_costing_order_status    ON costing_order (status);
-- 部分唯一索引：同一报价单至多一条「进行中」(PENDING/APPROVED) 核价单 —— DB 层防并发双开
CREATE UNIQUE INDEX IF NOT EXISTS uq_co_active ON costing_order (quotation_id)
    WHERE status IN ('PENDING','APPROVED');

-- -----------------------------------------------------------------------------
-- 6) quotation 状态 CHECK 放宽（加 CANCELLED + COSTING_REJECTED）
-- -----------------------------------------------------------------------------
ALTER TABLE quotation DROP CONSTRAINT IF EXISTS chk_q_status;
ALTER TABLE quotation ADD  CONSTRAINT chk_q_status CHECK (
    status IN ('DRAFT','SUBMITTED','APPROVED','SENT','ACCEPTED','REJECTED','EXPIRED','CANCELLED','COSTING_REJECTED')
);

-- -----------------------------------------------------------------------------
-- 7) quotation_approval 动作 CHECK 放宽（加 COSTING_APPROVED + COSTING_REJECTED）
-- -----------------------------------------------------------------------------
ALTER TABLE quotation_approval DROP CONSTRAINT IF EXISTS chk_qa_action;
ALTER TABLE quotation_approval ADD  CONSTRAINT chk_qa_action CHECK (
    action IN ('APPROVED','REJECTED','WITHDRAWN','COSTING_APPROVED','COSTING_REJECTED')
);

COMMIT;

-- =============================================================================
-- 验证（同步后可选执行，期望 8 列 + 4 索引/约束 + 序列存在）
-- =============================================================================
-- \d costing_order
-- SELECT 1 FROM pg_sequences WHERE sequencename = 'costing_order_number_seq';
-- SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
--   WHERE conname IN ('chk_q_status','chk_qa_action','chk_co_status','uq_co_number');
-- SELECT indexname FROM pg_indexes WHERE tablename = 'costing_order';
-- =============================================================================
