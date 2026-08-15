-- =====================================================================================
-- 核价组件模板-简易 · 组件 SQL 视图（4 个组件 / 4 张 $view）
-- 生成依据：dev-docs/rule-0724-组件模板配置/AGENT-配置入口.md（+ 核价侧.md）
-- 源模板  ：客户组件模板/核价组件/核价组件模板-简易.xlsx
-- 目标库  ：10.177.152.12:5432/cpq_db_0724
-- 生成日期：2026-08-14
--
-- ⚠️ 本文件**不是**建物理视图的 DDL，禁止用 psql 执行。
--    这里的每段 SQL 是「组件 SQL 视图」的 sql_template，随
--    客户组件模板/核价组件/核价组件模板-简易.json 一起导入（每个组件一条，scope=COMPONENT）。
--    本文件的用途 = 人工复核 / 事后在「组件管理 → SQL 视图」里对照修改。
--    禁在 DB 里建同名物理视图（AGENT-配置入口 §1）。
--
-- 口径（核价侧，逐条已核）：
--   · 无 :customerCode 过滤，分区条件写死 system_type='PRICING' / customer_no='_GLOBAL_'；
--   · 版本用 :versionFilter(是否当前列, 版本列, 料号键列) 宏，每 sheet 独立可切换；
--   · 每张视图必出 material_no；树页签（物料BOM）额外必出 parent_no（边键）；
--   · 全部按 = ANY(:total_material_no) 收窄（核价侧.md §页签 $view 契约）；
--   · FROM 一律 V6 表（material_bom_item / element_bom_item / unit_price + 主数据表），
--     无 mat_* / plating_plan / element_price* 等废弃表（AP-53）。
--
-- 🚩 导入 JSON 后必须手工补的 2 项（bundle 格式带不动，见文末「导入后必做」）
-- =====================================================================================


-- ─────────────────────────────────────────────────────────────────────────────────────
-- 1) 物料BOM  →  $wl_bom_view      【tabType=BOM，核价树主轴，19 字段】
--    · 驱动：material_bom_item（父 material_no → 子 component_no）
--    · parent_no=父件、material_no=子件，后端按 (parent_no, material_no) 边键挂数据
--    · 品名 = coalesce(mm.material_name, mr.name)：正式料件走主档，配方料走 material_recipe
--    · 底数单位借用 issue_unit（material_bom_item 无独立 base_unit 列，与既有核价模板一致）
--    · requiredVariables = [total_material_no]
-- ─────────────────────────────────────────────────────────────────────────────────────
select
  mbt.material_no        as parent_no,
  mbt.component_no       as material_no,
  mbt.component_no       as hf_part_no,
  mbt.bom_version        as view_version,
  mbt.production_no      as production_no,
  mbt.seq_no             as seq_no,
  coalesce(mm.material_name, mr.name) as material_name,
  mm.specification       as specification,
  mm.dimension           as dimension,
  mbt.operation_no       as operation_no,
  pm.process_name        as process_name,
  mbt.feature_mgmt       as feature_mgmt,
  mbt.composition_qty    as composition_qty,
  mbt.issue_unit         as issue_unit,
  mbt.base_qty           as base_qty,
  mbt.issue_unit         as base_unit,
  mbt.scrap_rate         as scrap_rate,
  mbt.fixed_scrap        as fixed_scrap,
  mbt.defect_rate        as defect_rate,
  mbt.calc_type          as calc_type,
  mm.unit_weight         as unit_weight
from material_bom_item mbt
left join material_master mm on mm.material_no = mbt.component_no
left join material_recipe mr on mr.code = mbt.component_no
left join process_master pm on pm.process_no = mbt.operation_no
where mbt.system_type = 'PRICING'
  and mbt.customer_no = '_GLOBAL_'
  and :versionFilter(mbt.is_current, mbt.bom_version, mbt.material_no)
  and mbt.component_no = ANY(:total_material_no)
order by mbt.seq_no


