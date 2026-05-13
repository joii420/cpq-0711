-- V172__seed_composite_process_def.sql
-- 6 个组合工艺字典 seed (对齐 docs/html/添加产品.html 原型 comboProcs 数组)

INSERT INTO composite_process_def (code, name, icon, description, param_schema, sort_order) VALUES
  ('RIVET', '铆接', '🔩', '将两个配件通过铆钉压接固定',
   '[{"id":"pressure","label":"铆接压力","unit":"kN","type":"number","placeholder":"如 5.0"},
     {"id":"height","label":"铆钉高度","unit":"mm","type":"number","placeholder":"如 3.2"}]'::jsonb, 10),
  ('RESISTANCE_WELD', '电阻焊', '⚡', '通过电阻加热实现配件熔合',
   '[{"id":"current","label":"焊接电流","unit":"kA","type":"number","placeholder":"如 8.0"},
     {"id":"time","label":"焊接时间","unit":"ms","type":"number","placeholder":"如 80"}]'::jsonb, 20),
  ('LASER_WELD', '激光焊', '🔆', '使用激光束对配件进行精密焊接',
   '[{"id":"power","label":"激光功率","unit":"W","type":"number","placeholder":"如 200"},
     {"id":"speed","label":"焊接速度","unit":"mm/s","type":"number","placeholder":"如 50"}]'::jsonb, 30),
  ('BRAZING', '钎焊', '🔥', '使用钎料在低于母材熔点下连接配件',
   '[{"id":"temp","label":"钎焊温度","unit":"°C","type":"number","placeholder":"如 650"},
     {"id":"material","label":"钎料材质","unit":"","type":"text","placeholder":"如 银基钎料"}]'::jsonb, 40),
  ('ULTRASONIC_WELD', '超声波焊接', '〰️', '利用超声波振动将配件熔合',
   '[{"id":"amplitude","label":"振幅","unit":"μm","type":"number","placeholder":"如 30"},
     {"id":"weld_time","label":"焊接时间","unit":"ms","type":"number","placeholder":"如 500"}]'::jsonb, 50),
  ('PRESS_FIT', '压配合', '🗜️', '通过过盈配合将配件压入固定',
   '[{"id":"force","label":"压入力","unit":"kN","type":"number","placeholder":"如 12"},
     {"id":"fit","label":"配合公差","unit":"","type":"text","placeholder":"如 H7/r6"}]'::jsonb, 60)
ON CONFLICT (code) DO NOTHING;
