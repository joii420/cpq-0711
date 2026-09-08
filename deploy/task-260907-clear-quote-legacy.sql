-- =====================================================================================
--  task-260907 · B-12 —— 存量清空脚本（报价侧旧模板 + 存量报价单）
--
--  🚨🚨🚨  本脚本【不自动执行】、【不进 Flyway】、【默认 ROLLBACK】  🚨🚨🚨
--
--  它属于 CLAUDE.md §3.2「不可逆操作红线 · 数据销毁 + 环境销毁」。
--  执行前必须由主线向用户呈报三件事并取得【当次】批准：
--     ① 操作是什么      ② 逐表影响行数（跑下面 §0 得到真实数字，不许凭记忆）
--     ③ 可恢复性        —— 目前【没有备份、没有迁移可重建、无法从远端恢复】
--                          ⇒ 必须直说「此操作不可恢复」，不要模糊带过
--  批准不跨操作、不跨会话。子代理没有批准权。
--
--  📌 交付背景：S-7（存量清空）已于 2026-09-07 由 D-24 移出本期
--     （实证「核价通过→回填升版」是活能力：13 单走过 COSTING_APPROVED，
--       unit_price 26 行 / material_bom_item 17 行已转正；本期就清会让该能力
--       整体消失且【没人会收到报错，它只是安静地不发生】）。
--     ⇒ 本脚本先行落盘，等第二段（BL-0215 核价回填）交付后再与之一并执行。
--
--  🚫 全篇不含 DROP / TRUNCATE / 无 WHERE 的 DELETE：
--     每条 DELETE 都带显式 WHERE，命中面由 §0 的 SELECT 事先量化。
-- =====================================================================================

\set ON_ERROR_STOP on
\timing on

-- =====================================================================================
-- §0  影响面盘点（只读）—— 🚦 呈报用的数字来自这里，先跑这一段，把输出贴给用户
-- =====================================================================================
\echo '===== §0 影响面盘点（只读）====='

SELECT 'quotation（全部报价单）'                AS object, count(*) AS rows FROM quotation
UNION ALL SELECT 'quotation_line_item',                  count(*) FROM quotation_line_item
UNION ALL SELECT 'quotation_line_component_data',        count(*) FROM quotation_line_component_data
UNION ALL SELECT 'quotation_line_composite_process',     count(*) FROM quotation_line_composite_process
UNION ALL SELECT 'quotation_line_item_snapshot',         count(*) FROM quotation_line_item_snapshot
UNION ALL SELECT 'quotation_line_process',               count(*) FROM quotation_line_process
UNION ALL SELECT 'quotation_component_sql_snapshot',     count(*) FROM quotation_component_sql_snapshot
UNION ALL SELECT 'quotation_view_structure',             count(*) FROM quotation_view_structure
UNION ALL SELECT 'quotation_approval',                   count(*) FROM quotation_approval
UNION ALL SELECT 'quotation_price_revision',             count(*) FROM quotation_price_revision
UNION ALL SELECT 'quotation_withdraw_request',           count(*) FROM quotation_withdraw_request
UNION ALL SELECT 'costing_order',                        count(*) FROM costing_order
UNION ALL SELECT 'costing_order_version_override',       count(*) FROM costing_order_version_override
UNION ALL SELECT 'template（QUOTATION 报价模板）',        count(*) FROM template WHERE template_kind = 'QUOTATION'
UNION ALL SELECT 'template_component（报价模板下）',      count(*) FROM template_component tc
                                                            WHERE EXISTS (SELECT 1 FROM template t
                                                                          WHERE t.id = tc.template_id
                                                                            AND t.template_kind = 'QUOTATION')
ORDER BY 1;

-- 🚨 会被连带影响、但【本脚本不删】的对象（只报数，让用户自己判断能不能接受）
\echo '----- 会被连带影响但本脚本不删的对象 -----'
SELECT 'import_record.quotation_id 将被置空的行' AS object, count(*) AS rows
  FROM import_record WHERE quotation_id IS NOT NULL
UNION ALL
SELECT 'import_record.template_id / customer_template_id 指向报价模板的行', count(*)
  FROM import_record ir
 WHERE EXISTS (SELECT 1 FROM template t
                WHERE t.template_kind = 'QUOTATION'
                  AND t.id IN (ir.template_id, ir.customer_template_id))
UNION ALL
SELECT 'material_price_update_job_item.quotation_id 非空行', count(*)
  FROM material_price_update_job_item WHERE quotation_id IS NOT NULL
UNION ALL
SELECT 'product_template_binding 指向报价模板的行', count(*)
  FROM product_template_binding ptb
  JOIN template t ON t.id = ptb.template_id AND t.template_kind = 'QUOTATION'
UNION ALL
SELECT 'costing_template.linked_template_id 指向报价模板的行', count(*)
  FROM costing_template ct
  JOIN template t ON t.id = ct.linked_template_id AND t.template_kind = 'QUOTATION';

-- =====================================================================================
-- §1  清空动作（默认包在 BEGIN … ROLLBACK 里 —— 先看命中行数，确认无误再改 COMMIT）
--
--  ⚠️ 删除顺序 = FK 依赖的叶子 → 根。顺序错会报外键冲突（那是好事，不是坏事）。
--  ⚠️ 本脚本【不删】主数据（customer / material_master / ds_quote_* / V6 八张表）——
--     那些是「基础资料」，与「报价单 + 报价模板」是两件事，混在一起删是范围失控。
-- =====================================================================================
BEGIN;

