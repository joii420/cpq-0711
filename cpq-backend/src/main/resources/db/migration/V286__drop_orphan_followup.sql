-- V286: 孤儿删除 followup（2026-06-02 审计第二批，用户确认）。
-- element_density：V103 元素密度常数表，唯一消费视图 v_part_plating_scheme 已于 V285 删除，
--   V6 改用 plating_scheme.density（P21PlatingSchemeHandler 导入时写入）→ 无任何残留消费。
-- _archived_product_data_pool_v4：V49 归档表，0 行，仅 V5ChainEndToEndTest T3 断言其存在，
--   该断言已同步移除。
DROP TABLE IF EXISTS element_density;
DROP TABLE IF EXISTS _archived_product_data_pool_v4;
