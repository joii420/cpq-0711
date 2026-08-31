-- =====================================================================================
-- repair-260830 元素单价 pending 不可见 · B-1
--
-- 根因（问题说明 §4.1）：f_material_element_price 是数据库函数，QuotePendingRewriter 的
--   文本改写够不到它内部；而该函数内部又自己读了一遍 material_bom_item / element_bom_item
--   并写死 is_current = true —— 于是同一次查询里同一张表被读了两遍，
--   外层那遍看得见 pending 影子行，函数里那遍看不见 ⇒ 候选料号集为空 ⇒ 元素单价 NULL。
--
-- 修法（方案乙，2026-08-30 用户 A0 裁决）：
--   ① 新增三参重载 (text, date, uuid)：candidate_materials 的两处过滤放开为
--      (is_current = true OR pending_quotation_id = p_pending_quotation_id)
--   ② 旧两参版 CREATE OR REPLACE 为委托调用（签名/返回列逐字不变）
--
-- 🔑 用重载而非改签名：无需 DROP FUNCTION，完整避开 CLAUDE.md §3.2「契约销毁」红线。
--    老调用方（8 个用 f_customer_element_price 的组件、PriceReconciler、
--    PriceAdjustVersionGenerationService）一行不用改。
--
-- 🔑 p_pending_quotation_id 为 NULL 时 `pending_quotation_id = NULL` 恒为 NULL，
--    在 WHERE 里等价 false ⇒ 函数自动退化为纯 is_current。
--    这正是核价侧（p_pq 恒 NULL）与冻结态需要的行为 —— E-4/E-5/E-6 零回归的技术依据。
--
-- 🔑 `is_current = true OR …` 的前半边不可删（E-8/AC-9）：删掉后核价侧候选 16→0、
--    老客户 CUST-0001/0002 候选→0、本单核价转正后单价再次变空（已实测）。
--
-- ⚠️ realtime 分支内部的 f_customer_element_price(p_customer_no, p_base_date) 保持两参不变 ——
--    它不读 BOM 表，与本 BUG 无关（问题说明 §4.5）。
-- =====================================================================================

-- ---------------------------------------------------------------------------
-- ① 三参重载：函数体逐字照抄 V369 两参版，仅 candidate_materials 两处过滤放开
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION f_material_element_price(
    p_customer_no          text,
    p_base_date            date,
    p_pending_quotation_id uuid
)
RETURNS TABLE(
    material_no  varchar,
    element_code varchar,
    unit_price   numeric,
    currency     varchar,
    price_unit   varchar
)
LANGUAGE sql
STABLE
AS $$
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
$$;

COMMENT ON FUNCTION f_material_element_price(text, date, uuid) IS
  'repair-260830：料号×元素级取价（pending 感知版）。第三参 = 当前报价单 id，'
  '用于让 candidate_materials 同时看到本单 pending 影子行；传 NULL 时自动退化为纯 is_current（核价侧/冻结态）。';

-- ---------------------------------------------------------------------------
-- ② 两参版改为委托（签名与返回列逐字不变，老调用方零改动）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION f_material_element_price(
    p_customer_no text,
    p_base_date   date
)
RETURNS TABLE(
    material_no  varchar,
    element_code varchar,
    unit_price   numeric,
    currency     varchar,
    price_unit   varchar
)
LANGUAGE sql
STABLE
AS $$
    SELECT * FROM f_material_element_price(p_customer_no, p_base_date, NULL::uuid);
$$;

COMMENT ON FUNCTION f_material_element_price(text, date) IS
  'repair-260830：保留原签名，委托三参重载并传 NULL（行为与 V369 版逐字一致）。';
