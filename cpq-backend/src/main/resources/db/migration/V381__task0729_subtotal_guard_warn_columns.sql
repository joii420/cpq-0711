-- task-0729 · 方向3 T2：L3 SUBTOTAL 口径守卫「拦截 → 告警」
--
-- 背景：方向3 T1（49e540c6）把 li.subtotal 收敛为「卡片值算完即覆盖」的单一来源后，
-- MaterialVersionUpgradeService 的 S0 守卫比较的两边（后端旧价重算 vs li.subtotal）趋于恒等
-- → 守卫代码还是拦截语义，但已经拦不到东西。「看起来有守卫、实际不报警」比没有守卫更危险。
--
-- 改造：守卫保留发现能力、去掉阻塞 —— 差异 > 阈值不再中断升版，改为记 WARN 日志 + 落可查记录。
-- 阈值仍读 price_adjust_settings.subtotalGuardThreshold（E14-11 可配项，验收 #70④）。
--
-- 记录落点：刻意【不新建独立告警表】—— 那会是第四张没人看的表。告警要有人看见才有价值，
-- 故落在用户已经在看的两行上：
--   dryRun（预算预览，高频侦测路径）   → material_price_review        → 屏 4
--   非 dryRun（批次执行，低频执行路径） → material_price_update_job_item → 屏 7
--
-- 为什么新增 warn_* 而不复用 error_code/error_message：
--   现有语义是「error_code 非空 = 非成功态」。告警行的 status 仍是 SUCCESS/READY，
--   复用会产出「status=SUCCESS 却带 error_code」的行，让所有消费点都要重新判断
--   「这是真失败还是告警」，屏 7 的「可重试」判定会被直接带偏（= 新 bug）。
--   diff_value 不新增 —— material_price_update_job_item 上早已存在且语义相同（守卫 diff）。

ALTER TABLE material_price_review
    ADD COLUMN IF NOT EXISTS warn_code    VARCHAR(50),
    ADD COLUMN IF NOT EXISTS warn_message TEXT,
    ADD COLUMN IF NOT EXISTS warn_diff    NUMERIC(20, 6);

COMMENT ON COLUMN material_price_review.warn_code IS
    'L3 口径守卫告警码（如 SUBTOTAL_MISMATCH）；非空 = 本次预算期检出前后端算值分叉，但【未阻断】，budget_status 仍可为 READY';
COMMENT ON COLUMN material_price_review.warn_message IS 'L3 口径守卫告警原文（含两侧数值、差异、阈值）';
COMMENT ON COLUMN material_price_review.warn_diff IS 'L3 口径守卫检出的差异绝对值 |后端旧价重算 - li.subtotal|';

ALTER TABLE material_price_update_job_item
    ADD COLUMN IF NOT EXISTS warn_code    VARCHAR(50),
    ADD COLUMN IF NOT EXISTS warn_message TEXT;

COMMENT ON COLUMN material_price_update_job_item.warn_code IS
    'L3 口径守卫告警码（如 SUBTOTAL_MISMATCH）；非空 = 升版时检出前后端算值分叉，但【未阻断】，status 仍为 SUCCESS。差异值见既有 diff_value 列';
COMMENT ON COLUMN material_price_update_job_item.warn_message IS 'L3 口径守卫告警原文（含两侧数值、差异、阈值）';

-- 可查性：按告警码 + 时间倒序检索「最近哪些单据出现过双端分叉」
CREATE INDEX IF NOT EXISTS idx_mpr_warn_code
    ON material_price_review (warn_code, updated_at DESC)
    WHERE warn_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mpuji_warn_code
    ON material_price_update_job_item (warn_code, updated_at DESC)
    WHERE warn_code IS NOT NULL;
