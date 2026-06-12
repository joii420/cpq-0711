-- KSUM E2E seed (幂等): 料8 (material_no=3110520790) 外购件 >=2 行
-- 客户: 苏州西门子 (customer_no='8000137')
-- 用途: KSUM 公式验收点 — 选产品 hfPartNo='3110520790' 时 $wgj_view.费用 有值 (Kp=1.5)
--
-- 核心逻辑:
--   $wgj_view SQL: SELECT finished_material_no hf_part_no, ... FROM unit_price
--                  WHERE system_type='QUOTE' AND price_type='COMPONENT_OTHER'
--                        AND is_current=true AND customer_no=:customerCode
--   ImplicitJoinRewriter 注入 hf_part_no = lineItem.partNo (即 product.hfPartNo)
--   → 需要 unit_price.finished_material_no = '3110520790' 且 customer_no = '8000137'
--
-- 参照料9 (code='1630010773', finished_material_no='3120018220') 的现役数据结构
-- version_no='2000' (与现役 QUOTE COMPONENT_OTHER 一致)
-- Kp 期望 = SUM(pricing_price) = 1.0 + 0.5 = 1.5

-- ─────────────────────────────────────────────
-- Step 1: 先删本 seed 行 (幂等 DELETE)
-- ─────────────────────────────────────────────
DELETE FROM unit_price
 WHERE system_type   = 'QUOTE'
   AND price_type    = 'COMPONENT_OTHER'
   AND customer_no   = '8000137'
   AND finished_material_no = '3110520790'
   AND cost_type IN ('KSUM测试件A', 'KSUM测试件B');

-- ─────────────────────────────────────────────
-- Step 2: 插 2 行 (pricing_price: 1.0 / 0.5 → Kp=1.5)
-- ─────────────────────────────────────────────
INSERT INTO unit_price (
    system_type,
    price_type,
    version_no,
    code,
    finished_material_no,
    customer_no,
    customer_name,
    cost_type,
    pricing_price,
    unit,
    is_current
) VALUES
(
    'QUOTE',
    'COMPONENT_OTHER',
    '2000',
    '3110520790',          -- code: 料8 material_no (外购件编号)
    '3110520790',          -- finished_material_no: 料8 (成品), 供 hf_part_no 谓词匹配
    '8000137',
    '苏州西门子',
    'KSUM测试件A',
    1.0,
    '元',
    true
),
(
    'QUOTE',
    'COMPONENT_OTHER',
    '2000',
    '3110520790',          -- code: 料8 material_no (外购件编号)
    '3110520790',          -- finished_material_no: 料8 (成品)
    '8000137',
    '苏州西门子',
    'KSUM测试件B',
    0.5,
    '元',
    true
);
