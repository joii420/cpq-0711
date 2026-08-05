-- task-0729 C4 · 存量 quotation_view_structure 回填元素角色字段（验收 #42①）
--
-- 【背景】价格锁定徽标依赖冻结结构里的 elementCodeField / elementPriceField /
-- elementCurrencyField 三个角色 key。它们 2026-08-02 才进 CardSnapshotService#buildCardStructure，
-- 而 quotation_view_structure 是【创建即冻、永不重建】的 —— 之前建的单结构里根本没有这三个 key。
-- 实测：全库 204 个 QUOTE_CARD 页签中 28 个指向价格承载组件，只有 3 个带字段 → 另外 25 个
-- 编辑页与详情页都不出徽标，#42① 实际只在新单上成立。
--
-- 【定性】这是 AP-39 同款问题的镜像：AP-39 是"改了 component.fields 没动引用方 jsonb 列"，
-- 本次是"给 component 加了新列，但存量冻结结构没跟上"。补的是【当时就该有、只因功能未上线
-- 而缺失】的字段，不是修改业务数据 —— 冻结语义不破。
--
-- 【安全性设计】三条硬保证，逐条对应实现手法：
--   ① 只补缺失、绝不覆盖已有  → 合并方向写成 `patch || tab`（tab 在右，同名 key 以 tab 为准）。
--      即使某页签已有角色字段，它的值也原样胜出。不依赖"key 不存在才写"的前置判断。
--   ② 除三个角色 key 外一律不动 → jsonb_set 只替换 '{tabs}' 这一个路径，structure 其余内容
--      （productAttributes 等）原样保留；tab 内部除三个 key 外同样由 `|| t.tab` 原样带过。
--   ③ 页签顺序不变 → WITH ORDINALITY + jsonb_agg(... ORDER BY t.ord)。缺了它 jsonb_agg 的
--      聚合顺序不确定，页签会乱序（渲染按数组序）。
--
-- 【与原写入语义对齐】CardSnapshotService 是"角色字段非空才 put"。这里用 jsonb_strip_nulls
-- 达到同一效果：component.element_currency_field 为 NULL 时不写 elementCurrencyField 键，
-- 而不是写一个 null 值 —— 否则前端 `tab.elementCurrencyField !== undefined` 类判断会分叉。
--
-- 【范围】仅 QUOTE_CARD。
-- ⚠️ COSTING_CARD 存在完全相同的缺口（实测 19 个页签缺 code/price 键），本迁移【有意不含】，
--    等业务方就"核价侧是否同样需要徽标"拍板后另行处理。若裁定需要，把下面两处
--    `view_kind = 'QUOTE_CARD'` 改成 `view_kind IN ('QUOTE_CARD','COSTING_CARD')` 即可，
--    其余逻辑无需改动。
-- 注：QUOTE_EXCEL / COSTING_EXCEL 的 structure 没有 tabs 键，被 view_kind 谓词天然排除。

UPDATE quotation_view_structure s
SET structure = jsonb_set(s.structure, '{tabs}', rebuilt.new_tabs)
FROM (
    SELECT s2.id,
           jsonb_agg(
               CASE
                   WHEN c.element_price_field IS NOT NULL
                       -- patch 在左、tab 在右：同名 key 以 tab 现有值为准（保证①不覆盖）
                       THEN jsonb_strip_nulls(jsonb_build_object(
                                'elementCodeField',     c.element_code_field,
                                'elementPriceField',    c.element_price_field,
                                'elementCurrencyField', c.element_currency_field
                            )) || t.tab
                   ELSE t.tab
               END
               ORDER BY t.ord        -- 保证③页签顺序
           ) AS new_tabs
    FROM quotation_view_structure s2
    CROSS JOIN LATERAL jsonb_array_elements(s2.structure -> 'tabs') WITH ORDINALITY AS t(tab, ord)
    -- c.id::text = ... 而不是 (...)::uuid = c.id：componentId 可能是空串/脏值，
    -- 反向 cast 会在整表上抛 invalid input syntax for type uuid，text 比较则安全跳过
    LEFT JOIN component c ON c.id::text = t.tab ->> 'componentId'
    WHERE s2.view_kind = 'QUOTE_CARD'
      AND jsonb_typeof(s2.structure -> 'tabs') = 'array'
    GROUP BY s2.id
) rebuilt
WHERE s.id = rebuilt.id
  -- 只重写"确实有页签需要回填"的结构行；已完整的行一行都不碰（避免无谓的行版本变动）
  AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(s.structure -> 'tabs') AS t2(tab)
      JOIN component c2 ON c2.id::text = t2.tab ->> 'componentId'
      WHERE c2.element_price_field IS NOT NULL
        AND NOT (t2.tab ? 'elementPriceField')
  );
