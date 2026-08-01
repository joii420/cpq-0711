-- =============================================================================
-- task-0729 · E14-6：核价「物料与元素BOM」组件补元素单价字段 + 视图接客户取价函数
-- =============================================================================
-- 依据：需求说明.md §11.8.3（核价侧元素材料成本的落点）/ §11.17.1 E14-6 / P8
--
-- 【为什么必须是迁移脚本而不是 UI 手配】
--   核价 COMP-0049「物料与元素BOM」当前是空壳（10 个字段无任何价格列），
--   而核价小计公式 COMP-0082 恰恰引用它的 __amount_total__（Σ金额列）→ 小计恒 0。
--   E12 定「两侧同一套客户元素价」，核价侧没有价格字段就没有落点，
--   验收 #21（核价侧联动）/ #64（两侧同一套价）根本测不了。
--   只在开发库 UI 手配 → 上线到生产必然丢失（教训：UI 建的配置从未进迁移）。
--
-- 【两条业务口径已由业务方 2026-08-01 拍板】
--   ① 严格对称报价侧：元素单价 is_amount=true，不加成本计算列
--      —— 报价侧 COMP-0021 实测就是「9 字段无成本列 + 单价自己当金额列 + 组件公式为空」，
--         两侧同口径，E13 的成本差额才是真实差异而非口径差。
--   ② 字段形态 INPUT_NUMBER + default_source.path（与报价侧对称）
--      —— 本期单价列只读机制 + 版本徽标走 ComponentCell 的 INPUT_* 分支；
--         S3 写回 snapshot_rows 也按 INPUT_NUMBER + basicDataValues 通道设计。
--         用 BASIC_DATA 这两个机制都不适用。
--
-- 【本迁移不做的两件事（有意留给后端）】
--   1. 三个角色字段（element_code_field / element_price_field / element_currency_field）
--      —— 那三列由 backtask B1 新增，本迁移不依赖它。B7 迁移期统一预填，
--         🔒 COMP-0049 的期望值：元素代码 / 元素单价 / (空)
--   2. 切到 f_material_element_price
--      —— 本迁移接的是**现有** f_customer_element_price（与报价侧现状逐字对称），
--         backtask B2 统一把两侧 8 个视图（报价侧 7 + 核价侧本视图）一起切新函数。
--         这样本迁移当下即可跑通验证，不阻塞、不依赖未来产物。
--
-- 【可逆】原 sql_template 完整保留在文末注释，回滚直接反向 UPDATE。
-- 【幂等】两处 UPDATE 均带存在性判断，可安全重跑。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1) 组件字段：追加「元素单价」
--    与报价侧 COMP-0021 的同名字段逐字对称（除视图名外）：
--    INPUT_NUMBER / is_amount=true / is_subtotal=false / default_source.type=BASIC_DATA
--    ⚠️ 核价侧字段 JSON 无 sort_order 键（该组件既有 10 字段皆无），故不加，追加到数组末尾。
-- ---------------------------------------------------------------------------
UPDATE component
   SET fields = fields || jsonb_build_array(jsonb_build_object(
           'name',           '元素单价',
           'field_type',     'INPUT_NUMBER',
           'is_amount',      true,
           'is_subtotal',    false,
           'content',        '',
           'notes',          '接客户价格策略 f_customer_element_price(无价留空手填)；task-0729 E12 两侧同一套客户价',
           'default_source', jsonb_build_object('type', 'BASIC_DATA', 'path', '$wl_ys_bom_view.元素单价')
       )),
       updated_at = now()
 WHERE code = 'COMP-0049'
   AND NOT EXISTS (
       SELECT 1 FROM jsonb_array_elements(fields) f WHERE f->>'name' = '元素单价'
   );

-- ---------------------------------------------------------------------------
-- 2) SQL 视图：LEFT JOIN 取价函数 + 输出「元素单价」列
--
--    🔒 JOIN 键铁律（§11.15.4.2）：JOIN 键必须与该视图 hf_part_no 输出表达式逐字一致。
--       本视图 hf_part_no = ebi.material_no（**平铺形态**，非 BOM 闭包）。
--       但现阶段接的 f_customer_element_price **不返回 material_no**（签名只有
--       element_code/unit_price/currency/price_unit），故当前只能按 element_code JOIN
--       —— 与报价侧现状完全一致。B2 切到 f_material_element_price 时，
--       本视图应加：AND cep.material_no = ebi.material_no（平铺形态的正确写法）。
--
--    🔒 别名固定 cep（D7 规范）。
--    🔒 价格策略列不带下划线前缀（元素单价），driver 列带（_xxx）—— 两类前缀规则相反，
--       见 §11.15.3.4 纪律 2。本视图既有 driver 列用的是英文原名（component_no 等），
--       故新增列直接用中文字段名「元素单价」以对齐 fields 的 default_source.path。
-- ---------------------------------------------------------------------------
UPDATE component_sql_view csv
   SET sql_template = 'select
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
left join f_customer_element_price(:customerCode, :priceBaseDate) cep
       on cep.element_code = ebi.component_no
where ebi.system_type = ''PRICING''
  and ebi.is_current = true
order by ebi.seq_no'
  FROM component c
 WHERE c.id = csv.component_id
   AND c.code = 'COMP-0049'
   AND csv.sql_template NOT LIKE '%f_customer_element_price%';

-- =============================================================================
-- 【回滚参考】原 sql_template（本迁移执行前的内容，逐字保留）：
--
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
--   ebi.scrap_rate         as scrap_rate
-- from element_bom_item ebi
-- left join material_master mi on mi.material_no = ebi.material_part_no
-- left join material_recipe mr on mr.code = ebi.material_part_no
-- where ebi.system_type = 'PRICING'
--   and ebi.is_current = true
-- order by ebi.seq_no
--
-- 回滚字段：UPDATE component SET fields = (SELECT jsonb_agg(f) FROM jsonb_array_elements(fields) f
--                                          WHERE f->>'name' <> '元素单价') WHERE code='COMP-0049';
--
-- ⚠️ 改视图后必须重启 Quarkus（ImplicitJoinRewriter.tableColumnsCache /
--    SqlViewExecutor.customerCodeCache 是进程级缓存，CLAUDE.md「视图重建后必须重启」）。
-- =============================================================================
