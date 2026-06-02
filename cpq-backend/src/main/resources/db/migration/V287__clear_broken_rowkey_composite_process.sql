-- V287: 清空「选配-组合工艺」组件 (3bbde78f) 的坏行键 row_key_fields。
--
-- 背景(2026-06-02 行键字段勾选改造 live 验收发现):
--   该组件 data_driver_path = $composite_process_mirror，但其 row_key_fields = ["process_code"]，
--   而 process_code **不在** $composite_process_mirror 的 declared_columns 里
--   (视图列: hf_part_no/seq_no/def_code/def_name/participating_parts/param_values)。
--   → driverRow.get("process_code") 恒 null → 行键拼接恒空 → 草稿重刷退化为按行号对齐(等同未配)。
--   这是 V279 命名空间修正时对该组件的遗留误配(非补字段可解，列本身不存在)。
--
-- 处置(用户确认): 暂时清空该组件行键 → 显式回到"按行号对齐"，消除"配了却恒空"的误导态。
--   后续若需精确行身份，再依该 mirror 真实列(如 def_code / seq_no)重配，单独立项。
--
-- 仅清这一个组件，不动其它组件的 row_key_fields。

UPDATE component
SET    row_key_fields = NULL
WHERE  id = '3bbde78f-718c-4544-85f2-0a25397b7eaa'   -- 选配-组合工艺
  AND  row_key_fields = '["process_code"]'::jsonb;     -- 防御: 仅当仍是该坏值时才清，避免覆盖后续重配