-- ─────────────────────────────────────────────────────────────────────────────────────
-- 2) 物料与元素BOM  →  $wl_ys_bom_view      【tabType=(空)，7 字段，接价格策略】
--    · 驱动：element_bom_item（一号多材质 → 逐行取，材质料号进行键，禁子查询聚合）
--    · 「元素单价」接客户价格策略：LEFT JOIN f_material_element_price（task-0729 / V369）
--        - 必 LEFT JOIN（INNER 会让无价元素整行掉行）
--        - JOIN 键 = 元素符号 component_no（Ag/Cu），不是元素编号(10001)、不是元素名(银/铜)
--        - 禁 coalesce(...,0)：无价留 NULL，由业务手填
--        - cep.material_no 必须与本视图 hf_part_no 的表达式逐字一致 →
--          本视图是「闭包形态」：coalesce(cl.root_no, ebi.material_no)，
--          子件的元素价按其所属成品料号取（价格策略是按成品料号发的）
--        - 别名固定 cep（2026-07-29 定死）；输出别名 = 字段名逐字「元素单价」，不加 `_`
--    · ⚠️ 传 :customerCode 而不是字面量 '_GLOBAL_' —— 与 dev-docs 现存 [核价模板] 目录
--      的 wl_ys_bom_view 一致。理由（实测证据，2026-08-14）：
--        ① element_price_strategy 只有 CUST-* 行，f_material_element_price('_GLOBAL_', …)
--           在本库返 0 行 → 用 '_GLOBAL_' 该列会恒为空；
--        ② BomTreeRenderService.render 已于 task-0729（2026-08-03）统一兜底
--           QuotationIdContext，核价侧 :customerCode 能解析到客户（源码注释直接点名
--           「wl_ys_bom_view 的元素单价」这个场景）。
--      核价侧.md §契约要点仍写 '_GLOBAL_'，该处已落后于 task-0729，采信代码与实测。
--    · requiredVariables = [customerCode, priceBaseDate, total_material_no]（由 dry-run 自动登记）
-- ─────────────────────────────────────────────────────────────────────────────────────
with recursive bom_closure as (
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
  ebi.seq_no             as seq_no,
  ebi.component_no       as component_no,
  ebi.content            as content,
  ebi.scrap_rate         as scrap_rate,
  cep.unit_price         as 元素单价
from element_bom_item ebi
left join lateral (
  select root_no from bom_closure where node_no = ebi.material_no
  order by (case when lvl = 0 then 1 else 0 end) asc, lvl asc, root_no asc limit 1
) cl on true
left join f_material_element_price(:customerCode, :priceBaseDate) cep
       on cep.element_code = ebi.component_no
      and cep.material_no  = coalesce(cl.root_no, ebi.material_no)
where ebi.system_type = 'PRICING'
  and ebi.is_current = true
  and ebi.material_no = ANY(:total_material_no)
order by ebi.seq_no


-- ─────────────────────────────────────────────────────────────────────────────────────
-- 3) 加工费&组装费  →  $jgf_view      【tabType=(空)，8 字段】
--    · 驱动：unit_price，price_type='SELF_PROCESS'（自制加工/组装工费）
--    · up.code = 销售料号（本 price_type 下 code 就是成品料号，与电镀 repair-0802 不同）
--    · 「加工费」= is_amount ✅ / is_subtotal ❌（模板备注「金额列（不累加）」，R7 判定：
--       该列各行相加对本页签无业务含义，累加会污染页签合计）
--    · 工序名称走 process_master 反查；⚠️ 本库 process_master 仅 2 行（Z100/Z101），
--      现有数据里的 Z002/Z008/Z053/Z490 均查不到名 → 该列多为空，属**数据缺口**不是配置错，
--      需业务侧补导工序主数据（同 BL-0045 结论）。
--    · requiredVariables = [total_material_no]
-- ─────────────────────────────────────────────────────────────────────────────────────
select
  up.code           as material_no,
  up.code           as hf_part_no,
  up.version_no     as view_version,
  up.production_no  as production_no,
  up.operation_no   as operation_no,
  pm.process_name   as process_name,
  up.pricing_price  as pricing_price,
  up.currency       as currency,
  up.unit           as unit,
  up.defect_rate    as defect_rate
from unit_price up
left join process_master pm on pm.process_no = up.operation_no
where up.system_type = 'PRICING'
  and up.price_type = 'SELF_PROCESS'
  and :versionFilter(up.is_current, up.version_no, up.code)
  and up.code = ANY(:total_material_no)


-- ─────────────────────────────────────────────────────────────────────────────────────
-- 4) 成品其他比例费用  →  $cpbl_view      【tabType=(空)，5 字段】
--    · 驱动：unit_price，price_type='FINISHED_OTHER' 且 cost_ratio is not null
--      （实测该 price_type 里混着两类行：比例费用行 cost_ratio 有值、pricing_price=0；
--        固定费用行 cost_ratio 为 NULL、金额在 pricing_price。两类还共用同一段 seq_no，
--        所以 `cost_ratio is not null` 这一条既是取数过滤，也是行键去重的前提，不能省。）
--    · 「要素」= cost_type（材料管理费 / 财务管理费 …）；「比例(%)」= cost_ratio（非金额列）
--    · requiredVariables = [total_material_no]
-- ─────────────────────────────────────────────────────────────────────────────────────
select
  up.code           as material_no,
  up.code           as hf_part_no,
  up.version_no     as view_version,
  up.production_no  as production_no,
  up.seq_no         as seq_no,
  up.cost_type      as cost_type,
  up.cost_ratio     as cost_ratio
from unit_price up
where up.system_type = 'PRICING'
  and up.price_type = 'FINISHED_OTHER'
  and up.cost_ratio is not null
  and :versionFilter(up.is_current, up.version_no, up.code)
  and up.code = ANY(:total_material_no)
order by up.seq_no


