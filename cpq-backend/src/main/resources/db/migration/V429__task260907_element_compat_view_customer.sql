-- task-260907 · B-7c 续 —— element 侧同型缺陷：兼容视图客户权威归位 + 回填口径对齐
--
-- 服务的 AC：AC-1（用户 2026-09-07 裁决「element 侧同型缺陷本期一并修」）
--
-- ============ 与 V427 的关系：同一个缺陷的另一半 ============
-- V427 修的是 v_compat_material_bom_item + ds_quote_material_bom。
-- v_compat_element_bom_item 是【逐字同型】的：
--     FROM ds_quote_element_bom e
--       JOIN cust_scope cs ON cs.material_no = e.material_no      ← 同样【没有客户条件】
--     SELECT md5('dqeb:'||e.id||':'||cs.customer_no)::uuid AS id, ..., cs.customer_no, ...
--     WHERE NOT EXISTS (... x.customer_no = cs.customer_no)
-- ⇒ 料号在 cust_scope 里对应两个客户时，该料号的每一行都被发两遍、各带一个客户号，
--   与该行自己的 customer_no 无关 —— 材质元素页签会串客户，与 material 侧同一机制。
--
-- 🔑 为什么必须【本期】一起改，而不是「以后再修」：
--   今天 element 侧回填能改值的行 = 0（实测，见下），所以「只改 material 侧」当下不掉数据。
--   真正的代价不是延迟，是【口径分叉】—— 一张表按 cust_scope 推、另一张统一 CUST-0001，
--   下一个人看到两种口径分不清哪个是对的，而且两者都「跑得通」。

-- ============================================================================
-- ① 回填：与 V427 §① 逐字同一段 SQL（cust_scope 唯一才推，推不出保持 CUST-0001）
--
--    影响面（隔离库 2026-09-07 实测，用当前数据重算、🚫 未套用 material 侧数字）：
--      ds_quote_element_bom 总行 62
--      可推出客户的行 5 / 其中【会被改值的行 0】/ 推不出保持 CUST-0001 的行 57
--      歧义料号（cust_scope 映射 >1 个客户）命中的 element 行 = 0
--    ⇒ 本条今天是【零改值】的。它存在的意义是把口径钉死，让 element 侧与 material 侧
--      从此走同一条规则 —— 而不是靠「现在恰好一样」。
--    ⚠️ 正因为它今天零改值，🚫 不要把「element 侧判据表零变化」当成本迁移生效的证据：
--      真正证明 ② 生效的是「视图不再合成 customer_no」，见文件末尾的还原实验说明。
-- ============================================================================
WITH cust_scope AS (
  SELECT DISTINCT cp.customer_no, cp.material_no
    FROM ds_quote_customer_part cp
   WHERE cp.material_no IS NOT NULL
  UNION
  SELECT DISTINCT cp.customer_no, b.input_material_no AS material_no
    FROM ds_quote_customer_part cp
    JOIN ds_quote_material_bom b ON b.material_no::text = cp.material_no::text
   WHERE cp.material_no IS NOT NULL AND b.input_material_no IS NOT NULL
),
uniq AS (
  SELECT material_no, min(customer_no) AS customer_no
    FROM cust_scope
   GROUP BY material_no
  HAVING count(DISTINCT customer_no) = 1
)
UPDATE ds_quote_element_bom e
   SET customer_no = u.customer_no,
       updated_at  = now()
  FROM uniq u
 WHERE u.material_no::text = e.material_no::text
   AND e.customer_no::text <> u.customer_no::text;

-- ============================================================================
-- ② v_compat_element_bom_item 的 ds_ 分支改 (c) 形态：EXISTS 只过滤，customer_no 取权威列
--
--    三处改动（与 V427 §② 一一对应）：
--      a. id 合成 md5('dqeb:'||e.id||':'|| cs.customer_no) → e.customer_no
--         （不改的话 cs 已不在作用域直接报错；且 id 必须随权威客户走）
--      b. 输出列 cs.customer_no → e.customer_no
--      c. JOIN cust_scope → WHERE EXISTS(...)；
--         🚨 NOT EXISTS 去重子句【保留】，其中 x.customer_no = cs.customer_no → e.customer_no
--         —— 这是 V427 里最容易漏的一处：漏了它，V6 已有该 (料号,客户) 正式行时
--            ds_ 影子行会重复出一遍，症状是行数翻倍而不是报错。
--
--    🚦 为什么用 EXISTS 而不是给 JOIN 补 AND cs.customer_no = e.customer_no：
--       后者是【掉行】（把不一致的行剔掉）；EXISTS 让【扇出结构上消失】而不是把它压回 1，零掉行。
--
--    🚫 CREATE OR REPLACE VIEW 要求列名/类型/顺序完全一致：本处逐列照抄原定义，只动上述三处。
-- ============================================================================
CREATE OR REPLACE VIEW v_compat_element_bom_item AS
 WITH cust_scope AS (
         SELECT DISTINCT cp.customer_no,
            cp.material_no
           FROM ds_quote_customer_part cp
          WHERE cp.material_no IS NOT NULL
        UNION
         SELECT DISTINCT cp.customer_no,
            b.input_material_no AS material_no
           FROM ds_quote_customer_part cp
             JOIN ds_quote_material_bom b ON b.material_no::text = cp.material_no::text
          WHERE cp.material_no IS NOT NULL AND b.input_material_no IS NOT NULL
        )
 SELECT v.id,
    v.system_type,
    v.customer_no,
    v.material_no,
    v.characteristic,
    v.component_no,
    v.part_no,
    v.effective_datetime,
    v.expire_datetime,
    v.operation_no,
    v.operation_seq,
    v.seq_no,
    v.issue_unit,
    v.composition_qty,
    v.base_qty,
    v.component_usage_type,
    v.feature_mgmt,
    v.content,
    v.upper_limit_pct,
    v.lower_limit_pct,
    v.scrap_batch,
    v.scrap_rate,
    v.defect_rate,
    v.fixed_scrap,
    v.issue_location,
    v.issue_storage,
    v.fas_group,
    v.plug_position,
    v.ref_rd_center,
    v.is_optional,
    v.wo_expand_option,
    v.is_purchase_replace,
    v.component_lead_time,
    v.main_substitute,
    v.attached_part,
    v.ecn_no,
    v.use_qty_formula,
    v.qty_formula,
    v.scrap_rate_type,
    v.is_backflush,
    v.is_customer_supply,
    v.recovery_discount,
    v.recovery_currency,
    v.recovery_unit,
    v.created_at,
    v.updated_at,
    v.created_by,
    v.updated_by,
    v.hf_part_no,
    v.is_current,
    v.production_no,
    v.material_part_no,
    v.pending_quotation_id,
    v.pending_supersedes
   FROM element_bom_item v
