-- V217: 组件级数据源 SQL 方案（方案 §3）
-- 新建 4 个 schema 对象：
--   a) component_sql_view 表 (§3.1)
--   b) quotation_component_sql_snapshot 表 (§3.2)
--   c) template.sql_views_snapshot JSONB 列 (§3.3)
--   d) bnf_table_meta 表 (§3.4)
-- 参考文档: docs/组件级数据源SQL方案.md
-- 实施时间: 2026-05-26

-- ============================================================
-- a) component_sql_view 表
-- ============================================================
CREATE TABLE component_sql_view (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    component_id UUID NOT NULL REFERENCES component(id) ON DELETE CASCADE,
    sql_view_name VARCHAR(80) NOT NULL,
    sql_template TEXT NOT NULL,
    declared_columns JSONB NOT NULL DEFAULT '[]',
    required_variables TEXT[] NOT NULL DEFAULT '{}',
    scope VARCHAR(20) NOT NULL DEFAULT 'COMPONENT',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    description TEXT,
    created_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (component_id, sql_view_name),
    CONSTRAINT chk_csv_scope CHECK (scope IN ('COMPONENT', 'GLOBAL')),
    CONSTRAINT chk_csv_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_csv_scope_global ON component_sql_view(scope) WHERE scope = 'GLOBAL';
CREATE INDEX idx_csv_component_id ON component_sql_view(component_id);

COMMENT ON TABLE component_sql_view IS '组件级用户自定义 SQL 视图，作为 BNF path 数据源的扩展层';
COMMENT ON COLUMN component_sql_view.sql_view_name IS 'BNF 引用名，例如 element_view；同 component 内唯一';
COMMENT ON COLUMN component_sql_view.sql_template IS '含命名占位符的 SQL 模板，如 :customerId';
COMMENT ON COLUMN component_sql_view.declared_columns IS '保存时 dry-run 自动提取的列签名 [{name,dataType,nullable}]';
COMMENT ON COLUMN component_sql_view.required_variables IS '从 sql_template 中解析出的 :xxx 占位符列表';
COMMENT ON COLUMN component_sql_view.scope IS 'COMPONENT=本组件使用; GLOBAL=可跨组件 BNF $$ 引用';

-- ============================================================
-- b) quotation_component_sql_snapshot 表（报价单提交时冻结）
-- ============================================================
CREATE TABLE quotation_component_sql_snapshot (
    quotation_id UUID NOT NULL REFERENCES quotation(id) ON DELETE CASCADE,
    sql_view_key VARCHAR(200) NOT NULL,
    sql_template TEXT NOT NULL,
    declared_columns JSONB NOT NULL DEFAULT '[]',
    required_variables TEXT[] NOT NULL DEFAULT '{}',
    frozen_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (quotation_id, sql_view_key)
);

CREATE INDEX idx_qcss_quotation_id ON quotation_component_sql_snapshot(quotation_id);

COMMENT ON TABLE quotation_component_sql_snapshot IS '报价单提交时冻结的 SQL 视图快照；key = componentId::sql_view_name';

-- ============================================================
-- c) template.sql_views_snapshot JSONB 列（模板发布时冻结）
-- ============================================================
ALTER TABLE template
    ADD COLUMN IF NOT EXISTS sql_views_snapshot JSONB DEFAULT NULL;

COMMENT ON COLUMN template.sql_views_snapshot IS '模板 PUBLISHED 时冻结的 SQL 视图闭包；结构: { "componentId::sql_view_name": { sql_template, declared_columns, required_variables } }';

-- ============================================================
-- d) bnf_table_meta 表（启动时自动同步 information_schema）
-- ============================================================
CREATE TABLE bnf_table_meta (
    table_name VARCHAR(120) PRIMARY KEY,
    is_view BOOLEAN NOT NULL,
    template_kind VARCHAR(20) DEFAULT 'ALL',
    display_name VARCHAR(200),
    picker_visible BOOLEAN DEFAULT true,
    last_synced TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE bnf_table_meta IS 'BNF path 根节点元数据表；启动时自动同步 information_schema；PathPicker 第二 Tab 数据源';
COMMENT ON COLUMN bnf_table_meta.template_kind IS 'QUOTATION/COSTING/ALL，运营按需调整';
COMMENT ON COLUMN bnf_table_meta.picker_visible IS '是否在 PathPicker 里显示';
