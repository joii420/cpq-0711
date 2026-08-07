-- ============================================================================
-- 组件：其他费用（报价通用模板）    SQL 视图名：qt_view    驱动路径：$qt_view
-- requiredVariables: ["customerCode"]    scope: COMPONENT
-- 页签属性：tabType=主件 · partNoField=销售料号 · partNameField=(空)
--           rowKeyFields=["项次", "要素"] · sortField=项次
--
-- 【本文件是「可编辑副本」，不是运行时来源】改完二选一才生效：
--   ① UI：组件管理 →「其他费用」组件 → SQL 视图 → 粘贴保存（保存即 dry-run 校验）
--   ② API：PUT /api/cpq/components/{componentId}/sql-views/{viewId}
--          body: {"sqlViewName":"qt_view","sqlTemplate":"<本文件内容>",
--                 "scope":"COMPONENT","requiredVariables":["customerCode"]}
--   改完记得把同目录 其他费用.json 也同步重出，否则 JSON 与实际配置漂移。
--
-- 【直连 PG 验证】把命名参数换字面量后跑（DRAFT 报价单还要模拟 pending 开域，见 README §验证）：
--   :customerCode → '<客户编号>' · :priceBaseDate → CURRENT_DATE · :total_material_no → ARRAY[...]::text[]
-- ============================================================================
-- 其他费用（成品其他费用；price_type='FINISHED_MATERIAL_OTHER'；料号在 code 列）
-- 本页签是「本成品自身的费用项」→ 不做 BOM 闭包（§3.8.1 判据）。
-- ⚠️ 现网口径：固定金额项写 pricing_price，按比例登记的项（材料管理费/利润/税率…）值在 cost_ratio、
--    pricing_price 为空 —— 故「费用」「比例」两列并存，各取各的，不互相兜底。
SELECT
  up.code AS hf_part_no,
  up.code AS _销售料号,
  up.seq_no AS _项次,
  up.cost_type AS _要素,
  up.pricing_price AS _费用,
  up.cost_ratio AS _比例,
  up.unit AS _单位
FROM unit_price up
WHERE up.system_type = 'QUOTE' AND up.price_type = 'FINISHED_MATERIAL_OTHER' AND up.is_current = true
  AND up.customer_no = :customerCode
ORDER BY up.seq_no
