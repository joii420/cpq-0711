-- task-260907 · B-8 —— 元素价格策略闭环：给 f_material_element_price 的候选料号集补 ds_ 支
--
-- 服务的 AC：AC-8（用户 2026-09-07 裁决「加上，让元素价格策略闭环」）
--
-- ============ 🚦 本迁移【只做一件事】，语义图一条语句都没有 ============
-- 早期草案里本文件还带着「撤销我们自己那条 V424（把 PRICE 边改指 f_customer_element_price）」的
-- 四段语句。2026-09-07 改号定案时那条 V424 被【整个删除】（git rm，从未在共享库执行过），
-- ⇒ 无物可撤，四段随之砍掉。
--
-- 🔑 砍掉的依据是【实查共享库现状】，不是推理（复核于 2026-09-07）：
--     semantic_edge 5b1bfc30-…  : ELEMENT_BOM --PRICE--> FUNC_ELEMENT_PRICE (ACTIVE)
--     semantic_edge_key         : seq0 element_code | seq1 material_no      ← 2 个都在，没被删过
--     semantic_node             : 只有 FUNC_ELEMENT_PRICE，无 FUNC_CUSTOMER_ELEMENT_PRICE
--     semantic_tab_view_node    : 38921742-… → FUNC_ELEMENT_PRICE / AUX
--   ⇒ 那条边从未被改动过，动过它的只有我们自己那条已删除的 V424。
-- ⇒ 本任务【不再触碰任何 semantic_*】，语义图完全归并发会话处置。
--
-- ============ 🚦 为什么修的是候选集，不是换 PRICE 边（用户裁决，🚫 不要走回头路）============
-- 换边（把 PRICE 边改指 f_customer_element_price）是【真回归】，不是等价替换：
--   f_material_element_price(customer, date) 是 UNION 的两支
--     versioned : material_price_version_ref → element_price_version_item   ← 按【料号】钉的版本价
--     realtime  : candidate_materials CROSS JOIN f_customer_element_price   ← 客户级实时价，纯扇出
--   ⇒ 改指 f_customer_element_price 等于把 versioned 整支丢掉。
--
--   实测（逐客户扫，而不是抽一个客户就下全称结论）：
--     SELECT c.code, (SELECT count(*) FROM (
--         SELECT element_code FROM f_material_element_price(c.code, CURRENT_DATE)
--         GROUP BY element_code HAVING count(DISTINCT unit_price) > 1) z)
--     FROM customer c;
--     → CUST-0002 = 1 · CUST-0729-QA = 1 · 其余全 0
--   CUST-0002 有 1 个料号把 Ag 钉在 3500，客户级实时价是 3000
--   ⇒ 换边会让它【静默从 3500 掉到 3000】，且引用 FUNC_ELEMENT_PRICE 的 21 个存量视图全走这条路。
--
-- ⇒ 真正的缺口不在「哪个函数」，在【候选料号集】：candidate_materials 只读 V6 的
--   material_bom_item / element_bom_item，ds_quote_* 独有的料号根本进不了候选集，
--   realtime 那支的 CROSS JOIN 就带不出它们（实测 ds_ 独有料号 = 8 个）。

-- ============================================================================
-- 5. 真正的修复：给 f_material_element_price(text,date,uuid) 的候选料号集补 ds_ 两支
--
--    🚫 不改签名、不改返回列（21 个存量视图按现有列名取数）。
--    🚫 不动 f_customer_element_price（它是 realtime 支调用的下游）。
--    ✅ 纯加法：只往候选集里加料号，已有料号的返回值逐行不变
--       ⇒ 除了 ds_ 侧确实新增料号的那个客户，其余客户逐行 md5 不变（A/B 证据见回报）。
--
--    ⚠️ 关于「双半边」语义（repair-260830）：V6 两支写的是
--         is_current = true OR pending_quotation_id = p_pending_quotation_id
--       ——一半管正式行、一半管本单 pending 影子行。
--       ds_quote_material_bom / ds_quote_element_bom 【没有】is_current，也【没有】
--       pending_quotation_id 列（2026-09-07 实查 21 列，两者均不存在）：
--         · 该模型的「当前版本」= 主表本身（历史版本整组归档到 *_history），故不需要 is_current 谓词；
--         · 该模型【不存在 pending 影子行】——QuotePendingRewriter.WHITELIST_TABLES 也没有登记这两张表，
--           口径是一致的。
--       ⇒ 新加的两支只对应【is_current 那半边（正式行）】，pending 那半边在 ds_ 侧【没有对应物】。
--       后果：若将来给 ds_ 表引入 pending 影子行（B-7 换表 / 核价回填 S-4 都可能触发），
--       本函数必须同步补 pending 半边，否则本单新料号在提交前取不到元素价。
--       🚫 这里不假装对齐、不写一个恒假的 pending 谓词充数。
--
--    ⚠️ 关于 '_GLOBAL_'：V6 两支带它是因为核价侧 BOM 主档全局共享；
--       ds_quote_* 是报价侧、customer_no 恒为真实客户码（NOT NULL，来自导入时选的客户），
--       不存在 '_GLOBAL_' 行。这里写 = p_customer_no 而不是 IN (p_customer_no,'_GLOBAL_')：
--       后者一旦有人误写一行 '_GLOBAL_'，会泄漏给【每一个】客户，那正是本任务要消灭的串客户形态。
-- ============================================================================
CREATE OR REPLACE FUNCTION public.f_material_element_price(
    p_customer_no text, p_base_date date, p_pending_quotation_id uuid)
