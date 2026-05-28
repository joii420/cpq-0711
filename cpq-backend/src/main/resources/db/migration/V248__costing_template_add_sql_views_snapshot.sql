-- V248__costing_template_add_sql_views_snapshot.sql
-- costing_template 增加 sql_views_snapshot 字段（Phase 1 — 方案 §5.2）
-- 发布时把本模板拥有的所有 costing_template_sql_view 快照到此 JSONB，实现发布冻结。

ALTER TABLE costing_template
  ADD COLUMN sql_views_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN costing_template.sql_views_snapshot IS
  '发布时把本模板拥有的所有 costing_template_sql_view 快照到此 JSONB。
   结构: {"<sql_view_name>": {"sqlTemplate": "...", "declaredColumns": [...], "requiredVariables": [...]}}';
