-- ============================================================================
-- 组件：物料（报价通用模板）    SQL 视图名：bom_view    驱动路径：$bom_view
-- requiredVariables: ["total_material_no"]    scope: COMPONENT
-- 页签属性：tabType=BOM · partNoField=(空) · partNameField=料件
--           rowKeyFields=["料件"] · sortField=(空)
--
-- 【本文件是「可编辑副本」，不是运行时来源】改完二选一才生效：
--   ① UI：组件管理 →「物料」组件 → SQL 视图 → 粘贴保存（保存即 dry-run 校验）
--   ② API：PUT /api/cpq/components/{componentId}/sql-views/{viewId}
--          body: {"sqlViewName":"bom_view","sqlTemplate":"<本文件内容>",
--                 "scope":"COMPONENT","requiredVariables":["total_material_no"]}
--   改完记得把同目录 物料.json 也同步重出，否则 JSON 与实际配置漂移。
--
-- 【直连 PG 验证】把命名参数换字面量后跑（DRAFT 报价单还要模拟 pending 开域，见 README §验证）：
--   :customerCode → '<客户编号>' · :priceBaseDate → CURRENT_DATE · :total_material_no → ARRAY[...]::text[]
-- ============================================================================
-- 物料（BOM 树页签；树契约：material_no=子 / parent_no=父 + :total_material_no；边式全子件 + 根分支）
-- 走 Pass2 树渲染（BomTreeRenderService, usage=QUOTE），禁用 :customerCode 平铺契约（报价侧.md §5.6）。
-- ⚠️ 加删任何一列，UNION ALL 两个分支都要同步改，根分支用 NULL::<类型> 占位；列数不等 PG 直接报错。
SELECT
  mbi.component_no AS material_no,
  mbi.material_no  AS parent_no,
  COALESCE(mm.material_name, mr.name) AS _料件,
  -- 【产出类型】= material_bom_item.component_usage_type ← Excel「产出料号类型/产出类型」。
  --   ⚠️ 同名列陷阱：element_bom_item 上的同名列 0 写点、恒 NULL，只有本表这列有数据（§7.2.1）。
  mbi.component_usage_type AS _产出类型,
  mbi.composition_qty AS _组成数量,
  mbi.rough_weight AS _材料毛重,
  mbi.net_weight AS _材料净重,
  mbi.weight_unit AS _单位,
  -- 【材料占比】= material_bom_item.material_ratio（V365）← Excel「材质占比/材料占比」。
  --   本页签 FROM material_bom_item 可直接绑；材质边出数、组装边留空属预期（报价侧.md §7.2.2）。
  mbi.material_ratio AS _材料占比,
  mbi.scrap_rate AS _损耗率,
  mbi.defect_rate AS _不良率,
  -- 【回收价格/回收比例】= 来料回收折扣（unit_price price_type='INCOMING_MATERIAL_RECYCLE'）。
  --   Q09IncomingRecoveryHandler：groupKey=(code=投入料号, finished_material_no=成品料号)，
  --   content=[seq_no=项次, cost_ratio=回收折扣%, pricing_price=值, currency, unit]。
  --   匹配维度 =【投入料号 + 直接父件】：code=本行子件、finished=本行父件。
  --   ⚠️ 已知取舍：三层以上 BOM 中孙件那行取不到值（它的父不是成品）。
  -- ⚠️ unit_price 是版本化表 → 禁写进带 is_current 的 LEFT JOIN（§3.6：参数错位、dry-run 照过、
  --    只在真实渲染炸），故用相关标量子查询。树契约不注入 :customerCode，靠(成品+投入料号)两维定位。
  (SELECT r.pricing_price FROM unit_price r
    WHERE r.system_type = 'QUOTE' AND r.price_type = 'INCOMING_MATERIAL_RECYCLE' AND r.is_current
      AND r.code = mbi.component_no AND r.finished_material_no = mbi.material_no
    ORDER BY COALESCE(r.seq_no, 0) LIMIT 1) AS _回收价格,
  (SELECT r.cost_ratio FROM unit_price r
    WHERE r.system_type = 'QUOTE' AND r.price_type = 'INCOMING_MATERIAL_RECYCLE' AND r.is_current
      AND r.code = mbi.component_no AND r.finished_material_no = mbi.material_no
    ORDER BY COALESCE(r.seq_no, 0) LIMIT 1) AS _回收比例
FROM material_bom_item mbi
  LEFT JOIN material_master mm ON mm.material_no = mbi.component_no
  LEFT JOIN material_recipe mr ON mr.code = mbi.component_no
WHERE mbi.system_type = 'QUOTE' AND mbi.is_current
  AND mbi.component_no = ANY(:total_material_no)
UNION ALL
-- 根分支：无父边的成品自身（树根）
SELECT
  mm.material_no, NULL::text, mm.material_name,
  NULL::varchar,                                 -- 产出类型
  NULL::numeric, NULL::numeric, NULL::numeric,   -- 组成数量 / 材料毛重 / 材料净重
  NULL::varchar,                                 -- 单位
  NULL::numeric, NULL::numeric, NULL::numeric,   -- 材料占比 / 损耗率 / 不良率
  NULL::numeric, NULL::numeric                   -- 根分支（成品自身）无回收折扣
FROM material_master mm
WHERE mm.material_no = ANY(:total_material_no)
  AND NOT EXISTS (SELECT 1 FROM material_bom_item x
                  WHERE x.component_no = mm.material_no
                    AND x.system_type = 'QUOTE' AND x.is_current)
