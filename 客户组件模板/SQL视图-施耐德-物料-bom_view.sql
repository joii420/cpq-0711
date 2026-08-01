-- ============================================================================
-- 组件：物料（施耐德-v3）   SQL 视图名：bom_view   驱动路径：$bom_view
-- requiredVariables: ["total_material_no"]   scope: COMPONENT
-- 页签类型 tabType: BOM   partNameField: 料件   rowKeyFields: ["料件"]
--
-- 2026-07-31 修订：新增 `_材料占比`（material_bom_item.material_ratio，V365 新增列），
--                  UNION ALL 根分支同步补 NULL::numeric 占位。其余逐字未动。
--                  规则见 dev-docs/rule-0724-组件模板配置/报价侧.md §7.2.2。
--
-- 【改这个文件后怎么生效】本文件是「可编辑副本」，不是运行时来源。改完二选一：
--   ① UI：组件管理 →「物料」组件 → SQL 视图 → 粘贴保存（保存即 dry-run 校验）
--   ② API：PUT /api/cpq/components/{componentId}/sql-views/{viewId}
--          body: {"sqlViewName":"bom_view","sqlTemplate":"<本文件内容>",
--                 "scope":"COMPONENT","requiredVariables":["total_material_no"]}
--      （componentId/viewId 在你导入 bundle 后才确定；GET /components?directoryId=<你的目录> 查）
--   改完记得把 bundle JSON 也重导一次，否则 JSON 与实际配置漂移。
--
-- 【改之前必看两条坑】
--   1. 本视图是 UNION ALL 两分支（主分支=BOM 边 / 根分支=无父边的成品自身）。
--      **加删任何一列，两个分支都要同步改**，根分支用 NULL::<类型> 占位。列数不等 PG 直接报错。
--   2. 验证别靠肉眼数列。直连 PG 跑一次最可靠（把命名参数换成字面量）：
--      sed 's/:total_material_no/ARRAY[\'S-80011\']::text[]/g' 本文件 | psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -f -
-- ============================================================================
-- 物料(BOM 树页签; 树契约: material_no=子 / parent_no=父 + :total_material_no; 边式全子件 + 根分支)
-- 走 Pass2 树渲染(BomTreeRenderService, usage=QUOTE)，禁用 :customerCode 平铺契约(报价侧.md §5.6)。
SELECT
  mbi.component_no AS material_no,
  mbi.material_no  AS parent_no,
  COALESCE(mm.material_name, mr.name) AS _料件,
  -- 【产出类型】= material_bom_item.component_usage_type（v2 由「材料成本」移入本页签）。
  --   写入路径已核实：MaterialBomMergeHandler:148 ← Excel「产出料号类型」(另有 P06/ConfigureProductService)。
  --   ⚠️ 同名列陷阱(§3.5)：element_bom_item 上的同名列 0 写点、恒 NULL，只有本表这列有数据。
  mbi.component_usage_type AS _产出类型,
  mbi.composition_qty AS _组成数量,
  mbi.rough_weight AS _材料毛重,
  mbi.net_weight AS _材料净重,
  mbi.weight_unit AS _单位,
  -- 【材料占比】= material_bom_item.material_ratio（V365 新增列，2026-07-31）。
  --   写入路径已核实：MaterialBomMergeHandler ← 报价侧「物料BOM」sheet 可选列「材质占比」。
  --   仅材质行(characteristic='RECIPE')有值——零件/外购件行 handler 显式置 NULL，故本树页签的
  --   材质边出数、组装边留空，属预期。小数口径(0.3=30%)。
  --   ⚠️ 本页签 FROM material_bom_item 可直接绑；若页签主表是 element_bom_item(如 $mc_view)，
  --      该表**没有**这个列，必须走跨表标量子查询(报价侧.md §7.2.2)。
  mbi.material_ratio AS _材料占比,
  mbi.scrap_rate AS _损耗率,
  -- 【回收价格/回收比例】= 来料回收折扣(unit_price price_type='INCOMING_MATERIAL_RECYCLE')。
  --   落库口径见 Q09IncomingRecoveryHandler：groupKey=(code=投入料号, finished_material_no=成品料号)，
  --   content=[seq_no=项次, cost_ratio=回收折扣%, pricing_price=值, currency, unit]（task-0730 扩列后）。
  --   匹配维度 =【投入料号 + 直接父件】(2026-07-31 用户拍板)：code=本行子件、finished=本行父件。
  --   ⚠️ 已知取舍：三层以上 BOM 中孙件那行取不到值(它的父不是成品)。
  -- ⚠️ unit_price 是版本化表 → 禁写进带 is_current 的 LEFT JOIN(§4 硬约束②：参数错位、dry-run 照过、
  --    只在真实渲染炸)，故用相关标量子查询。
  -- ⚠️ 树契约(§5.6)不注入 :customerCode，本子查询靠 (成品 + 投入料号) 两维定位。
  -- ⚠️ LIMIT 1：task-0730 起同一 (成品,投入料号) 可按项次存多行，取项次最小的一条。
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
-- 根分支：无父边的成品自身(树根)
SELECT
  mm.material_no, NULL::text, mm.material_name,
  NULL::varchar,                                  -- 产出类型
  NULL::numeric, NULL::numeric, NULL::numeric,
  NULL::varchar, NULL::numeric, NULL::numeric,   -- 单位 / 材料占比 / 损耗率
  NULL::numeric, NULL::numeric                    -- 根分支(成品自身)无回收折扣
FROM material_master mm
WHERE mm.material_no = ANY(:total_material_no)
  AND NOT EXISTS (SELECT 1 FROM material_bom_item x
                  WHERE x.component_no = mm.material_no
                    AND x.system_type = 'QUOTE' AND x.is_current)
