-- tooling_cost 唯一索引补 system_type + calc_version 维度（tesk-0709 Task 5 前置修复）。
-- 背景：V323 给 tooling_cost 加了 calc_version/system_type 列，但旧唯一索引 uq_tooling_cost 仍是
-- (material_no, process_no, seq_no, tooling_no)，不含 calc_version。P12 改整批版本化写入后，同料号
-- 同工序同项次同模具编号在跨版本时（旧版本 is_current=false + 新版本 is_current=true 同时存在）
-- 会撞旧唯一索引（已用手工 INSERT 复现验证：BEGIN...ROLLBACK 内两条仅 calc_version 不同的行报
-- duplicate key value violates unique constraint "uq_tooling_cost"）。
-- 新索引维度对齐 VersionedV6Writer 的 SYSTEM_TYPE_SCOPED 契约：groupKey={system_type, material_no}，
-- process_no/seq_no/tooling_no 属组内区分多行的 content 列。

DROP INDEX IF EXISTS uq_tooling_cost;

CREATE UNIQUE INDEX uq_tooling_cost ON tooling_cost (
    system_type,
    material_no,
    process_no,
    seq_no,
    tooling_no,
    COALESCE(calc_version, '')
);
