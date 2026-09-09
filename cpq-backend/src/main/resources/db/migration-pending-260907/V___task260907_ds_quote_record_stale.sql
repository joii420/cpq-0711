-- ============================================================================
-- task-260907 第二段 · D-35 —— 「`_record` 快照过期」标记表
--
-- 服务的裁决：D-35（用户 2026-09-07）「`_record` 写失败不许静默」
--
-- ⛔⛔ 本文件**尚未取迁移号、尚未应用**，落库步骤见本目录 README.md。
--
-- 【为什么是一张新表，而不是给 quotation 加列】
--   实查 `quotation` 的 4 个 jsonb 列（referenced_versions / submission_snapshot /
--   bound_global_variables_snapshot）与 remarks **各有既定语义**，塞进去属
--   `AP-52` 点名的「语义错配」。而新建一张独立小表：
--     · 不动共享主表 `quotation`（不影响任何既有实体映射、查询、快照比对）
--     · 天然支持「一张单可以有多条历史标记」的留痕
--     · 唯一索引是 partial 的 ⇒ 「未清除的标记」全局每单至多一条
--   ⇒ 侵入面比加列更小。
--
-- 【为什么不删行，而是 cleared_at 置位】
--   「这张单曾经写失败过」本身是可追溯价值。删了就只剩一条没人看的 WARN，
--   等于把 D-35 要消灭的静默换个地方重现。
-- ============================================================================

CREATE TABLE ds_quote_record_stale (
    id            bigserial      PRIMARY KEY,
    quotation_id  uuid           NOT NULL,
    reason        varchar(32)    NOT NULL,
    detail        text,
    detected_at   timestamptz    NOT NULL DEFAULT now(),
    detected_by   varchar(64),
    cleared_at    timestamptz
);

-- 一张单同时至多一条**未清除**的标记；已清除的历史行不受约束（留痕）
CREATE UNIQUE INDEX uq_ds_quote_record_stale_open
    ON ds_quote_record_stale (quotation_id) WHERE cleared_at IS NULL;
CREATE INDEX idx_ds_quote_record_stale_quotation ON ds_quote_record_stale (quotation_id);

COMMENT ON TABLE  ds_quote_record_stale IS
  '报价单 _record 快照过期标记（D-35）。写 _record 失败时留痕，下一次成功写入时置 cleared_at。'
  ' 预览必须把未清除的标记显式报给财务 —— 🚫 不许静默：'
  ' 「保存成功 → 快照没更新 → 预览从 _record 读到过期数据 → 财务照着确认 → 按错的数据回填主表」'
  ' 是 AP-60 判据四的同型形态（预览在撒谎，且撒得很有说服力）。';
COMMENT ON COLUMN ds_quote_record_stale.reason IS 'WRITE_FAILED（写 _record 抛异常）。⚠️ 受 varchar(32) 约束';
COMMENT ON COLUMN ds_quote_record_stale.detail IS '异常摘要（已截断），仅供排障，不进契约文案';
COMMENT ON COLUMN ds_quote_record_stale.cleared_at IS '非空 = 已被后续一次成功的 _record 写入清除';
