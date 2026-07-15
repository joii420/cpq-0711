-- V335__costing_version_select_fixture_annual_volume.sql
-- task-0713 返修（技术总监 live 验收发现 3a 总价联动 bug 后追加）：
-- 测试锚点报价行 68fd50d9-34ea-4c7c-8f7c-ac4f521030ec（真实 PENDING 核价单 HJ-20260713-0487
-- 的产品行，S-3120014539）annual_volume 原为 NULL，导致 costing_total_amount(=单件成本×年用量)
-- 恒为 0，掩盖了 CostingSubtotalUtil/SUBTOTAL 公式求值链路的真实 bug（componentType 字段缺失 +
-- SUBTOTAL tab 恒不回填 componentSubtotals，见同批次 CardSnapshotService 修复）。
-- 补一个 >0 的年用量，使 3a 总价联动可被真实验证（非 0 即通过的假绿测试）。
UPDATE quotation_line_item
   SET annual_volume = 100
 WHERE id = '68fd50d9-34ea-4c7c-8f7c-ac4f521030ec'
   AND annual_volume IS NULL;
