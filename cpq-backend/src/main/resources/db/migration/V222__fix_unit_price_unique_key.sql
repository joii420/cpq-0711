-- ============================================================
-- V222: 修复 unit_price 业务唯一键 (V220 设计漏洞)
--
-- 问题：V220 的 uq_unit_price 仅含 (system_type, price_type, version_no, code,
--       customer_no, supplier_no, effective_date)，缺 cost_type / finished_material_no /
--       operation_no / seq_no，导致不同费用类型的同一料号互相冲突。
--
-- 报价/核价导入方案各 Sheet 的业务键：
--   元素单价         (customer_no, code, version_no)
--   来料加工费/其他   (customer_no, finished_material_no, code, seq_no, cost_type)
--   自制加工费       (customer_no, finished_material_no, code, seq_no, operation_no, cost_type)
--   电镀费用         (customer_no, code, version_no, cost_type)  -- 一行拆两条不同 cost_type
--   组成件其他费用    (customer_no, finished_material_no, code, operation_no, seq_no, item_seq, cost_type)
--
-- 所以 unique index 必须含：cost_type / finished_material_no / operation_no / seq_no
-- 加上原有 (system_type, price_type, version_no, code, customer_no, supplier_no, effective_date)
-- ============================================================

DROP INDEX IF EXISTS uq_unit_price;

CREATE UNIQUE INDEX uq_unit_price ON unit_price(
    system_type,
    price_type,
    COALESCE(cost_type, ''),
    version_no,
    code,
    COALESCE(customer_no, ''),
    COALESCE(supplier_no, ''),
    COALESCE(finished_material_no, ''),
    COALESCE(operation_no, ''),
    COALESCE(seq_no, 0),
    COALESCE(effective_date, DATE '1900-01-01')
);

COMMENT ON INDEX uq_unit_price IS 'V6 unit_price 业务唯一键 (11 维)；ON CONFLICT 用同样表达式列表';
