-- V78: 核价基础数据视图 — 把"当前默认 PUBLISHED 版本"暴露为简单视图
-- 让 BASIC_DATA path 可以直接引用而无需感知 version_id（自动锁定默认版本）。
--
-- 设计要点：
--   1. 视图只暴露业务字段，不暴露 version_id —— 避免 BNF 路径需要写死 version_id
--   2. 视图名 v_costing_*，纯 ASCII 标识符 → SchemaContext.resolveTable 直接当物理表名通过
--   3. 视图本身不被 PathToSqlGenerator.VERSIONED_TABLES 命中（仅含 mat_fee/mat_process/plating_fee），不会误注 is_current
--   4. 视图无 customer_id / hf_part_no 列 → ImplicitJoinRewriter 不会注入这些谓词
--
-- 用法（在组件 BASIC_DATA 字段 basic_data_path 里写）：
--   v_costing_element_price[element_code='Ag'].costing_price        → 5800
--   v_costing_material_price[material_no='1610010128'].costing_price → 55
--   v_costing_exchange_rate[from_currency='CNY', to_currency='USD'].costing_rate → 0.138
--
-- 当用户切换默认版本（在「核价基础数据」菜单里设另一个版本为默认），视图自动跟进，无需改组件字段。

CREATE OR REPLACE VIEW v_costing_element_price AS
SELECT e.id,
       e.element_code,
       e.costing_price,
       e.market_ref_price,
       e.source_url,
       e.source_name,
       e.source_rule,
       e.currency,
       e.unit,
       e.discount_rate,
       e.sort_order
FROM costing_element_price e
JOIN costing_price_version v ON v.id = e.version_id
WHERE v.version_kind = 'ELEMENT'
  AND v.status      = 'PUBLISHED'
  AND v.is_default  = TRUE;

CREATE OR REPLACE VIEW v_costing_material_price AS
SELECT m.id,
       m.material_no,
       m.brand_name,
       m.spec,
       m.dimension,
       m.costing_price,
       m.market_ref_price,
       m.source_url,
       m.source_name,
       m.source_rule,
       m.currency,
       m.unit,
       m.discount_rate,
       m.sort_order
FROM costing_material_price m
JOIN costing_price_version v ON v.id = m.version_id
WHERE v.version_kind = 'MATERIAL'
  AND v.status      = 'PUBLISHED'
  AND v.is_default  = TRUE;

CREATE OR REPLACE VIEW v_costing_exchange_rate AS
SELECT r.id,
       r.from_currency,
       r.to_currency,
       r.costing_rate,
       r.market_rate,
       r.rate_rule,
       r.source_url,
       r.sort_order
FROM costing_exchange_rate r
JOIN costing_price_version v ON v.id = r.version_id
WHERE v.version_kind = 'EXCHANGE'
  AND v.status      = 'PUBLISHED'
  AND v.is_default  = TRUE;

COMMENT ON VIEW v_costing_element_price  IS '核价元素价格 — 自动锁定到默认 PUBLISHED 版本（供 BASIC_DATA path 引用）';
COMMENT ON VIEW v_costing_material_price IS '核价材料价格 — 自动锁定到默认 PUBLISHED 版本';
COMMENT ON VIEW v_costing_exchange_rate  IS '核价汇率 — 自动锁定到默认 PUBLISHED 版本';
