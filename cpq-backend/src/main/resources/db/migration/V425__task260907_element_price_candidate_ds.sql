-- task-260907 · B-8 —— 元素价格策略闭环：撤销 V424 的换边方案，改为给候选料号集补 ds_ 支
--
-- 服务的 AC：AC-8（用户 2026-09-07 裁决「加上，让元素价格策略闭环」）
--
-- ============ 🚦 为什么撤销 V424（用户 2026-09-07 改判，🚫 不要走回头路） ============
-- V424 把 ELEMENT_BOM(QUOTE) 的 PRICE 边从 f_material_element_price 改指 f_customer_element_price。
-- 主线全客户实测证明这是【真回归】，不是等价替换：
--
--   f_material_element_price(customer, date) 是 UNION 的两支
--     versioned : material_price_version_ref → element_price_version_item   ← 按【料号】钉的版本价
--     realtime  : candidate_materials CROSS JOIN f_customer_element_price   ← 客户级实时价，纯扇出
--   ⇒ 改指 f_customer_element_price 等于把 versioned 整支丢掉。
--
--   实测（cpq_t260907_custdim，逐客户扫而不是抽一个客户下全称结论）：
--     SELECT c.code, (SELECT count(*) FROM (
--         SELECT element_code FROM f_material_element_price(c.code, CURRENT_DATE)
--         GROUP BY element_code HAVING count(DISTINCT unit_price) > 1) z)
--     FROM customer c;
--     → CUST-0002 = 1 · CUST-0729-QA = 1 · 其余全 0
--   CUST-0002 有 1 个料号把 Ag 钉在 3500，而客户级实时价是 3000
--   ⇒ 换边会让它【静默从 3500 掉到 3000】，且引用 FUNC_ELEMENT_PRICE 的 21 个存量视图全走这条路。
--
-- ⇒ 真正的缺口不在「哪个函数」，在【候选料号集】：candidate_materials 只读 V6 的
--   material_bom_item / element_bom_item，ds_quote_* 独有的料号根本进不了候选集，
--   realtime 那支的 CROSS JOIN 就带不出它们（实测 ds_ 独有料号 = 8 个）。
--
-- ============ 🚦 为什么是新增 V425 而不是改写 V424 ============
-- V424 已应用到隔离库 cpq_t260907_custdim（flyway_schema_history success=true）。
-- 改写它 = checksum 失配，已应用库下次启动直接挂；且违反「schema 变更一律新建迁移脚本」。
-- 追加式的决定性好处：全新库跑 V424→V425、已应用库只跑 V425，【两条路径收敛到同一状态】。

-- ============================================================================
-- 1. 撤销 V424 ①：PRICE 边改回指向 FUNC_ELEMENT_PRICE
--    老节点 ddc7fafa-0fd3-5c47-a86c-e3f8b86b0f76 = FUNC_ELEMENT_PRICE(QUOTE)，V424 未删，原样还在。
--    note 逐字还原成 V413 seed 的原文，避免留下「改过又改回来」的半截状态。
-- ============================================================================
UPDATE semantic_edge
SET to_node_id = 'ddc7fafa-0fd3-5c47-a86c-e3f8b86b0f76',
    note = '双条件 JOIN；cep.material_no 必须与 hf_part_no 表达式逐字一致（AC-1⑤）',
    updated_by = 'seed',
    updated_at = now()
WHERE id = '5b1bfc30-551b-511c-bb2b-42becb609a06';

-- ============================================================================
-- 2. 撤销 V424 ②：恢复被删掉的料号连接键（seq=1）
--    🚨 这一步不能漏：V424 删它是因为 f_customer_element_price 没有 material_no 列；
--    边改回 f_material_element_price 之后，少了这个键 JOIN 就只剩 element_code 单条件，
--    同一元素会跨全部料号笛卡尔扇出 —— 单价看着有值、但行数与归属全错，且不报错。
--    id 沿用 V413 seed 的原 id，让「撤销」在主键层面也是真正的还原。
-- ============================================================================
INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
VALUES ('8d31087a-b15a-5a9a-bc45-2a02fd221efe',
        '5b1bfc30-551b-511c-bb2b-42becb609a06',
        'material_no', 'material_no', 1)
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 3. 撤销 V424 ④：QUOTE/材质元素 上的 AUX 挂载改回老节点
--    漏了这一步，老节点不再等于 priceGroupNodeId，会从 FieldTreeBuilder 的通用 tvns 循环里
--    再冒出一组，groupKind='PRICE' 组数从 1 变 2（正是 取数配置器补齐 AC-30 的成因）。
-- ============================================================================
UPDATE semantic_tab_view_node
SET node_id = 'ddc7fafa-0fd3-5c47-a86c-e3f8b86b0f76',
    updated_by = 'seed',
    updated_at = now()
WHERE id = '38921742-f391-53b9-8d10-0326bf7cc189';

-- ============================================================================
-- 4. 删除 V424 新建的节点及其 3 个节点列
--    ⚠️ 顺序：先列后节点（semantic_node_column.node_id 外键指向 semantic_node）。
--    ⚠️ 必须在 1/3 之后：边与挂载都还指着它时删节点会被外键挡住。
--    两条 DELETE 都带精确 WHERE，命中面 = V424 自己插入的 1 个节点 + 3 个列，
--    不存在「无 WHERE 全表删」的形态。
-- ============================================================================
DELETE FROM semantic_node_column
WHERE node_id IN (SELECT id FROM semantic_node WHERE node_key = 'FUNC_CUSTOMER_ELEMENT_PRICE');

DELETE FROM semantic_node
WHERE node_key = 'FUNC_CUSTOMER_ELEMENT_PRICE';

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
-- 6. 落地后应成立的不变量（供人工复核；🚫 不在此处断言，迁移不做业务校验）
--    ① SELECT count(*) FROM semantic_node WHERE node_key='FUNC_CUSTOMER_ELEMENT_PRICE'  → 0
--    ② ELEMENT_BOM(QUOTE) 出边里 edge_kind='PRICE' 的条数 = 1，to_node = FUNC_ELEMENT_PRICE
--    ③ 该边的 semantic_edge_key = 2 行（element_code seq=0 / material_no seq=1）
--    ④ 材质元素/QUOTE 的 semantic_tab_view_node 仍是 2 行，AUX 指回 FUNC_ELEMENT_PRICE
--    ⑤ 除 ds_ 侧新增料号的客户外，f_material_element_price 逐客户 md5 不变
-- ============================================================================
