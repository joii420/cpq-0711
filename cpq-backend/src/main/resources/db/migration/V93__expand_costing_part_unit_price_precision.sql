-- V93: 扩大 costing_part_* 单价字段精度, 防止小数值(能耗/折旧)被四舍五入丢失
--
-- 问题: V76 定义 unit_price NUMERIC(18,6), 仅 6 位小数。
--   v4 Excel 的能耗单价(包装工序按 PCS 计) 出现:
--     - 生产能耗 Z002: 0.00000014 (1.4e-7, 8 位小数) → 存为 0 (丢失!)
--     - 设备折旧 Z002: 0.0000025  (2.5e-6, 7 位小数) → 存为 0.000003 (四舍五入)
--   类似地, 包装能耗常常在 1e-7 ~ 1e-9 量级, 6 位小数完全不够。
--
-- 修复: 把 3 张 costing_part_* 表的金额/单价字段扩到 NUMERIC(20,10)
--       即支持小到 1e-10, 总有效位 20 (比之前 NUMERIC(18,6) 还多 2 位整数余量)。
--
-- 影响表/字段:
--   costing_part_process_cost.unit_price          NUMERIC(18,6) → NUMERIC(20,10)
--   costing_part_tooling_cost.tooling_unit_cost   NUMERIC(18,4) → NUMERIC(20,10)
--   costing_part_tooling_cost.unit_price          NUMERIC(18,6) → NUMERIC(20,10) (派生 = 单套/寿命/产量, 极小)
--   costing_part_weight.weight_g_per_pcs          NUMERIC(18,6) → NUMERIC(20,10) (重量 mg 级也无精度损失)
--
-- ALTER TYPE 在 PostgreSQL 中会触发表重写, 但 NUMERIC 扩精度是无损的(类型兼容), 现有数据自动放大。
-- 缺少精度的数据(如已存的 0.000000)无法恢复 — admin 重新导入即可。

ALTER TABLE costing_part_process_cost
    ALTER COLUMN unit_price TYPE NUMERIC(20,10);

ALTER TABLE costing_part_tooling_cost
    ALTER COLUMN tooling_unit_cost TYPE NUMERIC(20,10),
    ALTER COLUMN unit_price        TYPE NUMERIC(20,10);

ALTER TABLE costing_part_weight
    ALTER COLUMN weight_g_per_pcs  TYPE NUMERIC(20,10);

COMMENT ON COLUMN costing_part_process_cost.unit_price IS
    'V93: 扩到 NUMERIC(20,10) - 支持包装工序按 PCS 计的能耗 1e-7 ~ 1e-9 量级单价';
