-- V299: 删除废弃的 driver 路径级双轨残留列 template_component.data_driver_path_composite
--
-- 背景: 双轨方案 (V200/V205) 已于 2026-05-21 被「统一智能视图路径方案」取代。
--   composite driver 路径统一折叠进 data_driver_path_override + 自适应 v_composite_child_* 视图。
--   data_driver_path_composite 列在渲染/展开期无任何读取 (ComponentDriverService 只读 override),
--   迁移后全表为 null, 仅由已废弃并已删除的 admin patch-composite 端点写入。
--
-- 安全兜底: DROP 前把任何残留非空值回填到 data_driver_path_override
--   (防 migrate-to-unified-view 在某环境未跑过导致数据丢失)。

UPDATE template_component
   SET data_driver_path_override = data_driver_path_composite
 WHERE data_driver_path_composite IS NOT NULL
   AND btrim(data_driver_path_composite) <> ''
   AND (data_driver_path_override IS NULL OR btrim(data_driver_path_override) = '');

ALTER TABLE template_component DROP COLUMN IF EXISTS data_driver_path_composite;
