-- task-0729 需求变更：客户价格调整策略「默认关闭」，且存量全部关闭。
--
-- 业务方原话：「客户的价格调整策略默认为关闭状态」；追问存量处理时明确选择「存量也全部关闭」。
--
-- 两个连带后果（业务方在拍板时已被告知，量化数字见 docs/RECORD.md 同日登记）：
--   1) 定时扫描不再为该客户生成价格版本 —— PriceAdjustScheduledScanService 只扫 enabled=true。
--      受影响：CUST-0001 / CUST-0729-QA，均为 DAILY 09:30（本地时区），下次本该触发的
--      slot 是 2026-08-06 16:30 UTC。手动生成版本 / 现存 PENDING 版本的审核链路均不看
--      enabled，不受影响。
--   2) 已锁定的单价列会解锁 —— 策略停用后，下次归位（PriceReconciler 的 unlockOnly 模式）
--      会撤掉该客户所有单的 __priceLocked / __priceVersion 标记，只撤锁、不改业务值。
--      精确口径（逐 jsonb 行判定，非 row_data::text ILIKE 近似查询）：全库仅 CUST-0001
--      有锁 —— 5 张单 / snapshot_rows 14 行 / row_data 11 行。
--      🔒 撤锁是惰性的：不在本迁移发生，各单等到下次 saveDraft 才执行。
--
-- 配套代码改动（缺一不可，只改本迁移不足以交付「默认关闭」）：
--   - CustomerPriceAdjustStrategy.enabled 字段初始值 true -> false（覆盖 findOrCreateStrategy 路径）
--   - PriceAdjustStrategyService#doPutStrategy 新建分支默认 FALSE（原为显式 Boolean.TRUE）
--   - 前端 PriceAdjustStrategyTab.tsx 表单默认值（前端自己填 true，会绕过后端默认值）

-- 1) DDL 默认值：此后不带 enabled 列的 INSERT 一律落 false。
ALTER TABLE customer_price_adjust_strategy ALTER COLUMN enabled SET DEFAULT false;

-- 2) 存量全部关闭。
--    SET 只动 enabled + updated_at，其余列（周期/范围/阈值/created_*）完整保留 —— 这是
--    「停用」不是「重置」，重新启用后原配置必须原样可用。
UPDATE customer_price_adjust_strategy
   SET enabled = false,
       updated_at = now()
 WHERE enabled = true;
