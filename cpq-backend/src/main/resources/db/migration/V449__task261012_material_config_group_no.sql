-- task-261012: 数据库组号与创建顺序分离。Excel 组号只做文件内分组。
ALTER TABLE material_recipe_config ADD COLUMN IF NOT EXISTS group_no integer;

UPDATE material_recipe_config
   SET group_no = seq
 WHERE group_no IS NULL;

ALTER TABLE material_recipe_config ALTER COLUMN group_no SET NOT NULL;
ALTER TABLE material_recipe_config
  ADD CONSTRAINT uq_mrc_recipe_group_no UNIQUE (recipe_id, group_no);

COMMENT ON COLUMN material_recipe_config.group_no IS
  'task-261012：材质历史配置组号，含 INACTIVE，编号不回收；Excel 组号不落库';
