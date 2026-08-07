-- ============================================================================
-- 组件：组装加工费（报价通用模板）    SQL 视图名：zz_view    驱动路径：$zz_view
-- requiredVariables: []    scope: COMPONENT
-- 页签属性：tabType=主件 · partNoField=销售料号 · partNameField=料件
--           rowKeyFields=["项次", "工序"] · sortField=项次
--
-- 【本文件是「可编辑副本」，不是运行时来源】改完二选一才生效：
--   ① UI：组件管理 →「组装加工费」组件 → SQL 视图 → 粘贴保存（保存即 dry-run 校验）
--   ② API：PUT /api/cpq/components/{componentId}/sql-views/{viewId}
--          body: {"sqlViewName":"zz_view","sqlTemplate":"<本文件内容>",
--                 "scope":"COMPONENT","requiredVariables":[]}
--   改完记得把同目录 组装加工费.json 也同步重出，否则 JSON 与实际配置漂移。
--
-- 【直连 PG 验证】把命名参数换字面量后跑（DRAFT 报价单还要模拟 pending 开域，见 README §验证）：
--   :customerCode → '<客户编号>' · :priceBaseDate → CURRENT_DATE · :total_material_no → ARRAY[...]::text[]
-- ============================================================================
-- 组装加工费（主件=成品自身的组装工序；源 capacity，QUOTE 口径）
-- capacity 无 customer_no 列 → 不加 :customerCode 过滤，靠 hf_part_no=material_no 按本行成品收窄。
-- 本页签是「产品自身的组装工序」→ 不做 BOM 闭包（§3.8.1 判据）。
-- 现网工序名常写在 process_no（process_name 空）→ 工序绑 COALESCE(NULLIF(process_name,''), process_no)。
SELECT
  c.material_no AS hf_part_no,
  c.material_no AS _销售料号,
  COALESCE(mm.material_name, mr.name) AS _料件,
  c.seq_no AS _项次,
  COALESCE(NULLIF(c.process_name, ''), c.process_no) AS _工序,
  c.fixed_cost AS _加工费,
  c.capacity_unit AS _单位,
  c.default_defect_rate AS _不良率
FROM capacity c
  LEFT JOIN material_master mm ON mm.material_no = c.material_no
  LEFT JOIN material_recipe mr ON mr.code = c.material_no
WHERE c.system_type = 'QUOTE' AND c.is_current
ORDER BY c.seq_no
