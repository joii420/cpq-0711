-- task-0806 · T6 · FR-9 / D-5：S0 L3 口径守卫可配开关（api.md §1/§2，需求文档 §5.1）
--
-- 背景：MaterialVersionUpgradeService#upgrade 的 S0 段每项花 0.46s（14.3%）重算整卡做口径比对，
-- 实测 123 个 job item 只响过 1 次（0.8%）——且方向3 T2（2026-08-06）已把它从"拦截"改成"告警"，
-- 两边算值趋于恒等后它已经"拦不到东西了"。D-5 拍板：改可配开关，**默认关闭**（性能优先），
-- 但保留随时打开的能力（业务方担心"双端算值分叉却无人察觉"时可以重新打开）。
--
-- 🔒 与 subtotal_guard_threshold 同表同行为准则：不做进程级缓存，服务层每次读库，
-- PUT /price-adjust/settings 后即时生效、不需要重启（api.md §2 沿用 task-0729 验收 #70④ 口径）。

ALTER TABLE price_adjust_settings
    ADD COLUMN IF NOT EXISTS subtotal_guard_enabled BOOLEAN NOT NULL DEFAULT false;

-- 🔒 COMMENT ON 只接受字面量字符串，不接受表达式（'a' || 'b' 会 42601 语法错误），故单条写完
COMMENT ON COLUMN price_adjust_settings.subtotal_guard_enabled IS
    'S0 L3 口径守卫总开关（task-0806 FR-9）。false（默认）= 升版时跳过 S0 旧价重算；true = 每项都跑守卫，行为与该开关引入前逐位一致（含 warn_code/warn_message/diff_value 落库）。仅 SYSTEM_ADMIN 可改，即时生效';