\echo '===== §1.1 解除外部引用（置空，不删行）====='
-- import_record 是审计记录，本身要保留；只把指向即将消失的报价单/模板的外键置空。
UPDATE import_record SET quotation_id = NULL
 WHERE quotation_id IS NOT NULL;
UPDATE import_record SET template_id = NULL
 WHERE template_id IN (SELECT id FROM template WHERE template_kind = 'QUOTATION');
UPDATE import_record SET customer_template_id = NULL
 WHERE customer_template_id IN (SELECT id FROM template WHERE template_kind = 'QUOTATION');
UPDATE material_price_update_job_item SET quotation_id = NULL
 WHERE quotation_id IS NOT NULL;
UPDATE costing_template SET linked_template_id = NULL
 WHERE linked_template_id IN (SELECT id FROM template WHERE template_kind = 'QUOTATION');

\echo '===== §1.2 报价单从属数据（叶子先删）====='
DELETE FROM quotation_line_component_data
 WHERE line_item_id IN (SELECT id FROM quotation_line_item);
DELETE FROM quotation_line_composite_process
 WHERE line_item_id IN (SELECT id FROM quotation_line_item);
DELETE FROM quotation_line_item_snapshot
 WHERE line_item_id IN (SELECT id FROM quotation_line_item);
DELETE FROM quotation_line_process
 WHERE line_item_id IN (SELECT id FROM quotation_line_item);

-- quotation_line_item 自引用（parent_line_item_id）：先断父子关系再整表删，避免自引用冲突
UPDATE quotation_line_item SET parent_line_item_id = NULL
 WHERE parent_line_item_id IS NOT NULL;
DELETE FROM quotation_line_item
 WHERE quotation_id IN (SELECT id FROM quotation);

\echo '===== §1.3 核价单及其从属 ====='
DELETE FROM costing_order_version_override
 WHERE costing_order_id IN (SELECT id FROM costing_order);
DELETE FROM costing_order
 WHERE quotation_id IN (SELECT id FROM quotation);

\echo '===== §1.4 报价单本体的其余从属表 ====='
DELETE FROM quotation_component_sql_snapshot WHERE quotation_id IN (SELECT id FROM quotation);
DELETE FROM quotation_view_structure         WHERE quotation_id IN (SELECT id FROM quotation);
DELETE FROM quotation_approval               WHERE quotation_id IN (SELECT id FROM quotation);
DELETE FROM quotation_price_revision         WHERE quotation_id IN (SELECT id FROM quotation);
DELETE FROM quotation_withdraw_request       WHERE quotation_id IN (SELECT id FROM quotation);

\echo '===== §1.5 报价单主表 ====='
-- WHERE 恒真但显式写出：不允许出现「无 WHERE 的 DELETE」这种形态（§3.2 红线的字面判据）。
DELETE FROM quotation WHERE id IS NOT NULL;

\echo '===== §1.6 报价模板及其从属 ====='
DELETE FROM template_component_snapshot
 WHERE template_id IN (SELECT id FROM template WHERE template_kind = 'QUOTATION');
DELETE FROM template_global_variable_binding
 WHERE template_id IN (SELECT id FROM template WHERE template_kind = 'QUOTATION');
DELETE FROM template_sql_view
 WHERE template_id IN (SELECT id FROM template WHERE template_kind = 'QUOTATION');
DELETE FROM product_template_binding
 WHERE template_id IN (SELECT id FROM template WHERE template_kind = 'QUOTATION');
DELETE FROM template_component
 WHERE template_id IN (SELECT id FROM template WHERE template_kind = 'QUOTATION');
DELETE FROM template
 WHERE template_kind = 'QUOTATION';

\echo '===== §2 清空后复核（仍在事务内）====='
SELECT 'quotation' AS t, count(*) FROM quotation
UNION ALL SELECT 'quotation_line_item', count(*) FROM quotation_line_item
UNION ALL SELECT 'costing_order', count(*) FROM costing_order
UNION ALL SELECT 'template(QUOTATION)', count(*) FROM template WHERE template_kind='QUOTATION'
ORDER BY 1;

-- =====================================================================================
-- 🚦 默认 ROLLBACK。确认上面的命中行数与 §0 的盘点一致、且【用户已当次批准】后，
--    才把下面这行改成 COMMIT; 并重跑。
-- 🚫 不要为了「省一步」提前改成 COMMIT —— 这个默认值就是最后一道闸。
-- =====================================================================================
ROLLBACK;
-- COMMIT;

-- =====================================================================================
-- §3 执行后必做（写在脚本里，免得漏）
--   1. 强制重启后端（进程级缓存里还留着已被删掉的 template / component 快照，
--      CLAUDE.md §3「schema DDL / 大批量删除后必须重启」同源理由）
--   2. 用一个业务端点验证服务健康：期望 401，不是 500
--      curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components
--   3. 重新导入 S-5 的报价模板迁移（task-260907 B-11），否则新链路建出的单没有模板可绑
-- =====================================================================================
