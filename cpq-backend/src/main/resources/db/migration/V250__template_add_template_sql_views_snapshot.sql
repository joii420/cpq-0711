-- V250: Phase 2 重构 — template 增 template_sql_views_snapshot 列；costing_template 删 sql_views_snapshot 列
--
-- 背景：V249 将 SQL 视图从 costing_template 迁移到 template，
-- 对应的 snapshot 字段也随之迁移。

-- 1. 删除 Phase 1 在 costing_template 上加的 sql_views_snapshot（数据空，无损失）
ALTER TABLE costing_template DROP COLUMN IF EXISTS sql_views_snapshot;

-- 2. template 增字段（独立于现有的 template.sql_views_snapshot）
--    注意：template.sql_views_snapshot 是组件 SQL 视图快照（component_sql_view），
--          template.template_sql_views_snapshot 是模板自有 SQL 视图快照（template_sql_view）
ALTER TABLE template
    ADD COLUMN template_sql_views_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN template.template_sql_views_snapshot IS
    '发布时把本模板拥有的所有 template_sql_view 快照到此 JSONB。'
    '结构: {"<sql_view_name>": {sqlTemplate, declaredColumns, requiredVariables}}'
    '注意: 不要与 template.sql_views_snapshot 混淆，后者是组件 SQL 视图（component_sql_view）的冻结快照。';
