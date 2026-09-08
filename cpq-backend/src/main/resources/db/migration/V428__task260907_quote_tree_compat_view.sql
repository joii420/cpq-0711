-- task-260907 · B-7c —— 报价树换读兼容视图 + 客户权威归位（三件事同一批）
--
-- 服务的 AC：AC-1（用户 2026-09-07 裁决 A0-6；落地方式见 backtask B-7c）
--
-- ============ 🚦 为什么是追加 V427，而不是改写 V426 ============
-- V426 已应用到隔离库（flyway_schema_history 426 success=t），改写 = checksum 失配，
-- 已应用库下次启动直接挂。与 V425 同一条理由。
-- 追加式的决定性好处：全新库跑 V426→V427、已应用库只跑 V427，两条路径收敛同态。
-- 并且【一条 UPDATE 写完整最终模板】顺带消灭了「谁后落谁赢」——
-- 并发会话也改这一行 sql_template，分两条迁移各改一半必然静默互相覆盖。
--
-- ============ 🚨 三件事必须同一批，缺一即坏 ============
--   只做 ②（视图改权威列）：ds_quote_material_bom 里 69 行全是 CUST-0001（V423 统一回填）
--                            ⇒ CUST-0004 的树直接空掉
--   只做 ③（回填）：视图仍靠 cust_scope 合成客户号 ⇒ 隔离照样被扇出抹平
--   只做 ①（换表）：递归读到的仍是被抹平的视图，AC-1 验不出

-- ============================================================================
-- ① 回填：能从 cust_scope 唯一推出客户的按推出来的填，推不出的保持 V423 的 CUST-0001
--
--    ⚠️ 必须排在 ② 之前：② 之后视图直接读 b.customer_no，先改视图会有一瞬间
--       CUST-0004 的树是空的（虽然在同一事务里外部看不到，但顺序写对更省事）。
--
--    ⚠️ 「唯一才推」不是保守，是 cust_scope 里确实有歧义：
--       实测 TEST-Q13-CODE 同时映射 C1 与 CUST-0001，而它在 ds_quote_material_bom 里
--       只有 1 行 —— 一行拆不成两行，任选一个都是编数据。⇒ 歧义的保持原值。
--       后果见文件末尾「落地后的已知差异」。
--
--    影响面（隔离库 2026-09-07 实测，UPDATE 带精确 WHERE，非全表）：
--       可推出客户的行 10 / 其中真正改值 4（S0001×2, S0002, T260907-M1：CUST-0001→CUST-0004）
--       推不出、保持 CUST-0001 的行 59
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
UPDATE ds_quote_material_bom b
   SET customer_no = u.customer_no,
       updated_at  = now()
  FROM uniq u
 WHERE u.material_no::text = b.material_no::text
   AND b.customer_no::text <> u.customer_no::text;

-- ============================================================================
-- ② 兼容视图 ds_ 分支改 (c) 形态：EXISTS 只过滤，customer_no 取权威列
--
--    🚨 病灶：原本是 JOIN cust_scope cs ON cs.material_no = b.material_no —— 【没有客户条件】
--       ⇒ 一个料号在 cust_scope 里对应两个客户时，该料号的【每一行】都被发两遍、各带一个
--         客户号，与该行自己的 customer_no 无关 ⇒ AC-1 要验的隔离被视图反向抹平。
--       实测（改前）：视图给 ds_ 行合成的 customer_no 与表自身 customer_no 不一致 5/15 行。
--
--    🚦 为什么用 EXISTS 而不是给 JOIN 补 AND cs.customer_no = b.customer_no：
--       后者是【掉行】（把不一致的行剔掉，实测 12 → 7 行，掉 42%）；
--       EXISTS 让【扇出结构上消失】而不是把它压回 1，零掉行。
--
--    三处改动（缺一即编译不过或语义不对）：
--       a. id 合成 md5('dqmb:'||b.id||':'|| cs.customer_no) → b.customer_no
--          （不改的话 cs 已不在作用域，直接报错；且 id 必须随权威客户走）
--       b. 输出列 cs.customer_no → b.customer_no
--       c. NOT EXISTS 去重子句里的 x.customer_no = cs.customer_no → b.customer_no
--          ⚠️ 去重子句本身必须保留 —— 它保证「V6 已有该(料号,客户)的正式行时，ds_ 影子行不重复出」
--
--    🚫 CREATE OR REPLACE VIEW 要求列名/类型/顺序完全一致，本处逐列照抄原定义，只动上述三处。
-- ============================================================================
CREATE OR REPLACE VIEW v_compat_material_bom_item AS
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
    v.seq_no,
    v.component_no,
    v.part_no,
    v.effective_datetime,
    v.expire_datetime,
    v.operation_no,
    v.operation_seq,
    v.item_seq,
    v.issue_unit,
    v.composition_qty,
    v.base_qty,
    v.component_usage_type,
    v.feature_mgmt,
    v.upper_limit_pct,
    v.lower_limit_pct,
    v.scrap_batch,
    v.scrap_rate,
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
    v.defect_rate,
    v.calc_type,
    v.recovery_discount,
    v.recovery_currency,
    v.recovery_unit,
    v.created_at,
    v.updated_at,
    v.created_by,
    v.updated_by,
    v.is_current,
    v.bom_version,
    v.rough_weight,
    v.net_weight,
    v.weight_unit,
    v.production_no,
    v.pending_quotation_id,
    v.pending_supersedes,
    v.material_ratio
   FROM material_bom_item v
