-- V343__quotation_comparison_config.sql
-- task-0717 报价单比对视图：持久化「比对列配置」（按 报价单 × 桶(SALES/FINANCE) 存一份）。
-- （原定 V341，因并发 worktree 已抢占 V341/V342，改用 V343 —— 本文件从未被 Flyway 应用过，
--  不违反"共享 flyway 历史不改名改号"约束，见 CLAUDE.md。）
-- 详见 dev-docs/task-0717-比对视图/api.md §7、backtask.md T1。
--
-- 语义：
--   quotation_id + bucket 唯一 → 每张报价单每个入口桶各存一份列配置（非跨单全局模板）。
--   columns：ColumnDef[] JSONB，只存列定义（componentId/metric/threshold/sortOrder 等），不存值——
--     值在打开视图时由 /comparison-view/data 实时取（单一数据源纪律，见 backtask.md §T3）。
--   存量数据不迁移（新增功能，无历史数据）。

CREATE TABLE quotation_comparison_config (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id  UUID        NOT NULL,
    bucket        VARCHAR(16) NOT NULL,          -- SALES | FINANCE
    columns       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_qcc_quotation_bucket UNIQUE (quotation_id, bucket)
);

CREATE INDEX idx_qcc_quotation ON quotation_comparison_config (quotation_id);
