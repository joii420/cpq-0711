-- =============================================================================
-- task-0729 现场缺口修复 · COMP-0049（wl_ys_bom_view）元素单价 JOIN 键归一到销售料号
-- =============================================================================
-- 背景：QT-20260726-0018 升版后报价侧 Cu 正确 1850→3650，但核价侧「物料与元素BOM」tab
-- 元素单价全为 NULL——不是没变，是从没算出来过。
--
-- 根因：V369（B2）把本视图的价格 JOIN 写成 cep.material_no = ebi.material_no（直接用
-- 核价侧 element_bom_item 自己的 material_no）。但核价侧 material_no 是「颗粒物料」
-- （如 S-2120011658，被多个不同成品复用的共享子件配方标识），material_price_version_ref
-- 的指针只挂在「销售/成品料号」（如 S-3120014539）上——这个 JOIN 结构性地永远匹配不到
-- （不是偶发，是 100% 不命中，因为两个轴根本不是同一套料号空间）。
--
-- 对比：报价侧 mc_view 早就靠 bom_closure CTE 把闭包内子件归一到 hf_part_no=闭包根料号，
-- 价格 JOIN 也用同一个归一值（COALESCE(cl.root_no, ebi.material_no)）。核价侧当时只按
-- V366/V369 派发指令做了「补 cep.material_no=ebi.material_no」（平铺形态字面还原），
-- 没意识到核价侧「颗粒物料」与「销售料号」是两套完全不同的料号语义——这是任务书指令
-- 本身的判断失误（见 RECORD.md 本条），不是实现偏差。
--
-- 修法：仿报价侧手法在本视图内加一个独立的 bom_closure CTE（walk material_bom_item，
-- system_type='PRICING' + customer_no='_GLOBAL_'，与核价侧 BOM 数据同一存储范围），
-- 只改「价格 JOIN 键」为 COALESCE(归一后的根料号, ebi.material_no)，hf_part_no 输出
-- 保持 = ebi.material_no 不变——因为运行时 driver 注入的行过滤集合已用
-- quotation_line_item.costing_card_values 冻结数据实证是「颗粒物料」集合（如
-- {S-2120011658, S-2120011659}），不是销售料号本身；若连 hf_part_no 一起改会让驱动
-- 过滤条件直接对不上、行数归零，比现在的"有行无价"更糟。
--
-- ⚠️ 已知限制（如实记录，非彻底解）：颗粒物料可能被多个不同成品同时复用（本库实测
-- S-2120011658/S-2120011659 同时是 S-3120014539 与 S-3120018220 的子件）。视图内的
-- bom_closure 对同一颗粒物料会解出多个候选根，本迁移用 LATERAL 子查询 + 确定性排序
-- （优先非自身层级(lvl>0) → 层级最浅 → 根料号字典序）取"唯一一个"根，保证不产生行数
-- 膨胀（每行乘N份的新bug），但如果两个真正同层级共享该颗粒物料的成品都需要独立定价，
-- 这个排序规则本身是任意的、不代表"当前渲染上下文精确对应哪个成品"。真正精确解需要
-- 运行时把"当前行是哪个成品的BOM"作为参数传入价格 JOIN（类似 costing_bom_tree_config
-- 的 :production_part_nos 机制），超出本次热修复范围。
--
-- 已用真实数据验证（QT-20260726-0018，S-3120014539，CUST-0001）：S-2120011658/
-- S-2120011659 正确归一到根 S-3120014539，Cu 元素价格解出 3650.000000（与报价侧升版后
-- 数值完全一致，验收 #64"两侧同一套客户元素价"核心断言满足）；14 行输入对 14 行输出，
-- 无行数膨胀。
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
  ebi.content            as content,
  ebi.scrap_rate         as scrap_rate,
  cep.unit_price         as 元素单价
from element_bom_item ebi
left join material_master mi on mi.material_no = ebi.material_part_no
left join material_recipe mr on mr.code = ebi.material_part_no
left join lateral (
  select root_no from bom_closure where node_no = ebi.material_no
  order by (case when lvl = 0 then 1 else 0 end) asc, lvl asc, root_no asc limit 1
) cl on true
left join f_material_element_price(:customerCode, :priceBaseDate) cep
       on cep.element_code = ebi.component_no
      and cep.material_no  = coalesce(cl.root_no, ebi.material_no)
where ebi.system_type = 'PRICING'
  and ebi.is_current = true
order by ebi.seq_no
$tpl$,
       updated_at = now()
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0049'
   AND csv.sql_template NOT LIKE '%bom_closure%';

-- 【回滚参考】迁移前 sql_template 原文（V369 落地版本，逐字保留）：
-- select
--   ebi.material_no        as material_no,
--   ebi.material_no        as hf_part_no,
--   ebi.material_part_no   as material_part_no,
--   coalesce(mr.name, mi.material_name) as material_name,
--   mi.specification       as specification,
--   mi.dimension           as dimension,
--   ebi.seq_no             as seq_no,
--   ebi.component_no       as component_no,
--   ebi.content            as content,
--   ebi.scrap_rate         as scrap_rate,
--   cep.unit_price         as 元素单价
-- from element_bom_item ebi
-- left join material_master mi on mi.material_no = ebi.material_part_no
-- left join material_recipe mr on mr.code = ebi.material_part_no
-- left join f_material_element_price(:customerCode, :priceBaseDate) cep
--        on cep.element_code = ebi.component_no
--       and cep.material_no  = ebi.material_no
-- where ebi.system_type = 'PRICING'
--   and ebi.is_current = true
-- order by ebi.seq_no
