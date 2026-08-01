-- ============================================================================
-- 组件：材料成本（施耐德-1）   SQL 视图名：mc_view   驱动路径：$mc_view
-- requiredVariables: ["customerCode", "priceBaseDate"]   scope: COMPONENT
--
-- 2026-07-28 修订：只改 `_产出类型` 一处（原绑 ebi.component_usage_type 恒为空），
--                  其余逐字未动。详见 dev-docs/rule-0724-组件模板配置/报价侧.md §7.2.1。
-- ============================================================================
-- 材料成本(材质元素, 平铺契约: hf_part_no + :customerCode; 元素单价接客户价格策略)
-- 「销售料号」= 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
--   (task-0726 §3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- 「料号」= 材质料号(ebi.material_part_no) = 本页签语义层级的料号(§3.4 第二步)。
-- task-0726 BOM 闭包: hf_part_no 输出「闭包根料号」, 使子件的数据也进入本页签。
-- ⚠️ 铁律1(§3.8.3): 白名单表必须在顶层 FROM，不能写成 FROM bom_closure JOIN <白名单表>，
--          否则 QuotePendingRewriter 主位表探测退化选中 CTE base case 的表 → 锚点注入报
--          "each UNION query must have the same number of columns"。
-- ⚠️ 铁律2: 必须 LEFT JOIN bom_closure_d + COALESCE 兜底 —— 无任何 BOM 行的单料产品不在闭包里，
--          写成 INNER JOIN 或直取 cl.root_no 会让这类产品的页签整个空白。
WITH RECURSIVE bom_closure AS (
  SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
  FROM material_bom_item b
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
  UNION            -- ⚠️ 用 UNION 去重，不能用 UNION ALL：同一对父子常并存多条 BOM 边
                   --    (RECIPE + ASSEMBLY 各一条) → UNION ALL 会生出重复路径
  SELECT c.root_no, b.component_no, c.lvl + 1
  FROM bom_closure c
    JOIN material_bom_item b ON b.material_no = c.node_no
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
    AND c.lvl < 10                          -- 深度上限，防环
), bom_closure_d AS (
  -- 🔒 (root,node) 唯一化：多条边/多层路径到达同一子件时只保留一行(取最浅层)。
  --    漏这层 → 平铺页签行数成倍复制、页签小计翻倍(task-0726 §3.8.3 铁律3)。
  SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
)
SELECT
  COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
  ebi.material_no AS _销售料号,
  ebi.material_part_no AS _料号,                   -- 材质料号(partNoField)
  COALESCE(mr.name, mm2.material_name) AS _料件,   -- 材质名：逐行取自本行 material_part_no
  -- 「产出类型」= 物料BOM 的「产出料号类型」(MaterialBomMergeHandler:148 → material_bom_item.component_usage_type)。
  -- ⚠️ 不在 element_bom_item 上：该表的同名列无任何写入路径，绑它恒为空。
  -- ⚠️ 必须用相关标量子查询而非 LEFT JOIN：material_bom_item 是版本化表(§3.6)，
  --    且同一对父子常并存 RECIPE+ASSEMBLY 多条边(§3.8.3 铁律3) → JOIN 会让材料成本行倍增。
  (SELECT t.component_usage_type
     FROM material_bom_item t
    WHERE t.system_type  = 'QUOTE' AND t.is_current
      AND t.customer_no  = :customerCode
      AND t.material_no  = ebi.material_no
      AND t.component_no = ebi.material_part_no
    ORDER BY CASE WHEN t.characteristic = 'RECIPE' THEN 0 ELSE 1 END, t.characteristic
    LIMIT 1) AS _产出类型,
  ebi.seq_no AS _项次,
  ebi.component_no AS _元素,
  ebi.content AS _组成含量,
  ebi.scrap_rate AS _损耗率,
  ebi.composition_qty AS _毛重,
  ebi.issue_unit AS _毛用量单位,
  cep.unit_price AS 元素单价,                       -- 价格策略列：别名逐字、不加 _
  cep.currency AS 货币
FROM element_bom_item ebi
  LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
  LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
  LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
  LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
         ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键(硬约束 1/2)
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no