UNION ALL
 SELECT md5((('dqmb:'::text || b.id::text) || ':'::text) || b.customer_no::text)::uuid AS id,
    'QUOTE'::character varying(10) AS system_type,
    b.customer_no,
    b.material_no::character varying(20) AS material_no,
    b.output_material_type::character varying(100) AS characteristic,
    b.item_seq AS seq_no,
    b.input_material_no::character varying(20) AS component_no,
    NULL::character varying(20) AS part_no,
    NULL::timestamp(6) with time zone AS effective_datetime,
    NULL::timestamp(6) with time zone AS expire_datetime,
    NULL::character varying(20) AS operation_no,
    NULL::character varying(20) AS operation_seq,
    NULL::integer AS item_seq,
    NULL::character varying(20) AS issue_unit,
    b.component_qty::numeric(24,12) AS composition_qty,
    NULL::numeric(24,12) AS base_qty,
    mr.symbol::character varying(100) AS component_usage_type,
    NULL::character varying(20) AS feature_mgmt,
    NULL::numeric(18,12) AS upper_limit_pct,
    NULL::numeric(18,12) AS lower_limit_pct,
    NULL::numeric(24,12) AS scrap_batch,
    NULL::numeric(18,12) AS scrap_rate,
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
    NULL::numeric(18,12) AS defect_rate,
    NULL::character varying(20) AS calc_type,
    NULL::numeric(18,12) AS recovery_discount,
    NULL::character varying(10) AS recovery_currency,
    NULL::character varying(20) AS recovery_unit,
    b.created_at::timestamp(6) with time zone AS created_at,
    COALESCE(b.updated_at, b.created_at)::timestamp(6) with time zone AS updated_at,
    NULL::uuid AS created_by,
    NULL::uuid AS updated_by,
    true AS is_current,
    NULL::character varying(20) AS bom_version,
    NULL::numeric(26,12) AS rough_weight,
    NULL::numeric(26,12) AS net_weight,
    NULL::character varying(20) AS weight_unit,
    NULL::character varying(32) AS production_no,
    NULL::uuid AS pending_quotation_id,
    NULL::uuid[] AS pending_supersedes,
    b.material_ratio::numeric(24,12) AS material_ratio
   FROM ds_quote_material_bom b
     LEFT JOIN material_recipe mr ON mr.code::text = b.input_material_no::text
  WHERE EXISTS ( SELECT 1 FROM cust_scope cs WHERE cs.material_no::text = b.material_no::text)
    AND NOT (EXISTS ( SELECT 1
           FROM material_bom_item x
          WHERE x.system_type::text = 'QUOTE'::text AND x.is_current AND x.material_no::text = b.material_no::text AND x.customer_no::text = b.customer_no::text));

