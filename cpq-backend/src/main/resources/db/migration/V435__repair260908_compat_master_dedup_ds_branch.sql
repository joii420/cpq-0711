-- =====================================================================
-- repair-260908 · C-1 —— v_compat_material_master 的 ds 分支跨客户重号去重（方案丁）
--
-- 服务的 AC：C-1（「页签/选择框出现重复行」的根因之一）
--
-- ============ 病灶 ============
-- V425 把报价侧 ds_quote_* 全表加了 customer_no，并把 ds_quote_material 的业务唯一索引
-- 从 uq_ds_quote_material(material_no) 扩成 (customer_no, material_no)。
-- ⇒ 同一销售料号在两个客户下合法地存在两行。
--
-- 而本视图是【兼容视图】：它把 ds_quote_material 映射成老 V6 material_master 的形状，
-- 而 material_master 【根本没有客户列】（2026-09-09 实查 information_schema：0 行）。
-- ⇒ 复合轴 (customer_no, material_no) 在视图层被压回单轴 material_no，两个客户的行
--   在视图里合不掉，而且 id = md5('dqm:' || material_no) 让它们连主键都撞在一起。
--
-- 实测（2026-09-09，cpq_db_0724）：
--   视图 2731 行 / 2715 个去重料号 / 2715 个去重 id ⇒ 凭空多出 16 行
--   重号料号 = S0001..S0014 + T260907-M1 + T260907-M2（各 2 行，CUST-0001 / CUST-0004）
--   下游 v_composite_child_materials 2745 行（含 16 行是扇出出来的）
--   外购件选择框 GET /quotations/configure/outsourced-parts 的 total 被灌水，
--   且重复项会骑在分页边界上、翻页看还是两次
--
-- ⚠️ 上面这组数字比 2026-09-09 早些时候的一次实测（6419/6413，6 个重号）小得多 ——
--    期间共享库有大批导入改写（material_master 1903→48，ds_quote_material 4578→2725）。
--    ⇒ 🚫 不要把任何绝对行数当验收判据，它是移动靶。判据必须是【不变量】：
--       count(*) = count(DISTINCT material_no) = count(DISTINCT id)。
--
-- ============ 用户裁决（2026-09-09，闸门 A0）============
-- 问：「一个销售料号在两个客户下，物理属性（品名/规格/尺寸/单重/类型）按业务定义应该相同吗？」
-- 答：【应该相同】。
-- ⇒ 本迁移的 DISTINCT ON 是在【还原本来的语义】，不是「静默挑一个客户」的打补丁。
--   customer_no 在 ds_quote_material 上是【归属/作用域轴】，不是【属性轴】。
--
-- 🚨 但「应该相同」是业务定义，不是数据库约束 —— uq_ds_quote_material(customer_no, material_no)
--    结构上允许两个客户的属性分歧。一旦分歧，本视图就会静默挑一个、不报错、不留痕。
--    ⇒ 配套守卫断言见 CompatMasterCrossCustomerGuardTest，它把这条风险从【静默】变成【报红】。
--    🚫 不要因为「今天恰好一样」就删掉那个测试 —— V429 抬头段落是同一条纪律。
--
-- ============ 为什么只改 ds 分支（方案丁），不动 V6 分支 ============
-- material_master.material_no 本就全局唯一（2026-09-09 实查 48 行 / 48 个去重料号），
-- V6 分支【零重复贡献】。对它加 DISTINCT ON 是无谓风险：
--   它会把 V6 与 ds 两侧压进同一个去重窗口，一旦将来两侧出现同料号
--   （今天被下面的 NOT EXISTS 挡着），胜者就由 ORDER BY 决定，语义反而更难说清。
--
-- ============ 🚫 三条不许动（动了就不是方案丁）============
-- 1) V6 分支逐字照抄 V415，一个字符都不改。
-- 2) id 合成算法保持 md5('dqm:' || material_no) —— 🚫 不加 customer_no。
--    依据：2026-09-09 全库扫描证明该 id 【没有被持久化到任何地方】——
--      · 对 public schema 下每一个 uuid 类型列跑
--        `WHERE col IN (SELECT md5('dqm:'||material_no)::uuid FROM ds_quote_material)` → 0 命中
--      · 8 个 Java 消费点的投影列里都没有 id
--      · 3 个依赖视图 pg_views.definition ~ 'mm\.id' 全为 f
--    ⇒ 改它没有收益，只有风险。
-- 3) 列名 / 列序 / 类型（含 typmod）逐字不变 —— 这是 CREATE OR REPLACE 能成立的前提，
--    也是 v_composite_child_materials / _processes / _elements 三个依赖视图
--    【零改动】的前提。加列会让 REPLACE 失败、逼出 DROP ... CASCADE（CLAUDE.md §3.2 红线）。
--
-- ============ 🚦 为什么不是「视图加 customer_no 列，由消费方过滤」（方案甲）============
-- 两条硬事实否掉了它，记在这里免得下一个人重走一遍：
--   ① material_master 没有客户列 ⇒ V6 分支的 customer_no 只能填 NULL
--      ⇒ 任何 `WHERE customer_no = :cn` 的消费方会【静默丢掉全部 V6 存量】。
--   ② 最大的消费方拿不到客户上下文：ConfigureSearchResource#outsourcedParts 的签名是
--      (keyword, page, size) —— 没有 customerNo、没有 quotationId，前端也没传。
--      「由消费方自己按客户过滤」在那里【物理上办不到】，除非先改 REST 契约 + 前端 + api.md。
--
-- ============ 消费方（2026-09-09 全工程扫，全部零改动）============
--   PG 视图 3：v_composite_child_materials / _processes / _elements（列不变 ⇒ 不受影响）
--   Java   8：ConfigureProductService :279 :372 :749 :1678 :1685 :1722
--             ExistingProductService  :140 :157
--   component_sql_view：0 段引用（连裸表名 material_master 也是 0）
--   前端：0 处引用
--   bnf_table_meta：1 行自动同步的取数选择器元数据，component 表零引用（不是消费方）
-- =====================================================================

