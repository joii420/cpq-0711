-- =============================================================================
-- S-7 · 读法乙′（乙 排除 ds 原生）—— 🚦 用户 2026-09-07 明确批准
--
-- 与原 S-7-全库清空-待批准.sql 的差异（🚨 那份不要再用）：
--   ① B 段加 WHERE，排除第一段交付物 S-5 的 ds 原生模板与组件
--   ② 修正列名 bug：costing_template.linked_template → linked_template_id
--
-- 为什么排除：V421/V422 种子迁移已 success=true ⇒ Flyway 不会重灌 ⇒ 删了永久消失。
-- 且 component_sql_view.builder_config（14/14 非空）是手工在 UI 配的，不在任何迁移里。
-- 它们是新链路本身，不是存量 —— 删它们不是完成 S-7，是把 S-7 想留下的那一半也删了。
--
-- 备份：/home/joii/cpq-backup/cpq_db_0724_before_S7_20260907-182011.dump
--       17.8 MB · TOC 2589 条 · 270 个数据段 · 9 张关键表逐张确认有数据段
-- =============================================================================

-- 保留集（两段共用）
--   keep_tpl = template.template_series_id = '9d4bbf4a-222f-4642-a5a3-d7e6b9035798'
--              （df379593 v1.0/13页签 + 875a5c9f v1.1/14页签，两版都留，断版本链会影响版本切换）
--   keep_cmp = component.name LIKE 'T260907-%'
--
-- 外键交叉检查（执行前实测，双向均为 0）：
--   保留模板引用要删的旧组件 = 0   ⇒ 不会被 template_component.component_id 的 NO ACTION 挡住
--   要删的旧模板引用保留组件 = 0   ⇒ 保留组件不会被连带

-- ── A 段｜报价与核价业务单据（读法甲，无排除，整段清）────────────────────────
BEGIN;
DELETE FROM quotation_line_component_data;
DELETE FROM quotation_line_composite_process;
DELETE FROM quotation_line_process;
DELETE FROM quotation_line_item_snapshot;
DELETE FROM quotation_line_item;
DELETE FROM quotation_component_sql_snapshot;
DELETE FROM quotation_view_structure;
DELETE FROM quotation_price_revision;
DELETE FROM quotation_approval;
DELETE FROM quotation_withdraw_request;
DELETE FROM quotation_comparison_config;
DELETE FROM costing_order_version_override;
DELETE FROM costing_order;
DELETE FROM material_price_update_job_item;
DELETE FROM import_record;
DELETE FROM quotation;
-- 🚦 COMMIT 前核对：SELECT count(*) FROM quotation;  期望 0
COMMIT;

-- ── B 段｜旧组件与旧模板（排除 ds 原生，必须在 A 段之后）──────────────────────
BEGIN;
DELETE FROM template_component_snapshot
 WHERE template_id NOT IN (SELECT id FROM template WHERE template_series_id='9d4bbf4a-222f-4642-a5a3-d7e6b9035798');
DELETE FROM template_global_variable_binding
 WHERE template_id NOT IN (SELECT id FROM template WHERE template_series_id='9d4bbf4a-222f-4642-a5a3-d7e6b9035798');
DELETE FROM template_sql_view
 WHERE template_id NOT IN (SELECT id FROM template WHERE template_series_id='9d4bbf4a-222f-4642-a5a3-d7e6b9035798');
DELETE FROM template_component
 WHERE template_id NOT IN (SELECT id FROM template WHERE template_series_id='9d4bbf4a-222f-4642-a5a3-d7e6b9035798');
DELETE FROM product_template_binding
 WHERE template_id NOT IN (SELECT id FROM template WHERE template_series_id='9d4bbf4a-222f-4642-a5a3-d7e6b9035798');
DELETE FROM import_mapping_template
 WHERE template_id NOT IN (SELECT id FROM template WHERE template_series_id='9d4bbf4a-222f-4642-a5a3-d7e6b9035798');
-- ⚠️ 原脚本此处列名写错（linked_template），实为 linked_template_id
UPDATE costing_template SET linked_template_id = NULL
 WHERE linked_template_id IS NOT NULL
   AND linked_template_id NOT IN (SELECT id FROM template WHERE template_series_id='9d4bbf4a-222f-4642-a5a3-d7e6b9035798');
DELETE FROM template
 WHERE template_series_id IS DISTINCT FROM '9d4bbf4a-222f-4642-a5a3-d7e6b9035798';
DELETE FROM component_sql_view
 WHERE component_id NOT IN (SELECT id FROM component WHERE name LIKE 'T260907-%');
DELETE FROM component
 WHERE name NOT LIKE 'T260907-%';
-- 🚦 COMMIT 前核对：component 期望剩 27，template 期望剩 2，component_sql_view 期望剩 14
COMMIT;

-- =============================================================================
-- 执行后必须做的两件事
--   1. 强制重启后端 —— ImplicitJoinRewriter.tableColumnsCache / CachedSqlCompiler /
--      DataLoader.resultCache 是进程级缓存，大批量删数据后残留空集，
--      症状是「本该返单值的地方返全表」或「页签整体空白」
--   2. 重建 E2E 夹具 —— 失败形态是「Step1 下一步禁用」这类看起来像回归的样子
-- =============================================================================
