-- V334__costing_views_version_filter.sql
-- task-0713 B4：核价模板「有版本列」组件 $view + 主树递归 SQL 接入 :versionFilter 宏。
--
-- 两处改造（每个目标视图）：
--   ① 本表版本列 AS view_version（约定列名，前端据此识别可切换行）
--   ② is_current 硬编码谓词 → :versionFilter(is_current列, 版本列, 料号键列)
--
-- 范围说明（有意收窄，非遗漏）：component_sql_view 里 cz_view/gx_view/ys_view/zh_view/pj_view/
-- zpj_view 这些 sql_view_name 在库里有多个物理副本（模板被克隆时 component 也被克隆出独立行，
-- 各自 sql_template 内容可能不同——已实测确认 zpj_view 至少有一份内容与本迁移目标不同）。
-- 本迁移只精确改动下列已核实内容的 component_id（覆盖"BOM树演示-核价模板"+"核价模板0703"两个
-- 可验证模板 & 一张真实 PENDING 核价单 HJ-20260713-0487 的渲染链路），不做基于 sql_view_name 的
-- 批量 UPDATE（避免误伤内容不同的克隆副本）。其余克隆副本暂不支持版本切换，留作已知限制。
--
-- 顺手修复：zpj_view(component_id=089862b0-...) 原 sql_template 存在两个既有缺陷（均与本次改造
-- 目标直接相关，一并解决）：
--   (a) 引用了从未被真正展开的 :spineKeys(...) 宏（SqlViewExecutor 从不调用
--       SpineKeysMacro.expandForExecution），执行时会被通用占位符替换成字面量 NULL，
--       产出 "NULL(...)" 语法错误（已用 psql 实测复现）——该页签的业务行因此在
--       CostingTreeRenderService 里永远走异常兜底(空 map)。
--   (b) 未输出 CostingTreeRenderService 契约要求的 material_no / parent_no 系统列
--       （只有 hf_part_no / 子料号），树页签边匹配 edgeKey(parent_no, material_no) 永远落空。
-- 本迁移一并加上 parent_no/material_no 别名、去掉失效的 spineKeys、改用 versionFilter。
-- gx_view 另有一个不在本任务范围内的既有 bug（price_type='MATERIAL' 与实际数据 'PROCESS'/
-- 'SELF_PROCESS' 不匹配，恒 0 行）——不属于版本切换范畴，本迁移不修，仅记录供后续排查。

-- ── 1) pj_view（配件/主树，COMP-0039，"核价模板0703"，HJ-20260713-0487 实际使用） ──────

UPDATE component_sql_view
   SET sql_template = $sql$select
mbt.material_no  AS parent_no,
mbt.component_no material_no,
mbt.bom_version AS view_version,
mbt.seq_no 序号,
mm.material_name 料号名称,
mbt.composition_qty 数量,
mbt.issue_unit 单位
 from material_bom_item  mbt
 left join material_master mm on mm.material_no = mbt.component_no
where mbt.system_type = 'PRICING'
  and mbt.customer_no = '_GLOBAL_'
  and :versionFilter(mbt.is_current, mbt.bom_version, mbt.material_no)
and mbt.component_no = ANY(:total_material_no)
    order by mbt.seq_no$sql$,
       updated_at = now()
 WHERE sql_view_name = 'pj_view'
   AND component_id = '428c5db8-9604-4d14-ab70-361ac0f34e7f';

-- ── 2) ys_view（元素，COMP-0040，"核价模板0703"） ───────────────────────────────

UPDATE component_sql_view
   SET sql_template = $sql$--元素
SELECT
  ebi.material_no        material_no,
  ebi.characteristic     AS view_version,
  CASE mm.material_type
       WHEN '1' THEN '银点类' WHEN '2' THEN '非银点类' ELSE '其他' END  material_type,
  ebi.seq_no             序号,
  ebi.component_no       元素,
  ebi.content            含量,
  gvv.value_number       单价,
  ebi.issue_unit         单位
FROM element_bom_item ebi
  LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
  LEFT JOIN global_variable_value gvv ON gvv.key_id = ebi.component_no AND gvv.var_code = 'COST_ELEMENT'
WHERE ebi.system_type = 'PRICING' AND :versionFilter(ebi.is_current, ebi.characteristic, ebi.material_no)$sql$,
       updated_at = now()
 WHERE sql_view_name = 'ys_view'
   AND component_id = '17190112-4170-46ba-912b-b53dd29f574f';

-- ── 3) zpj_view（子配件/主树，"BOM树演示-核价模板"）：versionFilter + 修复 (a)(b) ──────

UPDATE component_sql_view
   SET sql_template = $sql$select
mbt.component_no hf_part_no,
mbt.material_no  AS parent_no,
mbt.component_no AS material_no,
mbt.bom_version  AS view_version,
mbt.seq_no 序号,
mbt.component_no 子料号,
mm.material_name 子料号名称,
mbt.composition_qty 数量,
mbt.issue_unit 单位
 from material_bom_item  mbt
 left join material_master mm on mm.material_no = mbt.component_no
where mbt.system_type = 'PRICING'
  and mbt.customer_no = '_GLOBAL_'
  and :versionFilter(mbt.is_current, mbt.bom_version, mbt.material_no)
    order by mbt.seq_no$sql$,
       updated_at = now()
 WHERE sql_view_name = 'zpj_view'
   AND component_id = '089862b0-26ea-4925-9d3c-e0aa9cc0bccc';

-- ── 4) cz_view（材质，"BOM树演示-核价模板"） ────────────────────────────────────