-- =====================================================================================
-- 导入后必做（bundle 格式**带不动**这两项，实测导入后回读为 NULL）
-- =====================================================================================
-- ① 「物料与元素BOM」的元素价格策略三项绑定（AGENT-配置入口 §3.6.1，缺了升版会静默不改价）：
--    组件管理 → 物料与元素BOM → 编辑 → 元素编码列=「元素代码」、元素单价列=「元素单价」、
--    货币列=留空（本视图未输出货币列）。
--    等价 API：
--      PUT /api/cpq/components/{cid}
--      {"elementCodeField":"元素代码","elementPriceField":"元素单价"}
--    自检：GET /api/cpq/components/{cid} 回读 elementCodeField/elementPriceField 非空。
--    （不补的后果：视图仍能取到实时价，但「客户价格调整策略」升版时改不到这一列 —— 不报错。）
--
-- ② 核价递归树配置（costing_bom_tree_config，usage=COSTING，全局唯一生效）：
--    本 bundle 只含页签组件，不含全局递归树 SQL。若目标库尚无「设为生效」的核价树配置，
--    物料BOM 页签会没有 spine → 整页签空。递归 SQL 原文见 核价侧.md §树主轴（可直接复制）。
--    自检：SELECT count(*) FROM costing_bom_tree_config WHERE usage='COSTING' AND is_active; -- 需 = 1
--
-- ③ 模板侧：组件配置不再自动推给已发布模板（2026-08-06 起）。挂完组件后必须
--    「新建 DRAFT 模板 → publish」或对已发布模板「createNewDraft → 改 → publish」才看得到。
--
-- =====================================================================================
-- 与库内已有目录「核价-简易」(f0b44fc1，COMP-0232~0236) 的差异（2026-08-14 用户逐条裁决）
-- =====================================================================================
-- 本 bundle 是**按 xlsx 重新推导**的一套独立组件，不是那个目录的导出。已知差异 4 处：
--   ① 范围：只出 xlsx 的 4 个页签组件。库里的「核价小计-简易」(SUBTOTAL) 与「核价excel」
--      (EXCEL) 不在 xlsx 中，故不含。→ 用户裁决：严格按 xlsx。
--   ② 库里 UI 后加的两列不含：物料BOM 的「材料费用」(INPUT_NUMBER 手填)、
--      加工费&组装费 的「小计」(FORMULA)。要沿用得导入后在组件管理里补加。
--   ③ 物料与元素BOM 的 tabType：本 bundle = (空)（照 xlsx 的 B1）；库里 COMP-0233 已改为
--      「材质元素」+ 料号列=料号。→ 用户裁决：以 xlsx 的 (空) 为准。
--      影响：(空) 不参与页签类型判定 / 树类型匹配；若后续要它参与，改成 材质元素 时
--      必须同时配料号列或名称列（两者皆空会 400）。
--   ④ SQL：本 bundle 的 $wl_ys_bom_view / $jgf_view 比库里现存版本多一句
--      `= ANY(:total_material_no)` 收窄（$cpbl_view 是新建，库里没有对应）。
--      → 用户裁决：保留。
--   ⑤ 新增：「成品其他比例费用」($cpbl_view) 是本次 xlsx 新加的页签，库里原来没有。
--
-- ⚠️ 行键口径（repair-0814 结论，勿再踩）：row_key_fields 存的是**字段名**（中文），
--    不是 driver 列名。组件管理里「行键」复选框读写的是 resolvedColumn，勾一下就会把
--    英文列名追加进去、且中文项在 UI 上显示为未勾选 → 集合虚胖会让页签连表判成不可比。
--    本 bundle 的三组行键已按字段名口径：
--      物料BOM ["销售料号","料号"] / 物料与元素BOM ["销售料号","料号","元素代码"]
--      / 加工费&组装费 ["销售料号","工序编号"] / 成品其他比例费用 ["销售料号","项次","要素"]
--    导入后**不要**再去点那几个复选框（BL-0170 未修）。
--
-- =====================================================================================
-- 生成期自检记录（2026-08-14，库 cpq_db_0724）
-- =====================================================================================
-- · 4 张视图 POST /components/{cid}/sql-views 均 200（dry-run 通过）；
-- · 真数据实跑（total_material_no = 成品 S-3120014539 树闭包 16 个料号）：
--     $wl_bom_view 24 行 / $wl_ys_bom_view 14 行 / $jgf_view 4 行 / $cpbl_view 2 行，均非空；
--     元素单价出数：Cu=171.368、Ag=4520.0000（CUST-0001），Ni 无价 → NULL（未补 0，未掉行）✅
-- · JSON bundle 导入回归：preview checksumValid=true / canCommit=true / blockers=[] / warnings=[]，
--   commit createdCount=4 + sqlViewsCreated=4；回读 tabType=BOM 的物料BOM 自动 bomRecursiveExpand=true；
--   partNoField/partNameField/sortField/rowKeyFields 全部还原；验收用的临时目录与组件已删除，库无残留。
-- · bindingReport: unboundCount=0（无未绑定公式）。
