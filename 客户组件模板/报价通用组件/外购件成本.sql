-- ============================================================================
-- 组件：外购件成本（报价通用模板）    SQL 视图名：wg_view    驱动路径：$wg_view
-- requiredVariables: ["customerCode"]    scope: COMPONENT
-- 页签属性：tabType=外购件 · partNoField=(空) · partNameField=料件
--           rowKeyFields=["销售料号", "料件", "要素"] · sortField=(空)
--
-- 【本文件是「可编辑副本」，不是运行时来源】改完二选一才生效：
--   ① UI：组件管理 →「外购件成本」组件 → SQL 视图 → 粘贴保存（保存即 dry-run 校验）
--   ② API：PUT /api/cpq/components/{componentId}/sql-views/{viewId}
--          body: {"sqlViewName":"wg_view","sqlTemplate":"<本文件内容>",
--                 "scope":"COMPONENT","requiredVariables":["customerCode"]}
--   改完记得把同目录 外购件成本.json 也同步重出，否则 JSON 与实际配置漂移。
--
-- 【直连 PG 验证】把命名参数换字面量后跑（DRAFT 报价单还要模拟 pending 开域，见 README §验证）：
--   :customerCode → '<客户编号>' · :priceBaseDate → CURRENT_DATE · :total_material_no → ARRAY[...]::text[]
-- ============================================================================
-- 外购件成本（外购件，平铺契约；characteristic='OUTSOURCED'）
-- 模板无料号列 → partNoField 留空，组成件料号(component_no) 只作取名/取价 JOIN 键（§3.4）。
--
-- 【要素/费用/单位】= 组成件其他费用（unit_price price_type='COMPONENT_OTHER'）。
--   ⚠️ 匹配维度必须是【闭包根(成品) × 组成件】而非【直接父件 × 组成件】：Q13 的 groupKey
--      =(cost_type, code=组成件料号, finished_material_no=**成品料号**, operation_no, supplier_no)，
--      费用登记在成品维度。故 ON 用 COALESCE(cl.root_no, mbi.material_no)。
--
-- ⚠️ §3.6 pending 改写坑：版本化表(unit_price)禁写进带 is_current 的 LEFT JOIN ON。此处用【派生表】——
--    is_current 落在子查询 WHERE（安全位置），JOIN ON 内不含 is_current。不用标量子查询是因为
--    cost_type 是 groupKey 的一维、同一组成件可挂多条费用要素，必须真连接产生多行（行键已含「要素」）。
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
  COALESCE(cl.root_no, mbi.material_no) AS hf_part_no,
  mbi.material_no AS _销售料号,
  COALESCE(mm.material_name, mr.name) AS _料件,
  mbi.composition_qty AS _组成数量,
  mbi.issue_unit AS _组成单位,
  co.cost_type AS _要素,
  co.pricing_price AS _费用,
  co.unit AS _单位
FROM material_bom_item mbi
  LEFT JOIN bom_closure_d cl ON cl.node_no = mbi.material_no
  LEFT JOIN material_master mm ON mm.material_no = mbi.component_no
  LEFT JOIN material_recipe mr ON mr.code = mbi.component_no
  LEFT JOIN (
    SELECT up.code, up.finished_material_no, up.cost_type, up.pricing_price, up.unit
    FROM unit_price up
    WHERE up.system_type = 'QUOTE' AND up.price_type = 'COMPONENT_OTHER' AND up.is_current
      AND up.customer_no = :customerCode
  ) co ON co.code = mbi.component_no
      AND co.finished_material_no = COALESCE(cl.root_no, mbi.material_no)
WHERE mbi.system_type = 'QUOTE' AND mbi.is_current
  AND mbi.characteristic = 'OUTSOURCED'
  AND mbi.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), mbi.material_no, mbi.seq_no