UNION ALL
 SELECT md5((('dqeb:'::text || e.id::text) || ':'::text) || e.customer_no::text)::uuid AS id,
    'QUOTE'::character varying(10) AS system_type,
    e.customer_no,
    e.material_no::character varying(20) AS material_no,
    '2000'::character varying(100) AS characteristic,
    e.element_code::character varying(20) AS component_no,
    NULL::character varying(20) AS part_no,
    NULL::timestamp(6) with time zone AS effective_datetime,
    NULL::timestamp(6) with time zone AS expire_datetime,
    NULL::character varying(20) AS operation_no,
    NULL::character varying(20) AS operation_seq,
    e.item_seq AS seq_no,
    NULL::character varying(20) AS issue_unit,
    NULL::numeric(24,12) AS composition_qty,
    NULL::numeric(24,12) AS base_qty,
    NULL::character varying(100) AS component_usage_type,
    NULL::character varying(20) AS feature_mgmt,
    e.content_pct::numeric(24,12) AS content,
    NULL::numeric(18,12) AS upper_limit_pct,
    NULL::numeric(18,12) AS lower_limit_pct,
    NULL::numeric(24,12) AS scrap_batch,
    NULL::numeric(18,12) AS scrap_rate,
    NULL::numeric(18,12) AS defect_rate,
    NULL::numeric(24,12) AS fixed_scrap,
    NULL::character varying(50) AS issue_location,
    NULL::character varying(50) AS issue_storage,
    NULL::character varying(20) AS fas_group,
    NULL::character varying(50) AS plug_position,
    NULL::character varying(50) AS ref_rd_center,
    NULL::boolean AS is_optional,
    NULL::character varying(20) AS wo_expand_option,
    NULL::boolean AS is_purchase_replace,
    NULL::numeric(24,12) AS component_lead_time,
    NULL::character varying(20) AS main_substitute,
    NULL::character varying(20) AS attached_part,
    NULL::character varying(30) AS ecn_no,
    NULL::boolean AS use_qty_formula,
    NULL::character varying(500) AS qty_formula,
    NULL::character varying(20) AS scrap_rate_type,
    NULL::boolean AS is_backflush,
    NULL::boolean AS is_customer_supply,
    NULL::numeric(18,12) AS recovery_discount,
    NULL::character varying(10) AS recovery_currency,
    NULL::character varying(20) AS recovery_unit,
    e.created_at::timestamp(6) with time zone AS created_at,
    COALESCE(e.updated_at, e.created_at)::timestamp(6) with time zone AS updated_at,
    NULL::uuid AS created_by,
    NULL::uuid AS updated_by,
    e.material_no::character varying(20) AS hf_part_no,
    true AS is_current,
    NULL::character varying(32) AS production_no,
    e.material_part_no::character varying(32) AS material_part_no,
    NULL::uuid AS pending_quotation_id,
    NULL::uuid[] AS pending_supersedes
   FROM ds_quote_element_bom e
  WHERE EXISTS ( SELECT 1 FROM cust_scope cs WHERE cs.material_no::text = e.material_no::text)
    AND NOT (EXISTS ( SELECT 1
           FROM element_bom_item x
          WHERE x.system_type::text = 'QUOTE'::text AND x.is_current AND x.material_no::text = e.material_no::text AND x.customer_no::text = e.customer_no::text));

-- ============================================================================
-- 落地后应成立的不变量（供人工复核；🚫 迁移不做业务校验）
--   ① v_compat_element_bom_item 对 element_bom_item 仍是严格超集：
--        SELECT count(*) FROM (SELECT id FROM element_bom_item
--                              EXCEPT SELECT id FROM v_compat_element_bom_item) z;  → 0
--   ② ds_ 分支逐行客户号 = 该行自身 ds_quote_element_bom.customer_no（不一致行数 → 0）
--   ③ element 侧判据表（视图里不属于 V6 的那些行）与落地前【逐行一致】——
--      本次预期零变化（回填改值行 = 0）。⚠️ 判据是逐行一致，不是行数相等。
--   ④ 语义图零影响：QUOTE/材质元素 的 groupKind='PRICE' 组数 = 1、
--      PRICE 边指向 FUNC_ELEMENT_PRICE、连接键 2 条 —— 本迁移不碰 semantic_*，应逐条不变。
--   ⑤ f_material_element_price 逐客户 EXCEPT ALL「只在旧」全 0
--      （ds_quote_element_bom 是 candidate_materials 两支之一，回填会动候选集）
--
-- 🚫 本迁移不碰：usage='COSTING' 的树配置、QuotePendingRewriter 白名单、
--    semantic_node / semantic_edge / semantic_tab_view_node、f_material_element_price 本身。
-- ============================================================================
