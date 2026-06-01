-- V280: 报价侧 v_composite_child_* 视图加 is_current=TRUE 过滤（Task 9b，worklist IN-scope PG 视图 4 个）
--
-- 背景：报价导入版本化后（VersionedV6Writer），unit_price/element_bom_item 等表二次导入升版会保留
--   is_current=false 旧版本行。读这些表的 v_composite_child_* 渲染视图若不过滤 is_current，会在升版后返重复行。
-- 机制：仅追加 WHERE 谓词、不改输出列 → 用 CREATE OR REPLACE VIEW（不 DROP CASCADE，避免级联重建 + 缓存风险）。
-- 注入：版本化表(element_bom_item/material_bom_item/unit_price)均为 INNER FROM → is_current 进 WHERE。
--   - element_bom_item / unit_price：多版本保留 → 必需。
--   - material_bom_item：Q03/Q12 deleteNonCurrent 只留当前 → 防御性（按设计 §6 统一加）。
-- 注：elements 视图原用 characteristic=max(...) 选最新版，与 is_current=true 等价；保留 max 逻辑 + 加 is_current 双保险。
-- 部署：本文件落 db/migration/ 后 touch 一个 java 文件触发 Quarkus 重启跑 Flyway（CLAUDE.md DDL 缓存纪律）。

-- ① v_composite_child_elements（element_bom_item，必需）
CREATE OR REPLACE VIEW v_composite_child_elements AS
 SELECT ebi.hf_part_no,
    ebi.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, ebi.material_no) AS child_part_name,
    0 AS child_seq,
    ebi.seq_no,
    ebi.component_no AS element_name,
    ebi.content AS composition_pct,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM element_bom_item ebi
     LEFT JOIN material_master mm ON mm.material_no::text = ebi.material_no::text
     LEFT JOIN customer c ON c.code::text = ebi.customer_no::text
  WHERE ebi.system_type::text = 'QUOTE'::text AND ebi.hf_part_no IS NOT NULL
    AND ebi.is_current = true
    AND ebi.characteristic::text = (( SELECT max(ebi2.characteristic::text) AS max
           FROM element_bom_item ebi2
          WHERE ebi2.system_type::text = ebi.system_type::text AND ebi2.customer_no::text = ebi.customer_no::text AND ebi2.material_no::text = ebi.material_no::text));

-- ② v_composite_child_materials（material_bom_item，防御性）
CREATE OR REPLACE VIEW v_composite_child_materials AS
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
  WHERE asy.system_type::text = 'QUOTE'::text AND asy.characteristic IS NULL
    AND asy.is_current = true
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

-- ③ v_composite_child_processes（unit_price 必需 + material_bom_item 防御性）
CREATE OR REPLACE VIEW v_composite_child_processes AS
 SELECT up.finished_material_no AS hf_part_no,
    up.finished_material_no AS child_hf_part_no,
    COALESCE(mm.material_name, up.finished_material_no) AS child_part_name,
    0 AS child_seq,
    row_number() OVER (PARTITION BY up.finished_material_no, c.id ORDER BY up.operation_no) AS seq_no,
    up.operation_no AS process_code,
    COALESCE(pm.process_name, up.operation_no) AS assembly_process,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM ( SELECT DISTINCT unit_price.customer_no,
            unit_price.finished_material_no,
            unit_price.operation_no
           FROM unit_price
          WHERE unit_price.system_type::text = 'QUOTE'::text AND unit_price.is_current = true AND (unit_price.cost_type::text = ANY (ARRAY['自制加工费'::character varying::text, '组装加工费'::character varying::text, '来料加工费'::character varying::text])) AND unit_price.operation_no IS NOT NULL AND unit_price.finished_material_no IS NOT NULL) up
     LEFT JOIN material_master mm ON mm.material_no::text = up.finished_material_no::text
     LEFT JOIN process_master pm ON pm.process_no::text = up.operation_no::text
     LEFT JOIN customer c ON c.code::text = up.customer_no::text
UNION ALL
 SELECT asy.material_no AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no AS child_seq,
    row_number() OVER (PARTITION BY asy.material_no, c.id, asy.component_no ORDER BY asy.seq_no, asy.operation_no) AS seq_no,
    asy.operation_no AS process_code,
    COALESCE(pm.process_name, asy.operation_no) AS assembly_process,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM material_bom_item asy
     LEFT JOIN material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN process_master pm ON pm.process_no::text = asy.operation_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text AND asy.characteristic::text = 'ASSEMBLY'::text AND asy.is_current = true AND asy.operation_no IS NOT NULL;

-- ④ v_composite_child_weights（material_bom_item，防御性）
CREATE OR REPLACE VIEW v_composite_child_weights AS
 SELECT asy.material_no AS hf_part_no,
    asy.component_no AS child_hf_part_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no AS child_seq,
    mm.unit_weight,
    'g'::text AS unit_label,
    c.id AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM material_bom_item asy
     LEFT JOIN material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text AND asy.characteristic::text = 'ASSEMBLY'::text AND asy.is_current = true
UNION ALL
 SELECT mm.material_no AS hf_part_no,
    mm.material_no AS child_hf_part_no,
    COALESCE(mm.material_name, mm.material_no) AS child_part_name,
    0 AS child_seq,
    mm.unit_weight,
    'g'::text AS unit_label,
    NULL::uuid AS customer_id,
    NULL::uuid AS quotation_line_item_id
   FROM material_master mm
  WHERE NOT (EXISTS ( SELECT 1
           FROM material_bom_item asy2
          WHERE asy2.system_type::text = 'QUOTE'::text AND asy2.characteristic::text = 'ASSEMBLY'::text AND asy2.is_current = true AND asy2.material_no::text = mm.material_no::text));
