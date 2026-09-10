-- ============================================================================
-- task-260909 · V6 老表退役 · 批次 1 收尾（B-5 / AC-2 · AC-12③ · AC-15）
--   退役 3 个 v_compat_* 兼容视图。
--
-- 🚨🚨 本文件含 CLAUDE.md §3.2【数据销毁】红线操作（DROP VIEW）。
--      执行依据三项齐全，缺任一项都不许重放：
--
--   ① 影响面（2026-09-09 17:38 实测，共享库 cpq_db_0724，DROP 前重采）
--        v_compat_material_master     2715 行   下游 DB 对象 0
--        v_compat_material_bom_item   2774 行   下游 DB 对象 0
--        v_compat_element_bom_item    2748 行   下游 DB 对象 0
--      「下游 0」的量法（pg_depend ⋈ pg_rewrite，非 grep 猜）+ 引用面复采：
--        costing_bom_tree_config / component_sql_view / template_sql_view
--        / 其余所有视图定义   命中 v_compat_ 均为 0
--        阳性对照：同一把量具对 component_sql_view 命中 ds_quote_ = 38 段（量具够得着）
--      ⚠️ 视图不存数据，DROP 不删任何业务行；AC-10 的 10 张表行数不受影响。
--
--   ② 可恢复路径 —— 🔴 **不是 V410/V415/V428/V429**
--      需求文档与 backtask 都写「回滚用 V410/V415/V428/V429」，**那份清单漏了一环**。
--      实测：建/改 v_compat_* 视图本体的迁移共 5 个 —— V410(20 处) V415(3) V428(10)
--            V429(6) **V435(3)**；V411/V437 只改引用方，不建视图。
--      🔴 V435__repair260908_compat_master_dedup_ds_branch.sql 改的是
--         v_compat_material_master 的 ds 分支去重 ⇒ **只按那四个回滚会还原成 V435 之前的旧定义。**
--      ✅ **回滚载体 = 归档 DDL 全文**（已验逐字等于 DROP 前的活定义）：
--           dev-docs/task-260909-V6老表退役/证据/ddl-基线-v_compat_material_master.sql    md5sum 765f745231…
--           dev-docs/task-260909-V6老表退役/证据/ddl-基线-v_compat_material_bom_item.sql  md5sum 281a67ef59…
--           dev-docs/task-260909-V6老表退役/证据/ddl-基线-v_compat_element_bom_item.sql   md5sum 1c350a7997…
--         （对应 md5(pg_get_viewdef(oid,true))：aaf2464d9df839c8cf6aabac88a071fc /
--           40f7b710b79e09f9c062b720d112a3a2 / 64116a232a98e34f4dbe7c4a5804aef8
--           —— 与 md5sum 不同是因为 viewdef 文本末尾换行，两种口径都记下来免得下次对不上）
--      回滚方式：把归档件原文包成 CREATE VIEW <名> AS <原文> 执行即可，视图无数据，回滚成本≈0。
--      🚦 已按 AC-13 真跑过一次重建→复核→再 DROP 的演练，不是纸面承诺。
--
--   ③ 用户批准：2026-09-09，用户对 B-4 数据变化对照清单答「可以接受」、对 DROP VIEW 答「批准」。
--      **批准覆盖且仅覆盖下面这三个视图**（§3.2「批准不跨对象」）——
--      🚫 不含 10 张 V6 老表本体、不含 v_composite_child_*、不含 v_ds_cost_*_all、不含任何列。
--
-- ⚠️ 顺序纪律（AC-12，不可颠倒）：① V437 已改写全部下游 → ② 已重启验 AC-3 通过
--    → ③ 本迁移 DROP → ④ 再重启复验 AC-3。先 DROP 后改写会让 composite 视图落进中间态失效。
-- ⚠️ DDL 之后必须重启 Quarkus（CLAUDE.md「视图 DROP/重建后必须重启」：进程级缓存会缓存空集并残留）。
-- ============================================================================

DROP VIEW IF EXISTS v_compat_material_bom_item;
DROP VIEW IF EXISTS v_compat_element_bom_item;
DROP VIEW IF EXISTS v_compat_material_master;

-- 收工断言：三张兼容视图必须全部不存在（AC-2）
DO $$
DECLARE leftover int;
BEGIN
    SELECT count(*) INTO leftover FROM pg_class WHERE relname LIKE 'v\_compat\_%';
    IF leftover <> 0 THEN
        RAISE EXCEPTION 'B-5 未收敛：仍有 % 个 v_compat_* 对象存在', leftover;
    END IF;
END $$;