RETURNS TABLE(material_no character varying, element_code character varying,
              unit_price numeric, currency character varying, price_unit character varying)
LANGUAGE sql
STABLE
AS $function$
WITH pointers AS (              -- 该客户已升版的料号 -> 当前指针指向的版本
    SELECT material_no, version_id
      FROM material_price_version_ref
     WHERE customer_no = p_customer_no
),
versioned AS (                  -- 有指针的料号：直接读版本明细（权威快照，不重算）。
                                 -- current_price IS NULL 的行（该元素本期彻底无价且从无历史价）
                                 -- 不出现在这里，下面 realtime 分支会按元素级兜底补上（§11.3.2.1 第三行）。
    SELECT p.material_no, i.element_code, i.current_price AS unit_price,
           i.currency, i.price_unit
      FROM pointers p
      JOIN element_price_version_item i ON i.version_id = p.version_id
     WHERE i.current_price IS NOT NULL
),
candidate_materials AS (        -- 候选 material_no 全集：覆盖真实客户（报价侧 BOM/元素挂真实
                                 -- customer_no）与 '_GLOBAL_'（核价侧 BOM/元素主档全局共享，
                                 -- 客户维度只体现在元素价格策略上，不体现在 BOM 结构上）。
                                 -- repair-260830：is_current 半边管正式行（核价侧 / 老客户 / 已转正），
                                 -- pending_quotation_id 半边管本单 pending 影子行，两个分支各管一半、
                                 -- 缺一不可，与 QuotePendingRewriter 的表改写口径对齐。
    SELECT material_no FROM material_bom_item
     WHERE customer_no IN (p_customer_no, '_GLOBAL_')
       AND (is_current = true OR pending_quotation_id = p_pending_quotation_id)
    UNION
    SELECT material_no FROM element_bom_item
     WHERE customer_no IN (p_customer_no, '_GLOBAL_')
       AND (is_current = true OR pending_quotation_id = p_pending_quotation_id)
    -- task-260907 B-8：ds_quote_* 体系（报价侧新表）。只对应 is_current 那半边，
    -- 见本迁移 §5 头部说明；两张表的 customer_no 由 V423 加列并 NOT NULL。
    UNION
    SELECT material_no FROM ds_quote_material_bom
     WHERE customer_no = p_customer_no
    UNION
    SELECT material_no FROM ds_quote_element_bom
     WHERE customer_no = p_customer_no
),
realtime AS (                   -- fallback：候选料号 × 全部实时算价元素（不改 f_customer_element_price 签名）
    SELECT cm.material_no, f.element_code, f.unit_price, f.currency, f.price_unit
      FROM candidate_materials cm
      CROSS JOIN f_customer_element_price(p_customer_no, p_base_date) f
)
SELECT v.material_no, v.element_code, v.unit_price, v.currency, v.price_unit
  FROM versioned v
UNION ALL
SELECT r.material_no, r.element_code, r.unit_price, r.currency, r.price_unit
  FROM realtime r
  LEFT JOIN versioned v2
    ON v2.material_no = r.material_no AND v2.element_code = r.element_code
 WHERE v2.material_no IS NULL;   -- 该 (料号,元素) 已由版本明细给出的不重复给实时价
$function$;

-- ============================================================================
-- 落地后应成立的不变量（供人工复核；🚫 不在此处断言，迁移不做业务校验）
--   ① 值中性：改动前后逐客户跑 EXCEPT ALL 双向差集，「只在旧」必须全为 0（纯加法）
--      🚫 不要用 md5(string_agg(..., ',' ORDER BY 1,2)) —— 聚合里的 1,2 是常量不是列序号，
--         排序键恒定 ⇒ 输出序 = 执行计划顺序，加 UNION 支会造成【行数不变而 md5 变】的假阳性。
--   ② 闭环：ds_ 独有料号至少 1 个能取到非空单价
--   ③ 语义图零影响：QUOTE/材质元素 的 groupKind='PRICE' 组数 = 1、
--      PRICE 边指向 FUNC_ELEMENT_PRICE、连接键 2 条 —— 本迁移不碰 semantic_*，应逐条不变
-- ============================================================================
