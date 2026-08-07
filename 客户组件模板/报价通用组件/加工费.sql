-- ============================================================================
-- 组件：加工费（报价通用模板）    SQL 视图名：jg_view    驱动路径：$jg_view
-- requiredVariables: ["customerCode"]    scope: COMPONENT
-- 页签属性：tabType=零件 · partNoField=料号 · partNameField=料件
--           rowKeyFields=["销售料号", "料号", "项次", "工序"] · sortField=项次
--
-- 【本文件是「可编辑副本」，不是运行时来源】改完二选一才生效：
--   ① UI：组件管理 →「加工费」组件 → SQL 视图 → 粘贴保存（保存即 dry-run 校验）
--   ② API：PUT /api/cpq/components/{componentId}/sql-views/{viewId}
--          body: {"sqlViewName":"jg_view","sqlTemplate":"<本文件内容>",
--                 "scope":"COMPONENT","requiredVariables":["customerCode"]}
--   改完记得把同目录 加工费.json 也同步重出，否则 JSON 与实际配置漂移。
--
-- 【直连 PG 验证】把命名参数换字面量后跑（DRAFT 报价单还要模拟 pending 开域，见 README §验证）：
--   :customerCode → '<客户编号>' · :priceBaseDate → CURRENT_DATE · :total_material_no → ARRAY[...]::text[]
-- ============================================================================
-- 加工费（零件，平铺契约；price_type='PROCESS'；工序=process_master.process_name）
-- 「销售料号」= 所属销售料号(up.finished_material_no)，「料号」= 零件料号(up.code)。
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
  up.code AS _料号,                                 -- 零件料号（partNoField）
  COALESCE(mm.material_name, mr.name) AS _料件,     -- 该费用项针对的零件名（经 up.code）
  up.seq_no AS _项次,
  COALESCE(pm.process_name, up.operation_no) AS _工序,
  up.pricing_price AS _加工费,
  up.cost_ratio AS _比例,
  up.unit AS _单位
FROM unit_price up
  LEFT JOIN bom_closure_d cl ON cl.node_no = up.finished_material_no
  LEFT JOIN material_master mm ON mm.material_no = up.code
  LEFT JOIN material_recipe mr ON mr.code = up.code
  LEFT JOIN process_master pm ON pm.process_no = up.operation_no
WHERE up.system_type = 'QUOTE' AND up.price_type = 'PROCESS' AND up.is_current = true
  AND up.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), up.finished_material_no, up.seq_no
