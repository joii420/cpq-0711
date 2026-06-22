-- V303: 加长 component_usage_type 列 varchar(20) → varchar(100)
-- 背景: 报价物料BOM 导入「产出料号类型」实际会出现材料牌号/规格长串
--   (如 'AgNi10-(QSn6.5-0.1)-H65铆接件' = 26 字符) 撑爆原 varchar(20)。
-- 关联: MaterialBomMergeHandler 写 material_bom_item.component_usage_type;
--   element_bom_item 同名列一并加长保持一致(防同类潜在溢出)。
-- 视图依赖: material_bom_item.component_usage_type 被 v_composite_child_materials 引用,
--   PG 不允许直接 ALTER TYPE → 必须先 DROP 视图、ALTER、再原样重建
--   (视图定义取自当前 DB pg_get_viewdef,与 V238/V280 终态一致)。

DROP VIEW IF EXISTS v_composite_child_materials;

ALTER TABLE material_bom_item ALTER COLUMN component_usage_type TYPE varchar(100);
ALTER TABLE element_bom_item  ALTER COLUMN component_usage_type TYPE varchar(100);

CREATE VIEW v_composite_child_materials AS
 SELECT asy.material_no AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no AS child_seq,
    NULL::uuid AS recipe_id,
    asy.component_no AS material_code,
    NULL::character varying AS chemical_symbol,
    COALESCE(asy.component_usage_type, mm.material_type, mm.material_name) AS material_name,
    COALESCE(mm.specification, asy.component_usage_type) AS spec_label,
    asy.component_usage_type AS recipe_type,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM material_bom_item asy
     LEFT JOIN material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text AND asy.characteristic IS NULL AND asy.is_current = true
UNION ALL
 SELECT mm.material_no AS hf_part_no,
    mm.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, mm.material_no) AS child_part_name,
    0 AS child_seq,
    NULL::uuid AS recipe_id,
    NULL::character varying AS material_code,
    NULL::character varying AS chemical_symbol,
    COALESCE(mm.material_type, mm.material_name) AS material_name,
    mm.specification AS spec_label,
    mm.material_type AS recipe_type,
    NULL::uuid AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM material_master mm
  WHERE NOT (EXISTS ( SELECT 1
           FROM material_bom_item asy2
          WHERE asy2.system_type::text = 'QUOTE'::text AND asy2.characteristic IS NULL AND asy2.is_current = true AND asy2.component_no::text = mm.material_no::text));
