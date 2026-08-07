-- ============================================================================
-- 组件：产品（报价通用模板）    SQL 视图名：cp_view    驱动路径：$cp_view
-- requiredVariables: ["customerCode"]    scope: COMPONENT
-- 页签属性：tabType=主件 · partNoField=销售料号 · partNameField=(空)
--           rowKeyFields=["销售料号"] · sortField=(空)
--
-- 【本文件是「可编辑副本」，不是运行时来源】改完二选一才生效：
--   ① UI：组件管理 →「产品」组件 → SQL 视图 → 粘贴保存（保存即 dry-run 校验）
--   ② API：PUT /api/cpq/components/{componentId}/sql-views/{viewId}
--          body: {"sqlViewName":"cp_view","sqlTemplate":"<本文件内容>",
--                 "scope":"COMPONENT","requiredVariables":["customerCode"]}
--   改完记得把同目录 产品.json 也同步重出，否则 JSON 与实际配置漂移。
--
-- 【直连 PG 验证】把命名参数换字面量后跑（DRAFT 报价单还要模拟 pending 开域，见 README §验证）：
--   :customerCode → '<客户编号>' · :priceBaseDate → CURRENT_DATE · :total_material_no → ARRAY[...]::text[]
-- ============================================================================
-- 产品（主件，平铺契约：hf_part_no + :customerCode）
-- 本页签展示「产品自身属性」→ 按 §3.8.1 判据【不做 BOM 闭包】，否则会串入子件信息。
-- 「税率」无源手填，故本视图不输出该列（§6 自检 0：视图输出列与字段一一对应，无没人绑的死列）。
SELECT
  mm.material_no AS hf_part_no,
  mm.material_no AS _销售料号,
  mcm.customer_material_name AS _客户料号名称,
  mcm.customer_product_no AS _客户产品编号,
  mcm.quote_currency AS _报价货币
FROM material_master mm
LEFT JOIN material_customer_map mcm
  ON mcm.material_no = mm.material_no AND mcm.customer_no = :customerCode
