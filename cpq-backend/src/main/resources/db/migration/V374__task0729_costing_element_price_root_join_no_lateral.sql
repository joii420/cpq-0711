-- =============================================================================
-- task-0729 · V373 跟进修复：LATERAL 子查询在本系统自定义 SQL 编译层不生效
-- =============================================================================
-- V373 用 LEFT JOIN LATERAL 解析根料号，直接 psql 验证 SQL 本身正确（14行→14行、Cu 正确
-- 解出 3650），但通过应用真实刷新链路（CardSnapshotService#refreshCostingCardValuesForLine
-- → component_sql_view 走本系统自定义 SQL 编译/改写层，非原生直通 JDBC）验证时元素单价
-- 仍全为 NULL——说明该自定义编译层（ImplicitJoinRewriter/CachedSqlCompiler 等，见
-- CLAUDE.md「视图DROP CASCADE重建后必须重启Quarkus」条目）对 LATERAL 子查询处理有问题
-- （要么整体忽略、要么改写后语义丢失，具体内部机制未展开排查，超出本次热修复排查预算）。
--
-- 改用不含 LATERAL 关键字的等价写法——相关子查询直接写在 JOIN ... ON 条件里（标准 SQL，
-- 无需 LATERAL 关键字，各类查询编译器兼容性远好于 LATERAL），语义与 V373 完全一致：
-- 同一颗粒物料的候选根优先取非自身层级(lvl>0)最浅一层，样内按根料号字典序确定性去重。
-- 已重新走同一条真实刷新链路验证：QT-20260726-0018/S-3120014539 的「物料与元素BOM」tab
-- 元素单价 Cu=3650.000000（与报价侧升版后数值一致），301/Ni 仍为 null（元素主表本就无对应
-- 价格，非本次改动引入，属预期）。
-- =============================================================================

UPDATE component_sql_view csv
   SET sql_template = $tpl$with recursive bom_closure as (
  select distinct b.material_no as root_no, b.material_no as node_no, 0 as lvl
  from material_bom_item b
  where b.system_type = 'PRICING' and b.is_current and b.customer_no = '_GLOBAL_'
  union all
  select c.root_no, b.component_no, c.lvl + 1
  from bom_closure c
    join material_bom_item b on b.material_no = c.node_no
  where b.system_type = 'PRICING' and b.is_current and b.customer_no = '_GLOBAL_'
    and c.lvl < 10
)
select
  ebi.material_no        as material_no,
  ebi.material_no        as hf_part_no,
  ebi.material_part_no   as material_part_no,
  coalesce(mr.name, mi.material_name) as material_name,
  mi.specification       as specification,
  mi.dimension           as dimension,
  ebi.seq_no             as seq_no,
  ebi.component_no       as component_no,
  ebi.content             as content,
  ebi.scrap_rate         as scrap_rate,
  cep.unit_price         as 元素单价
from element_bom_item ebi
left join material_master mi on mi.material_no = ebi.material_part_no
left join material_recipe mr on mr.code = ebi.material_part_no
left join f_material_element_price(:customerCode, :priceBaseDate) cep
       on cep.element_code = ebi.component_no
      and cep.material_no  = coalesce(
            (select cl.root_no from bom_closure cl where cl.node_no = ebi.material_no
              order by (case when cl.lvl = 0 then 1 else 0 end) asc, cl.lvl asc, cl.root_no asc limit 1),
            ebi.material_no)
where ebi.system_type = 'PRICING'
  and ebi.is_current = true
order by ebi.seq_no
$tpl$,
       updated_at = now()
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0049'
   AND csv.sql_template LIKE '%LATERAL%';
