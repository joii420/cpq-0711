-- V265: 材质字典绑定迁 V6
--
-- 背景（AP-53 续 5）：材质配方字典 material_recipe / material_recipe_element 本身未被废弃，
-- 但"料号 → 配方"的绑定关系历史上挂在已废弃的 V44 mat_part.material_recipe_id 上。
-- 选配 Step2 取数 (MaterialRecipeService.getForExistingPart) 迁 V6 后只读 material_master +
-- element_bom_item，看不到 V44 绑定 → 已绑 AgCu90 的料号在选配里仍显示"未绑定材质字典"。
--
-- 本迁移把绑定关系下沉到 V6 料号主表 material_master，并从 mat_part 回填现有绑定。
-- material_recipe 字典保留（非 AP-53 废弃表）。

-- 1. V6-native 绑定列（FK → material_recipe，字典软删/硬删时置 NULL）
ALTER TABLE material_master ADD COLUMN IF NOT EXISTS material_recipe_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_material_master_recipe'
          AND table_name = 'material_master'
    ) THEN
        ALTER TABLE material_master
            ADD CONSTRAINT fk_material_master_recipe
            FOREIGN KEY (material_recipe_id) REFERENCES material_recipe(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_material_master_recipe ON material_master(material_recipe_id);

-- 2. 从 V44 mat_part 回填（material_no = part_no 对齐）。
--    当前可映射 2 条（含 3120012574 → AgCu90 / 0fd5ceb3-8971-43de-a353-2ee62b3f5ba6）；
--    其余 V44 绑定对应的料号尚未进 material_master，待 V6 基础数据导入补齐后由管理页重新绑定。
UPDATE material_master mm
   SET material_recipe_id = mp.material_recipe_id,
       updated_at = NOW()
  FROM mat_part mp
 WHERE mp.part_no = mm.material_no
   AND mp.material_recipe_id IS NOT NULL
   AND mm.material_recipe_id IS NULL;
