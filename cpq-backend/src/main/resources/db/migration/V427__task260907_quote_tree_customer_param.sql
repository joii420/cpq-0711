-- task-260907 · B-7a —— 报价树递归按【本单客户】展开（只改 usage='QUOTE' 那条）
--
-- 服务的 AC：AC-1
-- 用户 2026-09-07 裁决：「修，只改 usage='QUOTE' 那条」。
--
-- ============ 病灶 ============
-- QUOTE 递归模板里，客户是这样"推"出来的：
--   (SELECT bc.customer_no FROM material_bom_item bc
--     WHERE bc.material_no=p AND bc.system_type='QUOTE' AND bc.is_current
--     ORDER BY bc.customer_no LIMIT 1) AS _cust
-- 即【取 customer_no 最小的那家】，与本张报价单是谁的单毫无关系；
-- 递归段再用 ch.customer_no = b._cust 沿着这个客户往下走。
--
-- 子代理已用自造夹具实证：同一料号挂 A/B 两个客户、子件不同时，
-- A 的报价单与 B 的报价单拿到【完全相同的一次展开】，都是 customer_no 最小那家的子树。
-- ⇒ 没有串到别人的行（谓词是有的），但也没有按本单客户选（选的是错的那家）。
-- ⇒ AC-1 的三条断言在这个形状下【任何实现都不可能成立】。
--
-- ============ 修法 ============
-- 把 _cust 换成绑定参数 :customerCode（由 BomTreeRenderService 从本单
-- quotation.customer_id → customer.code 解析后按出现顺序绑定，见该类 TREE_PARAM）。
--
-- 🚦 为什么外面还套一层 COALESCE，而不是直接换成 :customerCode（本迁移最关键的一条）：
--   递归模板还有一条【没有报价单上下文】的调用路径 ——
--     BuilderService:447  new QuotationLineItem(); lite.productPartNoSnapshot = partNo;
--                         collectTotalMaterialNoUnion(List.of(lite), "QUOTE")
--   这个 lite 行【不带 quotationId】（取数配置器的预览闭包，task-260819 AC-26/AC-27/AC-28）。
--   直接换成 :customerCode ⇒ 该路径绑到 NULL ⇒ ch.customer_no = NULL 恒假
--   ⇒ 预览闭包塌成"只有根料号自己"，子件行整片消失。
--   那是一个【静默】回归：预览页面照样出图，只是永远看不到子件，与"客户没数据"长得一模一样。
--   ⇒ COALESCE 让「没有客户上下文」逐位退回改造前的行为，只在【有本单客户】时才按本单客户走。
--   ⚠️ 这不是"保险起见多写一层"：两条路径的语义确实不同，一条有客户、一条按定义就没有。
--
-- 🚫 usage='COSTING' 那条一个字不动：它的客户语义是 customer_no IN (客户,'_GLOBAL_')、
--    带 :versionFilter 宏、锚点是生产料号，与报价侧不同轴。动它要另验核价树渲染无回归，本期不碰。
--
-- ============ 配套改动（缺任一条本迁移都会变成故障） ============
--   ② BomTreeRenderService.TREE_PARAM  加 customerCode，并按【出现顺序】绑成标量 varchar
--      （🚫 不能落进 seedArr/partArr/verArr 三选一那条数组分支，会位置错位静默绑错）
--   ③ 客户码来源：renderInternal 已有的 ctxCustomerId（整单只查一次）→ customer.code，
--      进程级缓存，口径与 SqlViewExecutor.customerCodeCache 同款
--   ④ CostingTreeSqlValidator 探针加 :customerCode → NULL::varchar 的桩，
--      否则【保存报价树配置】当场校验失败（PG 里 `:` 不是占位符语法，直接语法错）

UPDATE costing_bom_tree_config
SET sql_template = $tpl$WITH RECURSIVE bom AS (
  SELECT p::text AS root_no, p::text AS material_no,
    (SELECT bv.bom_version::text FROM material_bom_item bv WHERE bv.material_no=p AND bv.system_type='QUOTE' AND bv.is_current LIMIT 1) AS bom_version,
    NULL::text AS parent_no, p::text AS node_path,
    COALESCE(:customerCode::varchar,
      (SELECT bc.customer_no FROM material_bom_item bc WHERE bc.material_no=p AND bc.system_type='QUOTE' AND bc.is_current ORDER BY bc.customer_no LIMIT 1)) AS _cust
  FROM unnest(:production_part_nos) AS p
  UNION ALL
  SELECT b.root_no, ch.component_no::text,
    (SELECT bv.bom_version::text FROM material_bom_item bv WHERE bv.material_no=ch.component_no AND bv.system_type='QUOTE' AND bv.is_current LIMIT 1),
    ch.material_no::text, (b.node_path||'/'||ch.component_no)::text, b._cust
  FROM material_bom_item ch JOIN bom b ON ch.material_no=b.material_no AND ch.customer_no=b._cust
  WHERE ch.system_type='QUOTE' AND ch.is_current AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path FROM bom$tpl$,
    updated_at = now()
WHERE usage = 'QUOTE' AND is_active = true;

-- 落地后应成立的不变量（供人工复核）：
--   ① usage='QUOTE' AND is_active 的行数 = 1，其 sql_template 含 ':customerCode'
--   ② usage='COSTING' 那条 sql_template 逐字未变（本迁移的 WHERE 不命中它）
--   ③ 同料号挂两客户、子件不同时：A 单只出 A 的子件、B 单只出 B 的、互不出现（AC-1）
