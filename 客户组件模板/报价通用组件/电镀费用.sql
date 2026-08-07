-- ============================================================================
-- 组件：电镀费用（报价通用模板）    SQL 视图名：dp_view    驱动路径：$dp_view
-- requiredVariables: ["customerCode"]    scope: COMPONENT
-- 页签属性：tabType=(空) · partNoField=(空) · partNameField=(空)
--           rowKeyFields=["销售料号", "料件", "项次", "要素"] · sortField=项次
--
-- 【本文件是「可编辑副本」，不是运行时来源】改完二选一才生效：
--   ① UI：组件管理 →「电镀费用」组件 → SQL 视图 → 粘贴保存（保存即 dry-run 校验）
--   ② API：PUT /api/cpq/components/{componentId}/sql-views/{viewId}
--          body: {"sqlViewName":"dp_view","sqlTemplate":"<本文件内容>",
--                 "scope":"COMPONENT","requiredVariables":["customerCode"]}
--   改完记得把同目录 电镀费用.json 也同步重出，否则 JSON 与实际配置漂移。
--
-- 【直连 PG 验证】把命名参数换字面量后跑（DRAFT 报价单还要模拟 pending 开域，见 README §验证）：
--   :customerCode → '<客户编号>' · :priceBaseDate → CURRENT_DATE · :total_material_no → ARRAY[...]::text[]
-- ============================================================================
-- 电镀费用（费用页签，tabType 空不参与树；price_type='PLATING'）
-- 行式出行：每（销售料号 × 投入料号）两行 —— cost_type='电镀加工费' / '电镀材料费'，值在 pricing_price。
--
-- 🔑 repair-0802（2026-08-02）新口径 —— 本视图 2026-08-03 按此重写，与旧版语义相反，勿回退：
--    | 列                        | 改造前        | 改造后（现行）                          |
--    | up.code                   | 销售料号      | **投入料号（零件料号）**，两列皆空时回退=销售料号 |
--    | up.finished_material_no   | 恒 NULL       | **销售料号（成品）**，必有值              |
--    Q17PlatingCostHandler 落库口径已归队 Q06/Q07/Q13，groupKey 含 (code, finished_material_no) 两个料号维度。
--    ⇒ hf_part_no（驱动收窄键）与闭包 JOIN 键**必须绑 finished_material_no**：
--      继续绑 up.code 会拿零件号去匹配本报价行的成品料号，且当同一零件被多个成品共用时，
--      会把 A 成品的电镀费串到 B 成品的报价行上（2026-08-03 单事务造数 A/B 实证：旧写法在
--      只共用零件的 P-TEST9 上凭空多出 1.396/1.550 两行，新写法 0 行）。
--
-- ⚠️ 不用 报价侧.md §7.4 的 PIVOT 行转列写法：本模板把「要素」做成了一列（每行一个费用项），保持行式即可。
--    行式下没有 GROUP BY，§5.4 随动规则 2「GROUP BY 必须含 up.code」天然满足（每行独立、不会被 MAX() 吞并）。
-- ⚠️ 「料件」= 投入料号名称（经 up.code 取名），**不是电镀元素名称**（镀层元素 Ag/Ni 在 unit_price 无源）。
-- ⚠️ up.seq_no 无写入路径（Q17 的 CONTENT 只有 pricing_price/currency/unit/defect_rate）→「项次」列恒空、可手填。
--
-- task-0726 BOM 闭包：hf_part_no 输出「闭包根料号」，使子件的数据也进入本页签（§3.8.1 判据：
--    本页签展示的是整个产品结构的成本构成）。闭包 JOIN 键按 §3.8.2 对照表「零件/unit_price」行 = finished_material_no。
-- ⚠️ 铁律1：白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，否则
--          QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入报
--          "each UNION query must have the same number of columns"。
-- ⚠️ 铁律2：必须 LEFT JOIN bom_closure_d + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
--          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
WITH RECURSIVE bom_closure AS (
  SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
  FROM material_bom_item b
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
  UNION            -- ⚠️ 铁律3：用 UNION 去重，不能 UNION ALL（同一对父子常并存 RECIPE+ASSEMBLY 两条边）
  SELECT c.root_no, b.component_no, c.lvl + 1
  FROM bom_closure c
    JOIN material_bom_item b ON b.material_no = c.node_no
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
    AND c.lvl < 10                          -- 深度上限，防环
), bom_closure_d AS (
  -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只留一行（取最浅层）。
  --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍（§3.8.3 铁律3）。
  SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
)
SELECT
  COALESCE(cl.root_no, up.finished_material_no) AS hf_part_no,
  up.finished_material_no AS _销售料号,             -- 本行归属成品（repair-0802 起直接给出，不再靠闭包反推）
  COALESCE(mm.material_name, mr.name) AS _料件,     -- 投入料号名称（经 up.code = 被电镀的零件料号）
  up.seq_no AS _项次,                               -- Q17 不写 seq_no → 恒空，可手填
  up.cost_type AS _要素,                            -- 电镀加工费 / 电镀材料费
  up.pricing_price AS _费用,
  up.unit AS _单位,
  up.defect_rate AS _不良率
FROM unit_price up
  LEFT JOIN bom_closure_d cl ON cl.node_no = up.finished_material_no
  LEFT JOIN material_master mm ON mm.material_no = up.code
  LEFT JOIN material_recipe mr ON mr.code = up.code
WHERE up.system_type = 'QUOTE' AND up.price_type = 'PLATING' AND up.is_current = true
  AND up.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), up.finished_material_no, up.code, COALESCE(up.seq_no, 0), up.cost_type
