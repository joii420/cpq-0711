-- V300: 物料BOM 子表新增材料毛重/净重/重量单位专用列
-- 关联: docs/superpowers/specs/2026-06-17-material-bom-weight-fields-design.md
-- 背景: 原先这三列借住 composition_qty/base_qty/issue_unit，本次改存专用列。
ALTER TABLE material_bom_item
    ADD COLUMN IF NOT EXISTS rough_weight DECIMAL(18,6),
    ADD COLUMN IF NOT EXISTS net_weight   DECIMAL(18,6),
    ADD COLUMN IF NOT EXISTS weight_unit  VARCHAR(20);

COMMENT ON COLUMN material_bom_item.rough_weight IS '材料毛重（物料BOM Sheet，V300 前借住 composition_qty）';
COMMENT ON COLUMN material_bom_item.net_weight   IS '材料净重（物料BOM Sheet，V300 前借住 base_qty）';
COMMENT ON COLUMN material_bom_item.weight_unit  IS '重量单位（物料BOM Sheet，V300 前借住 issue_unit）';
