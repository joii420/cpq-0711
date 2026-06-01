-- V279: 修正 row_key_fields 命名空间 — 用 driverRow 底层 key 替代 fields 中文展示名
--
-- 背景(方案 A): rowKey 从 driverRow 取值拼接对齐(草稿重刷保留编辑)。但 driverRow 的 key 是
-- 英文技术列名(material_code / element_name / process_code / child_hf_part_no), 而 V278 预填
-- 误用了 fields 的中文展示名(材质代码/元素/工序代码/子件) → 从 driverRow 按中文名取值恒 null
-- → rowKey 全空 → 草稿重刷编辑值张冠李戴/丢失(AP-54)。
--
-- 命名空间因组件而异(取决于该组件 SQL 视图产出的列名):
--   材质/元素/选配-工序列表/选配-组合工艺 → driverRow 用英文 key, 本迁移修正;
--   "工序"(5c47fb41, driverRow 用中文 key 子件/工序代码) → 保持不变, 不在本迁移;
--   "组合工艺"(4d8874c8, 当前无展开数据无法确认真实 key) → 保持不变, 待 Phase 2 有数据再验。
--
-- 列名显示/单元格值/行序均不受影响: row_key_fields 仅用于后台行身份对齐, 用户不可见。

UPDATE component SET row_key_fields = '["child_hf_part_no","material_code"]'::jsonb
WHERE id = 'ca2b5fb3-1448-40a5-be70-d3819fb27a50';  -- 材质

UPDATE component SET row_key_fields = '["child_hf_part_no","element_name"]'::jsonb
WHERE id = 'dae85db8-cf47-44df-890d-516625a598da';  -- 选配-元素含量

UPDATE component SET row_key_fields = '["child_hf_part_no","process_code"]'::jsonb
WHERE id = '0a436b6c-0b42-4381-a2da-7f901901968f';  -- 选配-工序列表

UPDATE component SET row_key_fields = '["process_code"]'::jsonb
WHERE id = '3bbde78f-718c-4544-85f2-0a25397b7eaa';  -- 选配-组合工艺
