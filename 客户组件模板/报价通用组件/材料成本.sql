-- ============================================================================
-- 组件：材料成本（报价通用模板）    SQL 视图名：mc_view    驱动路径：$mc_view
-- requiredVariables: ["customerCode", "priceBaseDate"]    scope: COMPONENT
-- 页签属性：tabType=材质元素 · partNoField=料号 · partNameField=料件
--           rowKeyFields=["销售料号", "料号", "元素"] · sortField=项次
--
-- 【本文件是「可编辑副本」，不是运行时来源】改完二选一才生效：
--   ① UI：组件管理 →「材料成本」组件 → SQL 视图 → 粘贴保存（保存即 dry-run 校验）
--   ② API：PUT /api/cpq/components/{componentId}/sql-views/{viewId}
--          body: {"sqlViewName":"mc_view","sqlTemplate":"<本文件内容>",
--                 "scope":"COMPONENT","requiredVariables":["customerCode", "priceBaseDate"]}
--   改完记得把同目录 材料成本.json 也同步重出，否则 JSON 与实际配置漂移。
--
-- 【直连 PG 验证】把命名参数换字面量后跑（DRAFT 报价单还要模拟 pending 开域，见 README §验证）：
--   :customerCode → '<客户编号>' · :priceBaseDate → CURRENT_DATE · :total_material_no → ARRAY[...]::text[]
-- ============================================================================
-- 材料成本（材质元素，平铺契约：hf_part_no + :customerCode；元素单价接客户价格策略）
-- 「销售料号」= 所属销售料号(ebi.material_no)：闭包后主件与子件的材质行同页签共存，靠本列区分归属
--   (§3.8.4)；它必须同时在 rowKeyFields 里，否则撞键 → 编辑串行/末值塌缩。
-- 「料号」= 材质料号(ebi.material_part_no) = 本页签语义层级的料号（§3.4 第二步）。
-- 「产出类型」不在本模板的本页签（该列在【物料】BOM 页签直接取 mbi.component_usage_type）。
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
  COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
  ebi.material_no AS _销售料号,
  ebi.material_part_no AS _料号,                   -- 材质料号（partNoField）
  COALESCE(mr.name, mm2.material_name) AS _料件,   -- 材质名：逐行取自本行 material_part_no
  ebi.seq_no AS _项次,
  ebi.component_no AS _元素,
  ebi.content AS _组成含量,
  ebi.scrap_rate AS _损耗率,
  ebi.composition_qty AS _毛重,
  ebi.issue_unit AS _毛用量单位,
  -- 【净用量】= element_bom_item.base_qty ←「物料与元素BOM」sheet 的「净用量」(Q04:72)。
  ebi.base_qty AS _净用量,
  -- 【净用量单位】⚠️ V6 只有一个 issue_unit 列：Q04 的口径是「净用量单位非空则存它，否则存毛用量单位」，
  --   即毛/净两个单位在库里共用同一列。2026-08-02 用户拍板：两列都绑 issue_unit（显示同值）。
  ebi.issue_unit AS _净用量单位,
  -- 【元素回收折扣】= element_bom_item.recovery_discount ←「元素回收折扣」sheet（Q05 按
  --   (销售料号, 材质料号, 元素) 三维 UPDATE 到本行上），故直接取本行列、无需子查询。
  ebi.recovery_discount AS _元素回收折扣,
  -- 价格策略列：别名逐字、不加 _（硬约束6）。本模板无「货币」列 → element_currency_field 留空。
  cep.unit_price AS 元素单价
FROM element_bom_item ebi
  LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
  LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
  LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
  LEFT JOIN f_customer_element_price(:customerCode, :priceBaseDate) cep
         ON cep.element_code = ebi.component_no     -- LEFT JOIN + 元素符号键（硬约束 1/2）
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no
