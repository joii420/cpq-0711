\set ON_ERROR_STOP on
\encoding UTF8

\echo '==== 1. component 表 CHILD-PARTS 子料号名称 字段当前配置 ===='
SELECT f->>'name' AS field, f->>'field_type' AS ftype, f->>'basic_data_path' AS path,
       f->>'default_source' AS default_source, f->>'content' AS content
FROM component c, LATERAL jsonb_array_elements(c.fields) f
WHERE c.code='COMP-CFG-CHILD-PARTS';

\echo '==== 2. 各 PUBLISHED 模板 snapshot 里 CHILD-PARTS 子料号名称 字段 ===='
SELECT t.version,
       f->>'field_type' AS ftype, f->>'basic_data_path' AS path
FROM template t,
  LATERAL jsonb_array_elements(t.components_snapshot) comp,
  LATERAL jsonb_array_elements(comp->'fields') f
WHERE t.status='PUBLISHED'
  AND comp->>'componentCode'='COMP-CFG-CHILD-PARTS'
  AND f->>'name'='子料号名称'
ORDER BY t.version;

\echo '==== 3. QT-1651 用 v1.31, 该字段在 snapshot 中 ===='
SELECT f->>'field_type' AS ftype, f->>'basic_data_path' AS path
FROM template t,
  LATERAL jsonb_array_elements(t.components_snapshot) comp,
  LATERAL jsonb_array_elements(comp->'fields') f
WHERE t.id='2ee8cd64-6606-445b-b6c2-c25efc35ca7e'
  AND comp->>'componentCode'='COMP-CFG-CHILD-PARTS'
  AND f->>'name'='子料号名称';

\echo '==== 4. QT-1651 CHILD-PARTS 持久化 rowData ===='
SELECT li.product_part_no_snapshot, cd.row_data
FROM quotation_line_component_data cd
JOIN component c ON c.id=cd.component_id
JOIN quotation_line_item li ON li.id=cd.line_item_id
WHERE li.quotation_id='2ab989bb-2a91-43b0-832a-81e2de48433a' AND c.code='COMP-CFG-CHILD-PARTS';
