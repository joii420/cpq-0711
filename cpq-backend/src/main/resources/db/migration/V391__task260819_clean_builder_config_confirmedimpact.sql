-- V391__task260819_clean_builder_config_confirmedimpact.sql
-- 清洗 D-42 扁平化副作用造成的脏数据（主线裁决，2026-08-21）。
--
-- 根因：PUT /builder 保存时把 SaveRequest（BuilderConfig 子类，多带一个 confirmedImpact 字段）
-- 整个序列化进 component_sql_view.builder_config JSONB 列；下次 GET / 按纯 BuilderConfig 反
-- 序列化会撞 "Unrecognized field confirmedImpact" 500——任何"保存后不转手写、直接刷新页面"的
-- 真实用户路径都会炸。应用层已修（BuilderService.save 落库前剥离该字段），本迁移清洗存量脏行。
--
-- 影响面：dev 库 cpq_db_0724 当前 0 行受影响（尚无人跑过 PUT /builder），test 库 cpq_db 实测
-- 12 行 builder_config 非空且全部含 confirmedImpact——用 jsonb `-` 操作符原地删 key，不影响其余字段。

UPDATE component_sql_view
   SET builder_config = builder_config - 'confirmedImpact'
 WHERE builder_config IS NOT NULL
   AND builder_config ? 'confirmedImpact';
