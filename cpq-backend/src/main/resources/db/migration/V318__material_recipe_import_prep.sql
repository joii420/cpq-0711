-- V318__material_recipe_import_prep.sql（随 V317 element_master 避让并发 V316 后顺延）
-- 材质库真实数据导入前的两项准备（task-0708 · B2）。

-- 1) 清理 V171 注入的 12 条 demo seed 材质(code 为符号如 AgCu85)，为真实材质库(code=材质编号 00001…)让路。
--    FK 安全: material_recipe_element ON DELETE CASCADE(V164);
--            material_master.material_recipe_id ON DELETE SET NULL(V265)。当前该批 0 料号绑定，删除零副作用。
--    只删这 12 个已知 seed code，不 TRUNCATE 全表(防误删他人手工数据)。
DELETE FROM material_recipe
WHERE code IN ('AgCu85','AgCu90','AgNi90','AgNi95','AgSnO2','AgSnO2b',
               'AgCdO','AgW60','AgW72','CuCr','AgPd','AuAg');

-- 2) material_recipe.name / spec_label 改可空。
--    决策#2: 导入/新建把 name、spec_label 置 NULL(UI 隐藏、DB 列保留供下游 COALESCE(mr.name…) 引用)。
--    V164 原建表 name 为 NOT NULL，不放开会让"导入置 NULL"违反非空约束。spec_label 本就可空，此处仅补 name。
ALTER TABLE material_recipe ALTER COLUMN name DROP NOT NULL;
