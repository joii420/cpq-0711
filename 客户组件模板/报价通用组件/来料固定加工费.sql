-- ============================================================================
-- 组件：来料固定加工费（报价通用模板）    SQL 视图名：ll_view    驱动路径：$ll_view
-- requiredVariables: ["customerCode"]    scope: COMPONENT
-- 页签属性：tabType=(空) · partNoField=(空) · partNameField=(空)
--           rowKeyFields=["销售料号", "料件", "项次", "工序"] · sortField=项次
--
-- 【本文件是「可编辑副本」，不是运行时来源】改完二选一才生效：
--   ① UI：组件管理 →「来料固定加工费」组件 → SQL 视图 → 粘贴保存（保存即 dry-run 校验）
--   ② API：PUT /api/cpq/components/{componentId}/sql-views/{viewId}
--          body: {"sqlViewName":"ll_view","sqlTemplate":"<本文件内容>",
--                 "scope":"COMPONENT","requiredVariables":["customerCode"]}
--   改完记得把同目录 来料固定加工费.json 也同步重出，否则 JSON 与实际配置漂移。
--
-- 【直连 PG 验证】把命名参数换字面量后跑（DRAFT 报价单还要模拟 pending 开域，见 README §验证）：
--   :customerCode → '<客户编号>' · :priceBaseDate → CURRENT_DATE · :total_material_no → ARRAY[...]::text[]
-- ============================================================================
-- 来料固定加工费（费用页签，tabType 空不参与树；price_type='INCOMING_MATERIAL_PROCESS'）
-- 「销售料号」= 所属销售料号(up.finished_material_no)；「料件」= 来料件名（经 up.code 取名）。
-- ⚠️ 该 price_type 的金额落在 base_value、pricing_price 恒空 → 绑 COALESCE(pricing_price, base_value)。
-- task-0726 BOM 闭包：hf_part_no 输出「闭包根料号」，使子件的数据也进入本页签（§3.8.1 判据）。
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
  up.finished_material_no AS _销售料号,
  COALESCE(mm.material_name, mr.name) AS _料件,
  up.seq_no AS _项次,
  COALESCE(pm.process_name, up.operation_no) AS _工序,
  COALESCE(up.pricing_price, up.base_value) AS _加工费,
  up.cost_ratio AS _比例,
  up.unit AS _单位
FROM unit_price up
  LEFT JOIN bom_closure_d cl ON cl.node_no = up.finished_material_no
  LEFT JOIN material_master mm ON mm.material_no = up.code
  LEFT JOIN material_recipe mr ON mr.code = up.code
  LEFT JOIN process_master pm ON pm.process_no = up.operation_no
WHERE up.system_type = 'QUOTE' AND up.price_type = 'INCOMING_MATERIAL_PROCESS' AND up.is_current = true
  AND up.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), up.finished_material_no, up.seq_no
