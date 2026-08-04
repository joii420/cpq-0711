-- task-0729 · api.md §6.1 系统参数（L3 升版口径守卫阈值 E14-11，验收 #70④「阈值可配且即时生效」）
--
-- 单行表（id 恒为 1）：本期只有一个可配参数，不做 key-value 通用参数表——通用表会让每次读都要
-- 做一次字符串解析 + 类型转换，且失去 NUMERIC 的量纲约束。后续再加参数时直接加列即可。
--
-- 🔒 不做进程级缓存（服务层每次读库）：验收 #70④ 明确要求「不需要重启服务」即时生效。

CREATE TABLE IF NOT EXISTS price_adjust_settings (
    id                       SMALLINT      NOT NULL DEFAULT 1,
    -- L3 口径守卫阈值（金额，元）。|后端旧价重算 − li.subtotal| > 本值 → 该单标 FAILED/SUBTOTAL_MISMATCH 不写回
    subtotal_guard_threshold NUMERIC(20, 6) NOT NULL DEFAULT 0.01,
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by               UUID,
    CONSTRAINT pk_price_adjust_settings PRIMARY KEY (id),
    -- 单行约束：任何 INSERT 第二行都会被拒，服务层因此可以无条件 findById(1)
    CONSTRAINT chk_pas_singleton CHECK (id = 1),
    -- 阈值必须非负（负阈值会让守卫恒不触发，等于静默关闭 L3）
    CONSTRAINT chk_pas_threshold_nonneg CHECK (subtotal_guard_threshold >= 0)
);

-- 种子行：与 MaterialVersionUpgradeService.DEFAULT_SUBTOTAL_GUARD_THRESHOLD 同值（0.01），
-- 保证迁移前后 L3 守卫行为逐字节不变（本次改动只让它「可配」，不改默认口径）。
INSERT INTO price_adjust_settings (id, subtotal_guard_threshold)
VALUES (1, 0.01)
ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE  price_adjust_settings                          IS 'task-0729 调价系统参数（单行，id=1）· api.md §6.1';
COMMENT ON COLUMN price_adjust_settings.subtotal_guard_threshold IS 'L3 升版口径守卫阈值（金额/元，E14-11）。默认 0.01，仅 SYSTEM_ADMIN 可改，即时生效';
