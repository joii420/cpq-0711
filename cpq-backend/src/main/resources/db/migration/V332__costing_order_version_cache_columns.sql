-- V332__costing_order_version_cache_columns.sql
-- task-0713 B5：核价单缓存列（D1 落定）——核价侧「live 重算 + 结果缓存回核价单」的落点。
-- 绝不复用 total_amount（那是含 Step3 折扣的报价总额，语义不同）：
--   costing_render        = {lineItemId: {costingCardValues, costingExcelValues}}，已应用本单 override
--   costing_total_amount  = Σ 核价成本 subtotal，不含 Step3 折扣
ALTER TABLE costing_order
    ADD COLUMN costing_render jsonb,
    ADD COLUMN costing_total_amount numeric(18,4);
