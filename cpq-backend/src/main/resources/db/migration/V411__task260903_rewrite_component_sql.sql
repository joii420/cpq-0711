-- =====================================================================
-- task-260903 · 阶段 B · B-3：135 段 component_sql_view.sql_template 表名替换
--
-- 🚨 本文件与 V410 拆开，是因为它**必须与 QuotePendingRewriter 的白名单同批上线**。
--    单独并入其中任何一个都会造成静默故障，顺序无关 —— 两者是同一次协议变更的两半。
--
-- 【问题】QuotePendingRewriter.TABLE_TOKEN 要求表名紧跟 FROM/JOIN 之后才算命中白名单。
--   改成 v_compat_* 后不再命中，而后果完全静默：QuoteViewValidationService.checkOne
--   对 anchorInjected=false 返回「不适用，非失败」（那是 rewriter 刻意的安全降级设计），
--   启动不报错、日志不告警。实测口径（共享库 cpq_db_0724，2026-09-03）：
--     · 150 段视图中 128 段命中白名单 → 表替换 + __v6_id 锚点注入
--     · 其中 48 段**只**靠 material_bom_item / element_bom_item 命中 ⇒ 改名后锚点全丢
--
-- 【裁决】主线 2026-09-03：把 v_compat_material_bom_item / v_compat_element_bom_item
--   并入 QuotePendingRewriter.WHITELIST_TABLES（同一分支的另一个提交）。
--   v_compat_material_master 不并 —— material_master 历来就不在白名单里
--   （它没有 is_current 列，rewriter 的替换子查询会 SQL 报错），保持原状。
--
-- 【实测验证】48 段族去重后 22 个不同模板，逐个跑 rewrite + 实跑改写后 SQL（非 LIMIT 0）：
--     · anchorInjected 保持 true         22/22
--     · __v6_id 基表解析到兼容视图名      22/22（pgjdbc getBaseTableName 对视图返回视图名，
--                                          UNION 视图亦然，已 JDBC 实测）
--     · 实跑结果集与改造前逐行相同        22/22（其中 13 个为非空结果，最多 31 行）
--   锚点 id 列：兼容视图两侧同为 uuid（V6 侧原生，新表侧 md5(...)::uuid 合成），
--   11156 行 / 11156 个唯一 id / 0 个 NULL。
--
-- 🚩 【仍未闭合，已报主线待裁决】B5 回填的**写回**路径没打通：
--   QuoteBackfillCollector:172 有 `QuoteTableAxis.of(primaryTable) == null → continue` 守卫，
--   所以不会 NPE，但 v_compat_* 未登记 ⇒ 这 48 段从「能回填」降级成「不回填」。
--   （若强行登记，executeFlip 会 `UPDATE v_compat_material_bom_item` —— UNION 视图不可更新；
--    且新表侧那条合成 id 在任何物理表里都不存在。两条修法都跨出本任务授权范围。）
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
