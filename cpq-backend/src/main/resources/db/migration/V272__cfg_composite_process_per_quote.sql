-- V272: AP-53 收尾(Part 2)— [选配-组合工艺 COMP-CFG-COMPOSITE-PROC] 改 per-quote + V6
--
-- 背景:该组件原 driver 借用工序 mirror($$COMP-CFG-PROCESS.composite_child_processes_mirror),
-- 字段 BNF 指向 mat_composite_process.xxx(V44/mat_ 已废弃)。组合工艺实例数据 V6 无落点,
-- 经确认改为 per-quote:新表 quotation_line_composite_process(按报价行存),
-- 由 configure/saveDraft 写入(同 quotation_line_process 的 per-quote 存活机制),
-- 新组件 SQL 视图 composite_process_mirror 按 :lineItemId 读 + LEFT JOIN composite_process_def 取工艺名。
--
-- 部署后续:改了 component.fields,需对该组件刷模板快照:
--   POST /api/cpq/components/3bbde78f-718c-4544-85f2-0a25397b7eaa/refresh-template-snapshots

-- 1) per-quote 组合工艺表(line 删除级联,与 quotation_line_process 一致)
CREATE TABLE IF NOT EXISTS quotation_line_composite_process (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    line_item_id        uuid NOT NULL REFERENCES quotation_line_item(id) ON DELETE CASCADE,
    def_code            varchar(50) NOT NULL,
    seq_no              integer,
    participating_parts jsonb,
    param_values        jsonb,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_qlcp_line_item ON quotation_line_composite_process(line_item_id);

COMMENT ON TABLE quotation_line_composite_process IS
    '选配-组合工艺 per-quote 实例(按报价行存):line_item × 组合工艺步骤。configure/saveDraft 写入,composite_process_mirror 按 :lineItemId 读渲染。取代废弃的 mat_composite_process。';

-- 2) 组件 SQL 视图 composite_process_mirror(owned by 组合工艺组件 3bbde78f)
INSERT INTO component_sql_view
    (id, component_id, sql_view_name, sql_template, declared_columns, required_variables, scope, status, created_at, updated_at)
SELECT gen_random_uuid(), '3bbde78f-718c-4544-85f2-0a25397b7eaa', 'composite_process_mirror',
$V6$SELECT
    qli.product_part_no_snapshot AS hf_part_no,
    qcp.seq_no                   AS seq_no,
    qcp.def_code                 AS def_code,
    COALESCE(d.name, qcp.def_code) AS def_name,
    qcp.participating_parts::text AS participating_parts,
    qcp.param_values::text        AS param_values
FROM quotation_line_composite_process qcp
JOIN quotation_line_item qli ON qli.id = qcp.line_item_id
LEFT JOIN composite_process_def d ON d.code = qcp.def_code
WHERE qcp.line_item_id = :lineItemId
ORDER BY qcp.seq_no$V6$,
    '[{"name":"hf_part_no","dataType":"varchar","nullable":false},{"name":"seq_no","dataType":"int4","nullable":true},{"name":"def_code","dataType":"varchar","nullable":false},{"name":"def_name","dataType":"varchar","nullable":true},{"name":"participating_parts","dataType":"varchar","nullable":true},{"name":"param_values","dataType":"varchar","nullable":true}]'::jsonb,
    '{}'::text[], 'GLOBAL', 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM component_sql_view WHERE sql_view_name = 'composite_process_mirror');

-- 3) 组合工艺组件:driver 改读新视图 + 4 个字段 BNF 重配($composite_process_mirror.xxx)
UPDATE component
SET data_driver_path = '$composite_process_mirror',
    fields = $FIELDS$[
  {"name":"序号","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"$composite_process_mirror.seq_no"},
  {"name":"工艺代码","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"$composite_process_mirror.def_code"},
  {"name":"参与配件","notes":"JSONB 数组 [hf_part_no, ...]","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"$composite_process_mirror.participating_parts"},
  {"name":"参数值","notes":"JSONB 按 def.param_schema","content":"","is_amount":false,"field_type":"BASIC_DATA","is_subtotal":false,"basic_data_path":"$composite_process_mirror.param_values"},
  {"name":"工艺单价","notes":"V1 手填; V2 关联 composite_process_def.unit_price","content":"","is_amount":true,"field_type":"INPUT_NUMBER","is_subtotal":true}
]$FIELDS$::jsonb,
    updated_at = NOW()
WHERE id = '3bbde78f-718c-4544-85f2-0a25397b7eaa';
