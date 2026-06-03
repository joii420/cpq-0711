-- V293: material_bom_item 加 bom_version 版本列，子表多版本保留（对齐 material_bom 主表）
-- 设计: docs/superpowers/specs/2026-06-04-material_bom_item-版本化-design.md
-- 版本作用域仍 per-(料号, characteristic)；is_current 由 V277 已加。

-- 1) 加列（可空，容纳存量行）
ALTER TABLE material_bom_item ADD COLUMN IF NOT EXISTS bom_version VARCHAR(20);

-- 2) 重建唯一索引：版本维度并入 uq
DROP INDEX IF EXISTS uq_material_bom_item;
CREATE UNIQUE INDEX uq_material_bom_item ON material_bom_item(
    system_type, customer_no, material_no,
    COALESCE(characteristic,''),
    COALESCE(bom_version,''),
    COALESCE(seq_no,0),
    COALESCE(component_no,''),
    COALESCE(part_no,'')
);

-- 3) 存量当前行一次性对齐（不补历史）：子行 bom_version = 对应 master 当前 bom_version
UPDATE material_bom_item ci
SET bom_version = m.bom_version
FROM material_bom m
WHERE m.is_current = TRUE
  AND m.system_type = ci.system_type
  AND m.customer_no = ci.customer_no
  AND m.material_no = ci.material_no
  AND m.characteristic IS NOT DISTINCT FROM ci.characteristic
  AND ci.is_current = TRUE
  AND ci.bom_version IS NULL;

COMMENT ON COLUMN material_bom_item.bom_version IS 'V293 子表版本号，对齐 material_bom.bom_version，多版本保留 + is_current 标当前';
