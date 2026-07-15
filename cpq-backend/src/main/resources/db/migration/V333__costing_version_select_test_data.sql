-- V333__costing_version_select_test_data.sql
-- task-0713 B9：多版本核价测试数据（test.md T0.1/T0.2 验收前置）。
--
-- 背景：当前库 material_bom_item / element_bom_item 均无多版本数据（distinct bom_version=1，
-- is_current=false 行=0），版本切换功能无从测试。本迁移在真实产品数据锚点上造出可观测的
-- 拓扑变化（父节点切版 → 子件集合变），供 D2 递归 SQL 版本感知 spike + 前后端 E2E 使用。
--
-- 验收锚点（写入 docs/RECORD.md / 交付说明供后续引用）：
--   父料号 S-3120014539（PRICING/_GLOBAL_，同时是真实 PENDING 核价单 HJ-20260713-0487 的产品根料号）
--     - bom_version=2000（is_current=false，旧版）：8 个子件
--       {S-2120011658, S-2120011659, S-3111320634, S-3111320635, S-3111320636,
--        S-3111320637, S-3110520790, S-1630010773}
--     - bom_version=2001（is_current=true，新版）：6 个子件（去掉 S-1630010773 / S-3111320637）
--   元素料号 S-2120011658（PRICING/_GLOBAL_）：
--     - characteristic=2000（is_current=false，旧版）：Cu 22 / 301 78（二元）
--     - characteristic=2001（is_current=true，新版）：Cu 20 / 301 70 / Ni 10（三元）

-- ── T0.1：material_bom_item 父料号 S-3120014539 升版 ────────────────────────────

UPDATE material_bom_item
   SET is_current = false, updated_at = now()
 WHERE system_type = 'PRICING' AND customer_no = '_GLOBAL_'
   AND material_no = 'S-3120014539' AND bom_version = '2000';

INSERT INTO material_bom_item (
    system_type, customer_no, material_no, characteristic, seq_no, component_no, part_no,
    effective_datetime, expire_datetime, operation_no, operation_seq, item_seq, issue_unit,
    composition_qty, base_qty, component_usage_type, feature_mgmt, upper_limit_pct, lower_limit_pct,
    scrap_batch, scrap_rate, fixed_scrap, issue_location, issue_storage, fas_group, plug_position,
    ref_rd_center, is_optional, wo_expand_option, is_purchase_replace, component_lead_time,
    main_substitute, attached_part, ecn_no, use_qty_formula, qty_formula, scrap_rate_type,
    is_backflush, is_customer_supply, defect_rate, calc_type, recovery_discount, recovery_currency,
    recovery_unit, is_current, bom_version, rough_weight, net_weight, weight_unit, production_no
)
SELECT
    system_type, customer_no, material_no, characteristic, seq_no, component_no, part_no,
    effective_datetime, expire_datetime, operation_no, operation_seq, item_seq, issue_unit,
    composition_qty, base_qty, component_usage_type, feature_mgmt, upper_limit_pct, lower_limit_pct,
    scrap_batch, scrap_rate, fixed_scrap, issue_location, issue_storage, fas_group, plug_position,
    ref_rd_center, is_optional, wo_expand_option, is_purchase_replace, component_lead_time,
    main_substitute, attached_part, ecn_no, use_qty_formula, qty_formula, scrap_rate_type,
    is_backflush, is_customer_supply, defect_rate, calc_type, recovery_discount, recovery_currency,
    recovery_unit, true, '2001', rough_weight, net_weight, weight_unit, production_no
FROM material_bom_item
WHERE system_type = 'PRICING' AND customer_no = '_GLOBAL_'
  AND material_no = 'S-3120014539' AND bom_version = '2000'
  AND component_no NOT IN ('S-1630010773', 'S-3111320637')
  -- 幂等守卫：迁移可能因共享 DB churn 被重放，避免重复插入
  AND NOT EXISTS (
      SELECT 1 FROM material_bom_item x
       WHERE x.system_type = 'PRICING' AND x.customer_no = '_GLOBAL_'
         AND x.material_no = 'S-3120014539' AND x.bom_version = '2001'
  );

-- ── T0.2：element_bom_item 料号 S-2120011658 升版（characteristic 即版本，零新增列） ──

UPDATE element_bom_item
   SET is_current = false, updated_at = now()
 WHERE system_type = 'PRICING' AND customer_no = '_GLOBAL_'
   AND material_no = 'S-2120011658' AND characteristic = '2000';

INSERT INTO element_bom_item (
    system_type, customer_no, material_no, characteristic, component_no, part_no,
    effective_datetime, expire_datetime, operation_no, operation_seq, seq_no, issue_unit,
    composition_qty, base_qty, component_usage_type, feature_mgmt, content, upper_limit_pct,
    lower_limit_pct, scrap_batch, scrap_rate, defect_rate, fixed_scrap, issue_location,
    issue_storage, fas_group, plug_position, ref_rd_center, is_optional, wo_expand_option,
    is_purchase_replace, component_lead_time, main_substitute, attached_part, ecn_no,
    use_qty_formula, qty_formula, scrap_rate_type, is_backflush, is_customer_supply,
    recovery_discount, recovery_currency, recovery_unit, hf_part_no, is_current,
    production_no, material_part_no
)
SELECT
    system_type, customer_no, material_no, '2001', component_no, part_no,
    effective_datetime, expire_datetime, operation_no, operation_seq, seq_no, issue_unit,
    composition_qty, base_qty, component_usage_type, feature_mgmt,
    CASE component_no WHEN 'Cu' THEN 20.000000 WHEN '301' THEN 70.000000 ELSE content END,
    upper_limit_pct, lower_limit_pct, scrap_batch, scrap_rate, defect_rate, fixed_scrap,
    issue_location, issue_storage, fas_group, plug_position, ref_rd_center, is_optional,
    wo_expand_option, is_purchase_replace, component_lead_time, main_substitute, attached_part,
    ecn_no, use_qty_formula, qty_formula, scrap_rate_type, is_backflush, is_customer_supply,
    recovery_discount, recovery_currency, recovery_unit, hf_part_no, true,
    production_no, material_part_no
FROM element_bom_item
WHERE system_type = 'PRICING' AND customer_no = '_GLOBAL_'
  AND material_no = 'S-2120011658' AND characteristic = '2000'
  AND NOT EXISTS (
      SELECT 1 FROM element_bom_item x
       WHERE x.system_type = 'PRICING' AND x.customer_no = '_GLOBAL_'
         AND x.material_no = 'S-2120011658' AND x.characteristic = '2001'
  );

INSERT INTO element_bom_item (
    system_type, customer_no, material_no, characteristic, component_no, seq_no, content, is_current
)
SELECT 'PRICING', '_GLOBAL_', 'S-2120011658', '2001', 'Ni', 3, 10.000000, true
WHERE NOT EXISTS (
    SELECT 1 FROM element_bom_item x
     WHERE x.system_type = 'PRICING' AND x.customer_no = '_GLOBAL_'
       AND x.material_no = 'S-2120011658' AND x.characteristic = '2001' AND x.component_no = 'Ni'
);
