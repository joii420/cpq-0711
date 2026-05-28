-- V249: Phase 2 重构 — SQL 视图归宿从 costing_template 迁移到 template
--
-- 背景：V150 合并后 template.excel_view_config 才是实际渲染源（LinkedExcelView 读它）；
-- costing_template 是 legacy 配置入口，不驱动渲染。
-- Phase 1 把 SQL 视图建在了 costing_template_sql_view，现在平移到 template_sql_view。
-- costing_template_sql_view 表是空的（Phase 1 刚建，无生产数据），可以安全 drop。

-- 1. 删除 Phase 1 建的 costing_template_sql_view（数据空，无损失）
DROP TABLE IF EXISTS costing_template_sql_view CASCADE;

-- 2. 新建 template_sql_view（与 component_sql_view 同构，FK 到 template）
CREATE TABLE template_sql_view (
    id                  UUID         PRIMARY KEY,
    template_id         UUID         NOT NULL REFERENCES template(id) ON DELETE CASCADE,
    sql_view_name       VARCHAR(80)  NOT NULL,
    sql_template        TEXT         NOT NULL,
    declared_columns    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    required_variables  TEXT[]       NOT NULL DEFAULT '{}'::text[],
    scope               VARCHAR(20)  NOT NULL DEFAULT 'LOCAL' CHECK (scope IN ('LOCAL')),
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    description         TEXT,
    created_by          UUID,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tsv_template_view_name UNIQUE (template_id, sql_view_name)
);

CREATE INDEX idx_tsv_template ON template_sql_view(template_id);

COMMENT ON TABLE template_sql_view IS
    '模板拥有的 SQL 视图（与 component_sql_view 同构 + 隔离）。'
    '引用语法 $view.col，作用域为本模板内部。'
    'owner FK 指向 template（产品卡片模板），而非 costing_template（Excel 核价模板）。';

COMMENT ON COLUMN template_sql_view.template_id IS
    '所属产品卡片模板 ID（template.id）。';

COMMENT ON COLUMN template_sql_view.sql_view_name IS
    'BNF 引用名（如 summary_full），同模板内唯一。小写字母/数字/下划线。';

COMMENT ON COLUMN template_sql_view.sql_template IS
    '含命名占位符的 SQL 模板（如 :customerId / :partVersion）。';

COMMENT ON COLUMN template_sql_view.declared_columns IS
    '保存时 dry-run 自动提取的列签名。结构：[{name, dataType, nullable}, ...]';

COMMENT ON COLUMN template_sql_view.required_variables IS
    '从 sql_template 中解析出的 :xxx 占位符列表（不含 :hfPartNos）。';

COMMENT ON COLUMN template_sql_view.scope IS
    '命名空间范围。当前只允许 LOCAL（不支持跨模板 GLOBAL 引用）。';
