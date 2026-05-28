-- V266: 选配落库迁 V6 Phase 1 — material_master 加 config_fingerprint
--
-- 背景（docs/选配V6迁移诊断方案.md + AP-53 续 6）：选配落库改写 V6。
-- 选配复用判定（lookupHfByFingerprint）历史查 V44 mat_part.config_fingerprint；
-- 落库迁 material_master 后，唯一性判定需同迁到 material_master。
-- 与 V167 给 mat_part 加 config_fingerprint 同语义（partial unique 仅非空）。

ALTER TABLE material_master ADD COLUMN IF NOT EXISTS config_fingerprint VARCHAR(80);

CREATE UNIQUE INDEX IF NOT EXISTS uq_material_master_fingerprint
    ON material_master(config_fingerprint) WHERE config_fingerprint IS NOT NULL;
