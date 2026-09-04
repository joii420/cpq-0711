-- =====================================================================
-- task-260903 · 阶段 B · B-3：135 段 component_sql_view.sql_template 表名替换
--
-- 🚩🚩 本文件与 V410 刻意拆开，因为它**不是**零风险改动，落地前需要主线裁决一件事。
--
-- 【实测发现，2026-09-03】改表名会静默掐断 pending 报价单改写链路：
--   QuotePendingRewriter.WHITELIST_TABLES = {unit_price, material_bom, material_bom_item,
--   element_bom, element_bom_item, capacity, plating_scheme, annual_discount}，
--   其 TABLE_TOKEN 正则要求表名**紧跟在 FROM/JOIN 之后**。改成 v_compat_* 后不再命中。
--   实测口径（共享库 cpq_db_0724）：
--     · 150 段视图中 128 段现在会命中白名单 → 被做表替换 + 注入 __v6_id 锚点
--     · 其中 48 段**只**靠 material_bom_item / element_bom_item 命中 ⇒ 改名后锚点全丢
--   丢锚点的后果是**静默的**：QuoteViewValidationService.checkOne 对 anchorInjected=false
--   直接返回「不适用，非失败」，启动不报错、日志不告警，只是这 48 段从此
--   ① 看不见 pending 影子行 ② 不参与 B5 回填（QuoteBackfillColumnMapper 按基表名过滤）。
--
-- 【已实证的一半好消息】pgjdbc 的 getBaseTableName 对视图返回**视图名本身**，
--   UNION 视图、子查询包裹都成立（jshell/JDBC 实测，见回报）。所以只要把 3 个
--   v_compat_* 名字登记进 WHITELIST_TABLES，锚点注入与启动期硬校验就能恢复。
--
-- 【仍未解决、需要裁决的一半】QuoteBackfillService 回填时执行的是
--   `UPDATE <基表名> SET is_current = ...`。基表名若解析成 v_compat_material_bom_item，
--   UNION 视图不可更新 → 运行时报错；若在写边界把名字归一化回 material_bom_item，
--   则新表侧那条 md5 合成 id 在 V6 表里不存在 → UPDATE 命中 0 行、静默无效。
--   两条修法（视图加 INSTEAD OF 触发器分流 / 写边界归一化 + 新表侧另走一套回填）
--   都跨出本任务 backtask 的授权范围，属闸门 A0 级岔路。
--
-- ⇒ 未拿到裁决前，请**不要**把本文件并进 master。
-- =====================================================================

-- ── 5. B-3 · 135 段 component_sql_view.sql_template 表名替换 ──────────
-- 长优先无关紧要：\m/\M 是词边界，下划线算词内字符 ⇒ material_bom 不会命中
-- material_bom_item，替换结果也不会被二次替换（幂等）。
UPDATE component_sql_view
   SET sql_template = regexp_replace(
                        regexp_replace(
                          regexp_replace(sql_template,
                            '\melement_bom_item\M',  'v_compat_element_bom_item',  'g'),
                            '\mmaterial_bom_item\M', 'v_compat_material_bom_item', 'g'),
                            '\mmaterial_master\M',   'v_compat_material_master',   'g'),
       updated_at = now()
 WHERE sql_template ~ '\m(material_master|material_bom_item|element_bom_item)\M';

-- ── 6. 收工断言：替换后不许再有裸 V6 表名 ────────────────────────────
DO $$
DECLARE leftover int;
BEGIN
    SELECT count(*) INTO leftover
      FROM component_sql_view
     WHERE sql_template ~ '\m(material_master|material_bom_item|element_bom_item)\M';
    IF leftover <> 0 THEN
        RAISE EXCEPTION 'B-3 表名替换未收敛：仍有 % 段 sql_template 含裸 V6 表名', leftover;
    END IF;
END $$;
