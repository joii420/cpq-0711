-- task-260911 · B-18 —— 取价函数补 _record 分支（服务 AC-20 / 需求文档 R-10 / api.md §4）
--
-- ═══ 为什么必须改这个函数（不改的后果是「静默整列 NULL」）═══
-- A 落地后，带版本表的报价数据只落 ds_quote_*_record、不落主表。而
-- f_material_element_price 的 candidate_materials CTE 是<自己读表>的 —— 文本改写（DsRecordRewriter）
-- 够不到编译在库里的函数体。同一次查询里同一份 BOM 被读两遍：外层（已改写）看得见本单 _record，
-- 函数体里那遍只读主表 ⇒ 新料号在候选名单里整体缺席 ⇒ realtime 分支产不出行
-- ⇒ 元素单价整列 NULL，往下所有用单价的公式全塌。
-- 这正是 repair-260830 的原症状，区别是这次<按构造必然发生>而不是偶发。
-- 实测 11 段 ACTIVE 视图引用本函数，且这 11 段全部同时引用 ds 表。
--
-- ═══ 🚨 只改函数体不够，必须与改写器的「补参」配对 ═══
-- 实测（2026-09-11，cpq_db_0724）：11 段视图 100% 写的是<两参>调用
-- f_material_element_price(:customerCode, :priceBaseDate)，零段写三参。
-- 两参重载是薄壳：SELECT * FROM f_material_element_price(p1, p2, NULL::uuid)
-- ⇒ 若只改本函数体、不让改写器把调用补成三参，运行时传进来的永远是 NULL::uuid，
--   下面新增的两个分支<永远命中不了>，而代码里看起来「已经做了」。
-- 配对实现见 DsRecordRewriter#pendingAwareFunctionEdits（补第三参 :dsq），
-- 守卫测试见 DsRecordRewriterTest#pending_aware_function_gets_third_arg_and_is_idempotent。
--
-- ═══ 兼容性 ═══
-- · 签名与返回列逐字不变（api.md §4.1），调用方零改动；
-- · p_pending_quotation_id 为 NULL 时新分支 `quotation_id = NULL` 恒 false ⇒ 零行
--   ⇒ 核价侧 / 无报价单上下文 / 存量单的行为与本迁移前<逐字节一致>；
-- · 两参重载 f_material_element_price(text, date) 不动 —— 它本来就是委派给三参版，
--   自动继承本次改动。
--
-- ⚠️ 本迁移只 CREATE OR REPLACE 一个函数，不动任何表结构、不删任何数据。

CREATE OR REPLACE FUNCTION public.f_material_element_price(
    p_customer_no text,
    p_base_date date,
    p_pending_quotation_id uuid)
 RETURNS TABLE(material_no character varying, element_code character varying, unit_price numeric, currency character varying, price_unit character varying)
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
    -- task-260907 B-8：ds_quote_* 体系（报价侧新表）主表半边 —— 已转正的数据。
    UNION
    SELECT material_no FROM ds_quote_material_bom
     WHERE customer_no = p_customer_no
    UNION
    SELECT material_no FROM ds_quote_element_bom
     WHERE customer_no = p_customer_no
    -- ═══ task-260911 · B-18 新增：ds_quote_* 的 _record 半边 —— 本单尚未转正的数据 ═══
    -- 与上面主表两行合起来才是完整候选集，缺这一半 = 新料号元素单价整列 NULL。
    -- 🚫 刻意不再叠加 customer_no 谓词：一张报价单只属于一个客户，quotation_id 已是最精确的
    --    收窄；多叠一个 customer_no 不会多排除任何行，只会在「两边客户编码归一化口径万一不同」
    --    时把本单数据<静默>丢掉 —— 那正是本迁移要消灭的那类故障。
    UNION
    SELECT material_no FROM ds_quote_material_bom_record
     WHERE quotation_id = p_pending_quotation_id
    UNION
    SELECT material_no FROM ds_quote_element_bom_record
     WHERE quotation_id = p_pending_quotation_id
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

COMMENT ON FUNCTION public.f_material_element_price(text, date, uuid) IS
 'task-260911 B-18：候选名单 = 版本指针 ∪ V6(is_current 或本单 pending) ∪ ds 主表 ∪ ds _record(本单)。'
 '第三参为 NULL 时新增的 _record 两支恒零行，行为与 task-260907 版逐字节一致。';
