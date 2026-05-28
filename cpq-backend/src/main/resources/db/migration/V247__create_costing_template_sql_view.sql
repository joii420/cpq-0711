-- V247__create_costing_template_sql_view.sql
-- Excel 模板独立 SQL 视图表（Phase 1 — 方案 §5.1）
-- 与 component_sql_view 同构，FK 改为 costing_template，scope 只允许 LOCAL。

CREATE TABLE costing_template_sql_view (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    costing_template_id    UUID         NOT NULL REFERENCES costing_template(id) ON DELETE CASCADE,
    sql_view_name          VARCHAR(80)  NOT NULL,
    sql_template           TEXT         NOT NULL,
    declared_columns       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    required_variables     TEXT[]       NOT NULL DEFAULT '{}'::text[],
    -- scope 字段保留但只有 LOCAL 一个有效值，留为后续扩展空间
    scope                  VARCHAR(20)  NOT NULL DEFAULT 'LOCAL' CHECK (scope IN ('LOCAL')),
    status                 VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    description            TEXT,
    created_by             UUID,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_ctsv_template_view_name UNIQUE (costing_template_id, sql_view_name)
);

CREATE INDEX idx_ctsv_template ON costing_template_sql_view(costing_template_id);

COMMENT ON TABLE costing_template_sql_view IS
  'Excel 模板拥有的 SQL 视图（与 component_sql_view 同构 + 隔离）。引用语法 $view.col，作用域为本模板内部。';

COMMENT ON COLUMN costing_template_sql_view.scope IS
  '保留字段，当前只允许 LOCAL；后续若需要"模板视图库"特性可扩 GLOBAL';

COMMENT ON COLUMN costing_template_sql_view.sql_template IS
  '含命名占位符的 SELECT SQL 模板（如 :customerId / :partVersion）。由 BnfPathLinter 检测是否引用 V44 废弃表。';

COMMENT ON COLUMN costing_template_sql_view.declared_columns IS
  '保存时 dry-run 自动提取的列签名。结构：[{name: "col", dataType: "text", nullable: false}]';
