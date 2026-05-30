-- V270: AP-53 收尾(Part 1)— [选配-子配件清单 COMP-CFG-CHILD-PARTS] 改用 V6 表
--
-- 背景:该组件字段 BNF 原指向 mat_bom[bom_type='ASSEMBLY'].xxx(V44 已废弃),且 driver `$zcj_bom`
-- 对应的组件 SQL 视图缺失(展开报"视图未找到")。改为:
--   1) 新建组件 SQL 视图 zcj_bom,查 V6 material_bom_item(characteristic='ASSEMBLY')+ material_master;
--   2) 5 个字段 basic_data_path 重配为 $zcj_bom.xxx。
-- 子件 V6 数据由 configure 的 insertMaterialBomAssemblyV6 在加产品时写入(material_bom_item ASSEMBLY),
-- 本迁移仅建视图 + 改字段配置,不动数据。
--
-- 部署后续:改了 component.fields,需对该组件跑一次模板快照刷新,把新字段配置传播到各模板快照:
--   POST /api/cpq/components/c3ad18a5-2cd8-424e-b702-ea26f3a48cee/refresh-template-snapshots
-- (视图按名实时解析,故视图本身无需刷快照;但字段 basic_data_path 冻在 components_snapshot 里,必须刷。)

-- 1) 建 zcj_bom 组件 SQL 视图(幂等)
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns, required_variables, scope, status, created_at, updated_at)
SELECT gen_random_uuid(), 'c3ad18a5-2cd8-424e-b702-ea26f3a48cee', 'zcj_bom',
$V6$SELECT
    asy.material_no  AS hf_part_no,
    asy.component_no AS child_material_no,
    COALESCE(mm.material_name, asy.component_no) AS child_part_name,
    asy.seq_no       AS seq_no,
    asy.composition_qty AS qty,
    asy.issue_unit   AS unit
FROM material_bom_item asy
LEFT JOIN material_master mm ON mm.material_no = asy.component_no
WHERE asy.system_type = 'QUOTE'
  AND asy.characteristic = 'ASSEMBLY'
  AND asy.customer_no = :customerCode$V6$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"child_material_no","dataType":"varchar","nullable":true},{"name":"child_part_name","dataType":"varchar","nullable":true},{"name":"seq_no","dataType":"int4","nullable":true},{"name":"qty","dataType":"numeric","nullable":true},{"name":"unit","dataType":"varchar","nullable":true}]'::jsonb,
    '{}'::text[], 'GLOBAL', 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM component_sql_view WHERE sql_view_name = 'zcj_bom');

-- 2) 重配 [选配-子配件清单] 字段 BNF:mat_bom[bom_type='ASSEMBLY'].xxx → $zcj_bom.xxx
UPDATE component
SET fields = $FIELDS$[
  {"name":"序号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"$zcj_bom.seq_no"},
  {"name":"子料号","notes":"父→子: component_no 存子件 hf_part_no","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"$zcj_bom.child_material_no"},
  {"name":"子料号名称","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"$zcj_bom.child_part_name"},
  {"name":"数量","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"$zcj_bom.qty"},
  {"name":"单位","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"$zcj_bom.unit"}
]$FIELDS$::jsonb,
    updated_at = NOW()
WHERE id = 'c3ad18a5-2cd8-424e-b702-ea26f3a48cee';