CREATE OR REPLACE VIEW v_compat_material_master AS
SELECT
    v.id,
    v.material_no,
    v.material_name,
    v.specification,
    v.dimension,
    v.old_material_no,
    v.material_type,
    v.usage_property,
    v.unit_weight,
    v.standard_unit,
    v.created_at,
    v.updated_at,
    v.created_by,
    v.updated_by,
    v.material_recipe_id,
    v.config_fingerprint,
    v.production_no,
    v.pending_quotation_id
FROM material_master v
UNION ALL
SELECT
    (md5('dqm:' || m.material_no))::uuid AS id,
    (m.material_no)::character varying(20) AS material_no,
    (m.material_name)::character varying(100) AS material_name,
    (m.specification)::character varying(100) AS specification,
    (m.dimension)::character varying(100) AS dimension,
    (m.old_material_no)::character varying(50) AS old_material_no,
    (m.material_type)::character varying(50) AS material_type,
    NULL::character varying(50)        AS usage_property,
    (m.unit_weight)::numeric(24,12) AS unit_weight,
    NULL::character varying(20)        AS standard_unit,
    (m.created_at)::timestamp(6) with time zone AS created_at,
    (COALESCE(m.updated_at, m.created_at))::timestamp(6) with time zone AS updated_at,
    NULL::uuid                         AS created_by,
    NULL::uuid                         AS updated_by,
    NULL::uuid                         AS material_recipe_id,
    NULL::character varying(80)        AS config_fingerprint,
    (m.production_no)::character varying(32) AS production_no,
    NULL::uuid                         AS pending_quotation_id
-- 🔑 本迁移【唯一的改动】就是下面这个子查询（原本是裸 `FROM ds_quote_material m`）。
--    DISTINCT ON (material_no) 把复合轴 (customer_no, material_no) 收敛回本视图承诺的单轴。
--    ORDER BY 的 customer_no 只作【确定性 tie-break】—— 让胜者可预测（恒取字典序最小的客户），
--    🚫 它不表达「这个客户更权威」。属性按业务定义跨客户相同，所以挑谁都一样；
--       挑不一样的那天，是守卫断言该报红，不是这里该改。
--    🚫 子查询里【必须显式列名】，不许写 `SELECT *`：ds_quote_material 后续加列时
--       `*` 会把新列一起带进来，而外层 UNION 的列数是固定的 —— 那是「加个列就静默炸」。
FROM (
    SELECT DISTINCT ON (material_no)
           material_no,
           material_name,
           specification,
           dimension,
           old_material_no,
           material_type,
           unit_weight,
           created_at,
           updated_at,
           production_no,
           customer_no          -- 仅供上面的 ORDER BY 做 tie-break，不进外层投影
      FROM ds_quote_material
     ORDER BY material_no, customer_no
) m
WHERE NOT EXISTS (
    SELECT 1 FROM material_master x WHERE x.material_no = m.material_no
);

COMMENT ON VIEW v_compat_material_master IS
  'task-260903 B-1 + repair-260908 C-1：material_master 存量 UNION ALL ds_quote_material 投影（存量优先反连接）。'
  '列名/列序/类型逐字对齐 material_master。'
  'ds 分支按 DISTINCT ON (material_no) 收敛跨客户重号 —— 本视图是 V6 兼容形状，没有客户维度，'
  '而 V425 后 ds_quote_material 的轴是 (customer_no, material_no)。'
  '不变量：count(*) = count(DISTINCT material_no) = count(DISTINCT id)。';
