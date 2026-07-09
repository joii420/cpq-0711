-- V316: material_master 补 production_no(生产料号)描述列。
-- repair-1 决策 A:material_master 作生产料号的权威归属(与销售料号 material_no 1:1)。
-- 不进唯一键(material_master 键仍是销售料号维度;production_no 为 1:1 描述列)。
ALTER TABLE material_master ADD COLUMN IF NOT EXISTS production_no VARCHAR(32);
