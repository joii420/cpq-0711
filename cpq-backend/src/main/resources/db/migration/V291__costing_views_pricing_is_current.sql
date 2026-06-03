-- V291: 核价侧 v12_* sql_template 读版本化表补 PRICING + is_current 过滤（防升版后重复行 AP-22）。
--
-- 背景：PRICING 导入现对 4 张表（material_bom_item / element_bom_item / capacity / plating_scheme）
--   做版本化并翻转 is_current。运行时 BNF 解释执行的 sql_template 若不过滤 is_current（且 system_type），
--   升版后旧 is_current=false 行会与新 current 行混出，在核价单渲染产生重复行（AP-22「X（共N项）」）。
-- 范围：仅作用于 PRICING/核价 读取点。QUOTE 侧已由 V280/V281 处理，不在本迁移范围。
-- 机制：UPDATE ... regexp_replace（\s+ 弹性匹配换行/CRLF），并以 NOT ILIKE 守卫保证幂等。
-- 部署：落 db/migration/ 后 touch java 触发 Quarkus 重启 → Flyway + 元数据重同步（清缓存）。
--
-- ── Step1 发现结论（每个命中表/别名 → 处置）──────────────────────────────
-- 读 4 张版本化表的 runtime sql_template 全量枚举结果：
--   material_bom_item / element_bom_item 的所有读取点（v12_raw_bom / zcj_view / cz_view / zpj_view /
--     ys_view / v12_raw_element_bom / composite_child_*_mirror / summary_material(ebi) 等）一律
--     system_type='QUOTE' → QUOTE 侧，**不在本范围**（V280/V281 已处理 / 防御性省略）。
--   v12_plating_cost / summary_material(PRICING 部分) 读的是 unit_price / fee_config，不是本范围的
--     4 张版本化表 → **跳过**（V281 注释已说明 PRICING unit_price 有意保留不动）。
--   capacity：唯一读取点是 zh_view（组件「组合工艺」COMP-0023，与 zcj/cz/ys/zpj 同属 QUOTE 渲染家族，
--     兄弟视图均用 system_type='QUOTE'）。zh_view 当前为 `FROM capacity WHERE is_current`，缺 system_type；
--     capacity 现含 QUOTE+PRICING 两类 current 行，存在升版后 QUOTE/PRICING 混行风险。但 zh_view 属
--     **QUOTE 家族**，非核价(PRICING)读取点 → **不在本 PRICING 任务范围**，本迁移不擅自注入 PRICING；
--     该 QUOTE 侧缺 system_type 问题应由 QUOTE 侧（V281 后续）评估补 system_type='QUOTE'。见下方说明。
--   plating_scheme：唯一读取点 v12_plating_scheme（组件「核价V5-电镀方案」= 核价/PRICING）。V281 已补
--     is_current，但缺 system_type；plating_scheme 现含 QUOTE/PRICING 语义 → 核价侧须补 PRICING。**注入**。

-- ① v12_plating_scheme：核价侧（核价V5-电镀方案）；已含 ps.is_current（V281），补 ps.system_type='PRICING'。
--    现状模板末尾为 `FROM plating_scheme ps WHERE ps.is_current = true`，在 is_current 前插入 system_type。
UPDATE component_sql_view
   SET sql_template = regexp_replace(
         sql_template,
         'WHERE\s+ps\.is_current\s*=\s*true',
         'WHERE ps.system_type = ''PRICING'' AND ps.is_current = true',
         'g'),
       updated_at = NOW()
 WHERE sql_view_name = 'v12_plating_scheme'
   AND sql_template NOT ILIKE '%ps.system_type%';

-- ② capacity（zh_view）：核价侧无 capacity 读取点（zh_view 属 QUOTE 渲染家族）→ 本 PRICING 迁移无需注入。
--    说明：zh_view 缺 system_type 是 QUOTE 侧独立隐患，留待 QUOTE 侧评估，不在本任务范围。

-- ③ material_bom_item / element_bom_item：核价侧无独立读取点（全部 system_type='QUOTE'）→ 无需注入。
