-- update-0724 元素价格手工维护 · B1
-- 元素价格变更历史表：新建 / 修改 / 删除三种动作各写一条快照，供审计与差异展示。
-- price_id 不建 FK：DELETE 后原行已不存在，建 FK 会阻止删除或误伤历史。
-- 键三元组 (element_name, source_id, price_date) 冗余存储，保证删除后仍可追溯。

CREATE TABLE element_daily_price_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    price_id        UUID,                              -- 指向 element_daily_price.id，不建 FK
    element_name    VARCHAR(64)  NOT NULL,
    source_id       UUID,
    price_date      DATE         NOT NULL,
    action          VARCHAR(16)  NOT NULL,
    snapshot        JSONB        NOT NULL,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    changed_by      UUID,
    changed_by_name VARCHAR(100),
    CONSTRAINT chk_edpl_action CHECK (action IN ('CREATE','UPDATE','DELETE'))
);

CREATE INDEX idx_edpl_target ON element_daily_price_log
    (element_name, COALESCE(source_id::text, ''), price_date, changed_at DESC);
CREATE INDEX idx_edpl_time   ON element_daily_price_log (changed_at DESC);

COMMENT ON TABLE element_daily_price_log IS
  '元素价格变更历史（update-0724）。price_id 不建 FK：DELETE 后原行已不存在。
   键三元组冗余存储，保证删除后仍可追溯。changes 不入库，查询时比对相邻 snapshot 算出。';
