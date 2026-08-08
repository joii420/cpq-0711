-- repair-0807 FR-4：SKIPPED 成为 job item 独立终态（原先被 PriceAdjustJobExecutionService#executeItem
-- 静默并入 SUCCESS，掩盖"该单一个字节都没被更新"这一事实——32/32 全成功的批次实际有单据被静默跳过）。
--
-- 不回填存量：历史被静默跳过的 item 现在记的是 SUCCESS，不重跑无从判定，属 BL-0148 存量范围。

ALTER TABLE material_price_update_job_item DROP CONSTRAINT chk_mpuji_status;
ALTER TABLE material_price_update_job_item ADD CONSTRAINT chk_mpuji_status
  CHECK (status::text = ANY (ARRAY[
    'WAITING','RUNNING','SUCCESS','FAILED','CONFLICT','STALE','SKIPPED'
  ]::text[]));

ALTER TABLE material_price_update_job
  ADD COLUMN IF NOT EXISTS skipped_count integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN material_price_update_job.skipped_count IS
    '本批次 job item 里 SKIPPED 终态的数量（repair-0807 FR-4）。有跳过时批次 status 不得报 SUCCESS，只能是 PARTIAL——MaterialPriceUpdateJob#recountFrom 唯一实现该判定。';