UPDATE component_sql_view
   SET sql_template = $sql$SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    asy.bom_version  AS view_version,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no       AS child_seq,
    asy.component_no AS material_code,
    mr.code    AS recipe_code,
    mr.symbol    AS chemical_symbol,
    COALESCE(asy.component_usage_type, mm.material_type, mm.material_name) AS material_name,
    COALESCE(mm.specification, asy.component_usage_type) AS spec_label,
    asy.component_usage_type AS recipe_type
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
left join material_recipe mr on mm.material_recipe_id = mr.id
WHERE asy.system_type   = 'PRICING'
AND asy.characteristic IS NULL
and :versionFilter(asy.is_current, asy.bom_version, asy.material_no)$sql$,
       updated_at = now()
 WHERE sql_view_name = 'cz_view'
   AND component_id = '1f2914e5-88d4-4630-96b7-febc3a499a0a';

-- ── 5) gx_view（工序，"BOM树演示-核价模板"）：versionFilter；price_type 既有 bug 不动 ──

UPDATE component_sql_view
   SET sql_template = $sql$select
up.finished_material_no hf_part_no,
up.version_no AS view_version,
up.code 子件,
up.seq_no 序号,
up.operation_no 工序代码,
pm.process_name 工序,
gvv.value_number 成材率
from
unit_price up
left join global_variable_value gvv on gvv.key_id = up.operation_no and gvv.var_code = 'PROCESS_DEFAULT_YIELD'
left join process_master pm on pm.process_no = up.operation_no
where up.system_type = 'QUOTE' AND :versionFilter(up.is_current, up.version_no, up.finished_material_no)
and up.price_type= 'MATERIAL'
and up.cost_type = '自制加工费'
and up.customer_no = :customerCode$sql$,
       updated_at = now()
 WHERE sql_view_name = 'gx_view'
   AND component_id = '54805dfa-ebcc-4de6-94f2-a8ef1cb7cf80';

-- ── 6) zh_view（组合工艺，"BOM树演示-核价模板"）：capacity 无 version_no 列，实用 calc_version ──

UPDATE component_sql_view
   SET sql_template = $sql$select
material_no hf_part_no,
calc_version AS view_version,
seq_no 序号,
process_no 工艺代码,
default_defect_rate 不良率
from capacity where :versionFilter(is_current, calc_version, material_no) and system_type = 'PRICING'$sql$,
       updated_at = now()
 WHERE sql_view_name = 'zh_view'
   AND component_id = '73d2934a-beda-4c2f-8cde-a066c50b3039';

-- ── 7) ys_view（元素，"BOM树演示-核价模板"，componentId=b3359f70） ─────────────────

UPDATE component_sql_view
   SET sql_template = $sql$SELECT
  ebi.material_no        hf_part_no,          -- ★ 节点自身料号（元素所属料号），不是父件
  ebi.material_no        料号,
  ebi.characteristic     AS view_version,
  CASE mm.material_type
       WHEN '1' THEN '银点类' WHEN '2' THEN '非银点类' ELSE '其他' END  material_type,
  ebi.seq_no             序号,
  ebi.component_no       元素,
  ebi.content            含量,
  gvv.value_number       单价,
  ebi.issue_unit         单位
FROM element_bom_item ebi
  LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
  LEFT JOIN global_variable_value gvv ON gvv.key_id = ebi.component_no AND gvv.var_code = 'COST_ELEMENT'
WHERE ebi.system_type = 'PRICING' AND :versionFilter(ebi.is_current, ebi.characteristic, ebi.material_no)$sql$,
       updated_at = now()
 WHERE sql_view_name = 'ys_view'
   AND component_id = 'b3359f70-f830-40f5-ad0f-938d1ce3970c';

-- ── 8) costing_bom_tree_config 主树递归 SQL（全局唯一 is_active=true 记录）：3 处 is_current → versionFilter ──

UPDATE costing_bom_tree_config
   SET sql_template = $sql$WITH RECURSIVE bom AS (
  SELECT
    p::text                                        AS root_no,
    p::text                                        AS material_no,
    (SELECT bv.bom_version::text
       FROM material_bom_item bv
      WHERE bv.material_no = p
        AND bv.customer_no = '_GLOBAL_'
        AND bv.system_type = 'PRICING'
        AND :versionFilter(bv.is_current, bv.bom_version, bv.material_no)
      LIMIT 1)                                     AS bom_version,
    NULL::text                                     AS parent_no,
    p::text                                        AS node_path
  FROM unnest(:production_part_nos) AS p

  UNION ALL

  SELECT
    b.root_no,
    ch.component_no::text                          AS material_no,
    (SELECT bv.bom_version::text
       FROM material_bom_item bv
      WHERE bv.material_no = ch.component_no
        AND bv.customer_no = '_GLOBAL_'
        AND bv.system_type = 'PRICING'
        AND :versionFilter(bv.is_current, bv.bom_version, bv.material_no)
      LIMIT 1)                                     AS bom_version,
    ch.material_no::text                           AS parent_no,
    (b.node_path || '/' || ch.component_no)::text  AS node_path
  FROM material_bom_item ch
  JOIN bom b ON ch.material_no = b.material_no
  WHERE ch.customer_no  = '_GLOBAL_'
    AND ch.system_type  = 'PRICING'
    AND :versionFilter(ch.is_current, ch.bom_version, ch.material_no)
    AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path
FROM bom$sql$,
       updated_at = now()
 WHERE is_active = true;
