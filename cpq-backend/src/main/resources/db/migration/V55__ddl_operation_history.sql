-- ============================================================
-- V55: DDL 扩列操作历史记录表（v5.1 §3.4 TECH-4 方案 B）
-- 方案 B：不写物理 migration 文件，把生成的 SQL 文本存入 migration_content
-- Ref: docs/superpowers/specs/2026-04-26-cpq-design-v5.1.md §3.4
-- ============================================================

CREATE TABLE IF NOT EXISTS ddl_operation_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    table_name      VARCHAR(64) NOT NULL,
    column_name     VARCHAR(64) NOT NULL,
    data_type       VARCHAR(64) NOT NULL,
    default_value   TEXT NOT NULL,
    importance      VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    affects_calculation BOOLEAN NOT NULL DEFAULT false,
    status          VARCHAR(16) NOT NULL,
    error_message   TEXT,
    migration_content TEXT NOT NULL,
    flyway_version_hint VARCHAR(32),
    created_by      UUID NOT NULL,
    created_by_name VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_ddl_status CHECK (status IN ('SUCCESS','FAILED')),
    CONSTRAINT chk_ddl_importance CHECK (importance IN ('CRITICAL','IMPORTANT','NORMAL'))
);

CREATE INDEX idx_ddl_history_table ON ddl_operation_history(table_name, created_at DESC);
CREATE INDEX idx_ddl_history_status ON ddl_operation_history(status, created_at DESC);

COMMENT ON TABLE ddl_operation_history IS 'v5.1 §3.4 TECH-4: 运行时 ALTER 扩列历史记录 + 生成的 migration 文本（方案 B）';
COMMENT ON COLUMN ddl_operation_history.migration_content IS '生成的 ALTER TABLE SQL 文本，供管理员复制到 git 作正式 migration';
COMMENT ON COLUMN ddl_operation_history.flyway_version_hint IS '推荐的 Flyway 版本号（如 V56），根据 flyway_schema_history MAX(version)+1 推算';