-- ============================================================================
-- ③ QUOTE 递归模板：4 处 material_bom_item → v_compat_material_bom_item
--    （连同 V426 的 :customerCode 参数化一起，一条 UPDATE 写入完整最终模板）
--
--    🚫 usage='COSTING' 一个字不动 —— 它带 :versionFilter 宏，而兼容视图新表侧无版本列；
--       锚点也不同轴（生产料号）。本 WHERE 不命中它。
--
--    ✅ pending 可见性自动闭合：v_compat_material_bom_item 已在
--       QuotePendingRewriter.WHITELIST_TABLES 里 ⇒ 改写命中，V6 那半边照常带出本单 pending 行。
--       （🚫 因此不需要、也不许动白名单：ds_ 裸表既无 is_current 也无 pending_quotation_id，
--         而改写器 :365/:371-372 无条件生成 t.pending_quotation_id = :pq，加进去会运行时报
--         column does not exist。）
-- ============================================================================
UPDATE costing_bom_tree_config
SET sql_template = $tpl$WITH RECURSIVE bom AS (
  SELECT p::text AS root_no, p::text AS material_no,
    (SELECT bv.bom_version::text FROM v_compat_material_bom_item bv WHERE bv.material_no=p AND bv.system_type='QUOTE' AND bv.is_current LIMIT 1) AS bom_version,
    NULL::text AS parent_no, p::text AS node_path,
    COALESCE(:customerCode::varchar,
      (SELECT bc.customer_no FROM v_compat_material_bom_item bc WHERE bc.material_no=p AND bc.system_type='QUOTE' AND bc.is_current ORDER BY bc.customer_no LIMIT 1)) AS _cust
  FROM unnest(:production_part_nos) AS p
  UNION ALL
  SELECT b.root_no, ch.component_no::text,
    (SELECT bv.bom_version::text FROM v_compat_material_bom_item bv WHERE bv.material_no=ch.component_no AND bv.system_type='QUOTE' AND bv.is_current LIMIT 1),
    ch.material_no::text, (b.node_path||'/'||ch.component_no)::text, b._cust
  FROM v_compat_material_bom_item ch JOIN bom b ON ch.material_no=b.material_no AND ch.customer_no=b._cust
  WHERE ch.system_type='QUOTE' AND ch.is_current AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path FROM bom$tpl$,
    updated_at = now()
WHERE usage = 'QUOTE' AND is_active = true;

-- ============================================================================
-- 落地后应成立的不变量（供人工复核；🚫 迁移不做业务校验）
--   ① v_compat_material_bom_item 对 material_bom_item 仍是严格超集：
--        SELECT count(*) FROM (SELECT id FROM material_bom_item
--                              EXCEPT SELECT id FROM v_compat_material_bom_item) z;  → 0
--   ② ds_ 分支（视图里不属于 V6 的那些行）逐行客户号 = 该行自身 ds_quote_material_bom.customer_no
--   ③ QUOTE 模板含 v_compat_material_bom_item ×4 且含 :customerCode；COSTING 模板逐字未变
--
-- ⚠️ 落地后的已知差异（主线 2026-09-07 已在可回滚事务里实测，🚫 不是推测）：
--    ds_ 分支从 11 行变成 10 行，消失的是
--        C1 | TEST-Q13-CODE | TEST-Q13-CODE | seq 1 | id f3af6d74-0c3e-4052-c966-ffefbb59e2ca
--    其余 10 行【id 逐字节相同】、客户号与 backtask B-7c 期望表逐行一致。
--    根因链（每一环都实测过）：
--      TEST-Q13-CODE 在 cust_scope 里映射 C1 + CUST-0001（两个客户）
--      → 回填「唯一才推」推不出 → 该 ds 行保持 CUST-0001
--      → 视图 NOT EXISTS 去重发现 V6 已有 (TEST-Q13-CODE, CUST-0001, is_current) 的正式行
--      → 该 ds 行被判重复、剔除。
--    它今天之所以还在，纯粹是 JOIN cust_scope 的扇出把这唯一一条 ds 行复制出了一份挂在 C1 名下，
--    而 C1 那份在 V6 里没有对应行，因此逃过了去重 —— 也就是说，
--    【这一行是扇出缺陷制造出来的行，不是真实数据】。删掉它正是本次修复的题中之义。
--    ⇒ 但它有业务后果（客户 C1 的树少一个节点），已在回报里单独拎出报主线。
-- ============================================================================
