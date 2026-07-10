-- V321__material_recipe_name_default_symbol.sql
-- task-0708 · repair-1：材质 name/symbol 语义修补。
-- task-0708 导入把 name 置 NULL；repair-1 语义为 name(材质名称) 默认=化学式(symbol)，回填存量。
-- 只填 NULL 的，不动已有 name（若有人工填过）。
UPDATE material_recipe SET name = symbol, updated_at = NOW()
WHERE name IS NULL;
