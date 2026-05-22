-- V205: template_component 加 data_driver_path_composite 列 (2026-05-20)
--
-- 背景:
--   V200 双轨方案在模板字段层加了 basic_data_path_composite (按 lineItem.compositeType 切换 path),
--   但 driver 层面 _composite 路径仅存在于 components_snapshot.entry.data_driver_path_composite
--   — 没有源头列, 导致 publish() / refreshSnapshotsByComponent() 重建 snapshot 时丢失.
--
-- 修法: 加列 data_driver_path_composite TEXT (类比 data_driver_path_override).
--   publish + createNewDraft + refreshSnapshotsByComponent + admin patch-composite 都把它写入 snapshot.entry.data_driver_path_composite,
--   前端 effectiveDriverAndFields() 已按 lineItem.compositeType 选用.
--
-- 兼容: 旧 PUBLISHED 模板该列为 NULL → effectiveDriverPath 行为完全等价 (fallback 到 component.dataDriverPath).

ALTER TABLE template_component
    ADD COLUMN IF NOT EXISTS data_driver_path_composite TEXT;

COMMENT ON COLUMN template_component.data_driver_path_composite IS
    'V205: 模板级 COMPOSITE 视角 driver_path. 非 NULL 时 publish/refresh 时写入 snapshot.entry.data_driver_path_composite. 组合产品视角(lineItem.compositeType=COMPOSITE)优先使用; SIMPLE 视角仍走 dataDriverPathOverride / component.dataDriverPath.';
